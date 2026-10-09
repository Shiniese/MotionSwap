package com.shiniese.motionswap.task

import android.content.ContentResolver
import androidx.exifinterface.media.ExifInterface
import com.shiniese.motionswap.core.model.MotionPhotoLayout
import com.shiniese.motionswap.core.model.MotionProbe
import com.shiniese.motionswap.core.motion.ByteSampler
import com.shiniese.motionswap.core.motion.ExtractTarget
import com.shiniese.motionswap.core.motion.MotionFormat
import com.shiniese.motionswap.core.motion.MotionMerger
import com.shiniese.motionswap.core.motion.MotionProbeReader
import com.shiniese.motionswap.core.motion.VivoMediaTail
import com.shiniese.motionswap.core.motion.VivoPairBlocks
import com.shiniese.motionswap.core.xmp.JpegSegmentWriter
import com.shiniese.motionswap.core.xmp.StreamIo
import com.shiniese.motionswap.data.MediaImage
import com.shiniese.motionswap.data.MediaStoreWriter
import com.shiniese.motionswap.data.SiblingVideo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream

/**
 * Splits embedded motion photos into a still image and a video file.
 *
 * Detection reads only the head and tail of the file plus a sliver at any offset the
 * metadata names; the bodies are streamed in fixed-size ranges so a 30MB photo never
 * lands on the heap whole.
 */
