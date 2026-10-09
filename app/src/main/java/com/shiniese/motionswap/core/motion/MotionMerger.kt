package com.shiniese.motionswap.core.motion

import com.shiniese.motionswap.core.xmp.JpegSegmentWriter
import com.shiniese.motionswap.core.xmp.MotionXmpBuilder
import com.shiniese.motionswap.core.xmp.MotionXmpEditor
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * Writes a still image and its video as one embedded motion photo.
 *
 * The image bytes are streamed through untouched apart from an XMP segment swap, so
 * there is no decode/encode step and no quality loss. The video is appended verbatim.
 */
object MotionMerger {

    sealed interface Result {
        data class Success(val bytesWritten: Long) : Result
        data class Failure(val reason: String) : Result
    }

    /**
     * Combines [image] and [video] into [output] using [format]. Neither stream is
     * closed; the caller owns them.
     *
     * [videoLength] must be the byte count of [video] exactly as it will be written, before
     * any trailing box this method adds. A `VIVO` merge additionally appends
     * [VivoMediaTail.BOX] unless [videoHasVivoTail] says the clip already ends with it — set
     * that flag from [VivoMediaTail.endsWithTail], because the decision has to be made before
     * the still is written, and only the caller can look at the video's tail without consuming
     * the stream. Passing `false` for a clip that already carries the box would write a second
     * one; passing `true` for one that does not would leave vivo refusing the file.
     */
    fun merge(
        image: InputStream,
        video: InputStream,
        output: OutputStream,
        format: MotionFormat,
        videoLength: Long,
        videoHasVivoTail: Boolean = false,
    ): Result {
        if (videoLength <= 0) return Result.Failure("视频文件为空")

        // vivo reads the trailer, so the length we declare and the bytes we write have to be
        // settled before the XMP is written — the still precedes the video in the file.
        val appendTail = format == MotionFormat.VIVO && !videoHasVivoTail
        val declaredLength =
            if (appendTail) videoLength + VivoMediaTail.SIZE else videoLength

        return try {
            val imageOk =
                if (MotionXmpBuilder.carriesNoMetadata(format)) {
                    copyVerbatim(image, output)
                    true
                } else {
                    // Edit the photo's own packet rather than replacing it. A packet built from
                    // scratch describes only the video, which orphans any gain map the image
                    // already carried and leaves vendors reading a file they consider corrupt.
                    JpegSegmentWriter.writeEditingXmp(image, output) { existing ->
                        if (existing == null) {
                            MotionXmpBuilder.build(format, declaredLength)
                        } else {
                            MotionXmpEditor.addMotion(existing, format, declaredLength)
                        }
                    }
                }
            if (!imageOk) return Result.Failure("图片格式不受支持，请先转为 JPEG")

            copyVerbatim(video, output)
            if (appendTail) output.write(VivoMediaTail.BOX)
            Result.Success((output as? CountingStream)?.count ?: declaredLength)
        } catch (e: Exception) {
            Result.Failure(e.message ?: "写入失败")
        }
    }

    /** Wraps [output] so [merge] can report how many bytes landed. */
    fun counting(output: OutputStream): OutputStream = CountingStream(output)

    private class CountingStream(private val delegate: OutputStream) : FilterOutputStream(delegate) {
        var count: Long = 0
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

    private fun copyVerbatim(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(BUFFER)
        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            output.write(buffer, 0, read)
        }
    }

    private const val BUFFER = 64 * 1024
}