class ExtractWorker(
    private val resolver: ContentResolver,
    private val writer: MediaStoreWriter,
) {

    /** Identifies a file without writing anything, for the pre-flight list. */
    suspend fun probe(image: MediaImage): MotionProbe = withContext(Dispatchers.IO) {
        val size = image.sizeBytes
        if (size <= 0) return@withContext MotionProbe.NotMotionPhoto
        MotionProbeReader.probe(
            sampler = sampler(image, size),
            totalSize = size,
            make = readMake(image),
        )
    }

    suspend fun extract(
        image: MediaImage,
        subdirectory: String,
        target: ExtractTarget = ExtractTarget.PLAIN,
    ): Pair<ItemStatus, String?> = withContext(Dispatchers.IO) {
        val size = image.sizeBytes
        if (size <= 0) return@withContext (ItemStatus.FAILED to "无法读取文件")
        val probe = MotionProbeReader.probe(
            sampler = sampler(image, size),
            totalSize = size,
            make = readMake(image),
        )
        if (probe !is MotionProbe.Detected) {
            return@withContext (ItemStatus.SKIPPED to "不是动图")
        }
        writeParts(image, probe.layout, subdirectory, target)
    }

    /** Random-access reader used by detection to sample the head, tail and candidate offsets. */
    private fun sampler(image: MediaImage, size: Long): ByteSampler = ByteSampler { offset, length ->
        if (offset < 0 || length <= 0 || offset >= size) return@ByteSampler null
        val wanted = minOf(length.toLong(), size - offset).toInt()
        try {
            resolver.openInputStream(image.uri)?.use { input ->
                val buffer = ByteArray(wanted)
                if (readAt(input, offset, buffer) < wanted) return@use null
                buffer
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Seeks to [offset] and fills [buffer], returning how many bytes were actually read. */
    private fun readAt(input: InputStream, offset: Long, buffer: ByteArray): Int {
        var remaining = offset
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
            } else {
                if (input.read() == -1) return -1
                remaining--
            }
        }
        var filled = 0
        while (filled < buffer.size) {
            val read = input.read(buffer, filled, buffer.size - filled)
            if (read == -1) break
            filled += read
        }
        return filled
    }

    private fun writeParts(
        image: MediaImage,
        layout: MotionPhotoLayout,
        subdirectory: String,
        target: ExtractTarget = ExtractTarget.PLAIN,
    ): Pair<ItemStatus, String?> {
        // One id per photo, shared by both files, decided before either is written: the
        // still has to land first and its trailing block has to be inside the byte count
        // MediaStore is told about.
        val liveId = if (target == ExtractTarget.VIVO_PAIR) VivoPairBlocks.newId() else null

        val imageResult = writer.writeImage(
            subdirectory = subdirectory,
            displayName = image.displayName,
        ) { output ->
            val written = writeStill(image, output, layout.imageLength, liveId != null)
            if (liveId != null) output.write(VivoPairBlocks.degs(liveId))
            written + if (liveId != null) VivoPairBlocks.DEGS_SIZE.toLong() else 0L
        }
        if (imageResult is MediaStoreWriter.Result.Failure) {
            return ItemStatus.FAILED to imageResult.reason
        }

        // The clip keeps its own box order untouched — device testing showed vivo pairs on
        // the id alone. What it cannot keep is an embedded-mode trailer: that box shares its
        // type with the pair box, so leaving one on would put two same-typed boxes with
        // different ids in the file, and vivo could match the stale one.
        val clipBytes = videoBytesToCopy(image, layout, liveId != null)
        val videoResult = writer.writeVideo(
            subdirectory = subdirectory,
            displayName = "${image.baseName}.mp4",
        ) { output ->
            openStream(image).use {
                skipFully(it, layout.videoOffset)
                copyRange(it, output, clipBytes)
            }
            if (liveId != null) output.write(VivoPairBlocks.uuidBox(liveId))
            clipBytes + if (liveId != null) VivoPairBlocks.UUID_BOX_SIZE.toLong() else 0L
        }
        if (videoResult is MediaStoreWriter.Result.Failure) {
            return ItemStatus.FAILED to videoResult.reason
        }

        val still = (imageResult as MediaStoreWriter.Result.Success).displayName
        val clip = (videoResult as MediaStoreWriter.Result.Success).displayName
        return ItemStatus.DONE to "$still + $clip"
    }

    /**
     * How many bytes of the embedded video to copy: all of them, unless an embedded-mode
     * vivo trailer has to be dropped first.
     *
     * Decided before the copy rather than during it, so the clip is written once. Opening
     * the source a second time costs a file descriptor; the alternative is buffering clips
     * that run to tens of megabytes.
     */
    private fun videoBytesToCopy(
        image: MediaImage,
        layout: MotionPhotoLayout,
        dropVivoTail: Boolean,
    ): Long =
        if (dropVivoTail && endsWithVivoTail(image, layout)) layout.videoLength - VivoMediaTail.SIZE
        else layout.videoLength

    private fun endsWithVivoTail(image: MediaImage, layout: MotionPhotoLayout): Boolean {
        if (layout.videoLength < VivoMediaTail.SIZE) return false
        val tail = ByteArray(VivoMediaTail.SIZE)
        return try {
            openStream(image).use {
                skipFully(it, layout.videoOffset + layout.videoLength - VivoMediaTail.SIZE)
                StreamIo.readFully(it, tail).toInt() == VivoMediaTail.SIZE && VivoMediaTail.endsWithTail(tail)
            }
        } catch (e: IOException) {
            false
        }
    }

    /**
     * Writes the image half of a motion photo, dropping the XMP markers that point at a
     * video.
     *
     * The copy is bounded by [imageLength] because [input] is the whole motion photo. A JPEG
     * whose packet still says `MotionPhoto` with a video length invites the gallery to hunt
     * for a video that has just been written out as a separate file; any offset it derives
     * from that length lands in the wrong place. Files that cannot be rewritten — a PNG, or a
     * JPEG whose head does not parse — fall back to a plain truncated copy rather than
     * failing the whole item.
     */
    private fun writeStill(
        image: MediaImage,
        output: java.io.OutputStream,
        imageLength: Long,
        stopAtEoi: Boolean = false,
    ): Long {
        val counter = CountingOutputStream(output)
        openStream(image).use { input ->
            val ok = if (stopAtEoi) {
                JpegSegmentWriter.writeStillEndingAtEoi(input, counter, imageLength)
            } else {
                JpegSegmentWriter.writeWithoutMotion(input, counter, imageLength)
            }
            if (ok) return counter.count
        }
        // The head did not parse, so nothing was written; fall back to a plain truncated
        // copy so the still is still extracted.
        return openStream(image).use { copyRange(it, counter, imageLength) }
    }

    /** Tally so MediaStoreWriter is told how many bytes the still actually took. */
    private class CountingOutputStream(private val delegate: java.io.OutputStream) :
        java.io.FilterOutputStream(delegate) {
        var count = 0L
            private set

        override fun write(b: Int) {
            delegate.write(b)
            count++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            delegate.write(b, off, len)
            count += len
        }
    }

    private fun openStream(image: MediaImage): InputStream =
        resolver.openInputStream(image.uri) ?: throw IOException("无法读取 ${image.displayName}")

    /** Reads the EXIF Make tag, which Huawei-format detection depends on. */
    private fun readMake(image: MediaImage): String? = try {
        resolver.openInputStream(image.uri)?.use { input ->
            ExifInterface(input).getAttribute(ExifInterface.TAG_MAKE)
        }
    } catch (e: Exception) {
        null
    }

    /** Copies exactly [length] bytes; returns the number actually written. */
    private fun copyRange(
        input: InputStream,
        output: java.io.OutputStream,
        length: Long,
    ): Long {
        val buffer = ByteArray(COPY_BUFFER)
        var remaining = length
        var total = 0L
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read == -1) break
            output.write(buffer, 0, read)
            remaining -= read
            total += read
        }
        return total
    }

    /** `skip` is allowed to be a no-op, so fall back to reading single bytes. */
    private fun skipFully(input: InputStream, count: Long) {
        var remaining = count
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
            } else {
                if (input.read() == -1) throw IOException("文件长度不足")
                remaining--
            }
        }
    }

    companion object {
        private const val COPY_BUFFER = 64 * 1024
    }
}

/**
 * Combines a still image with its sibling video into one embedded motion photo.
 *
 * The video is passed in rather than looked up here. Discovery lives in one place —
 * `SiblingVideoFinder`, driven by the ViewModel — because two lookup paths is what let merge
 * depend on the user asking for a pre-flight pass before it would find anything.
 */
class MergeWorker(
    private val resolver: ContentResolver,
    private val writer: MediaStoreWriter,
) {

    suspend fun merge(
        image: MediaImage,
        video: SiblingVideo,
        format: MotionFormat,
        subdirectory: String,
    ): Pair<ItemStatus, String?> = withContext(Dispatchers.IO) {
        if (!image.isJpegLike) {
            return@withContext (ItemStatus.SKIPPED to "仅支持 JPEG，请先转换格式")
        }
        // Read before opening the copy stream: the merger has to know whether to add vivo's
        // trailer box before it writes the still, and that answer lives in the clip's last
        // bytes. Opening the URI twice costs a descriptor, not memory — the alternative is
        // buffering whole clips, which run to tens of megabytes.
        val clipHasVivoTail =
            if (format == MotionFormat.VIVO) hasVivoTail(video) else false
        val result = writer.writeImage(
            subdirectory = subdirectory,
            displayName = image.displayName,
        ) { output ->
            openStream(image).use { still ->
                openStream(video.uri, video.displayName).use { clip ->
                    when (val merged = MotionMerger.merge(
                        still, clip, MotionMerger.counting(output), format,
                        video.sizeBytes, clipHasVivoTail,
                    )) {
                        is MotionMerger.Result.Success -> merged.bytesWritten
                        is MotionMerger.Result.Failure ->
                            throw IOException(merged.reason)
                    }
                }
            }
        }
        when (result) {
            is MediaStoreWriter.Result.Failure -> ItemStatus.FAILED to result.reason
            is MediaStoreWriter.Result.Success ->
                ItemStatus.DONE to "${result.displayName} (${format.displayName})"
        }
    }

    /**
     * Whether the clip already ends with vivo's trailer box. A clip too short to hold one
     * answers `false`, which is the safe direction: the box gets appended.
     */
    private fun hasVivoTail(video: SiblingVideo): Boolean {
        if (video.sizeBytes < VivoMediaTail.SIZE) return false
        val tail = ByteArray(VivoMediaTail.SIZE)
        var read = 0
        try {
            openStream(video.uri, video.displayName).use { input ->
                StreamIo.skipFully(input, video.sizeBytes - VivoMediaTail.SIZE)
                read = StreamIo.readFully(input, tail).toInt()
            }
        } catch (e: IOException) {
            return false
        }
        return read == VivoMediaTail.SIZE && VivoMediaTail.endsWithTail(tail)
    }

    private fun openStream(uri: android.net.Uri, name: String): InputStream =
        resolver.openInputStream(uri) ?: throw IOException("无法读取 $name")

    private fun openStream(image: MediaImage): InputStream =
        openStream(image.uri, image.displayName)
}