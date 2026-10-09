package com.shiniese.motionswap.core.motion

import com.shiniese.motionswap.core.model.MotionOrigin
import com.shiniese.motionswap.core.model.MotionPhotoLayout
import com.shiniese.motionswap.core.model.MotionProbe
import com.shiniese.motionswap.core.xmp.XmpFields
import com.shiniese.motionswap.core.xmp.XmpPacket

/**
 * Reads [length] bytes at an absolute file offset, or null when they cannot be read.
 *
 * Detection needs three different windows — the head for the XMP packet, the tail for
 * the MP4 box, and a sliver at a metadata-declared offset to confirm it — so the
 * probing strategy takes a sampler rather than a pre-loaded array.
 */
fun interface ByteSampler {
    fun read(offset: Long, length: Int): ByteArray?
}

/** How much of each end of the file detection reads. */
object ProbeWindows {
    /** XMP lives in a leading APP1 segment, so a modest head window is enough. */
    const val HEAD_BYTES = 256 * 1024

    /**
     * Size of one step of the streaming `ftyp` scan. The box can sit anywhere — not
     * merely near the end — because the appended video length is unbounded, so the scan
     * walks the whole file in windows of this size rather than sampling a fixed tail.
     * `samples/HUAWEI.jpeg` is the reason: no XMP at all, and its box starts at 7,843,538
     * inside a 17,675,998-byte file, which no fixed head or tail window can reach.
     */
    const val SCAN_CHUNK_BYTES = 1024 * 1024

    /**
     * Overlap between consecutive windows so a box header straddling a boundary is still
     * seen whole: 4 bytes of box size plus the 4-byte `ftyp` signature.
     */
    const val SCAN_OVERLAP_BYTES = 8

    /** Enough to cover the box header and its `mp4` brand. */
    const val VERIFY_BYTES = 64
}

/**
 * Strategy for reading one vendor's motion photo layout out of an XMP field map.
 *
 * Implementations only need to reason about metadata; the shared machinery that reads
 * the windows, validates offsets and falls back to signature scanning lives in
 * [MotionProbeReader].
 */
interface MotionFormatReader {

    val format: MotionFormat

    /** Returns a layout when this format's markers are present and usable, else null. */
    fun read(fields: Map<String, String>, totalSize: Long): MotionPhotoLayout?
}

/**
 * Xiaomi writes `GCamera:MicroVideoOffset`, the exact byte length of the appended MP4.
 * Unambiguous, so it is always tried first.
 */
object XiaomiReader : MotionFormatReader {

    override val format = MotionFormat.XIAOMI

    private const val OFFSET_KEY = "GCamera:MicroVideoOffset"

    override fun read(fields: Map<String, String>, totalSize: Long): MotionPhotoLayout? {
        val offset = fields[OFFSET_KEY]?.trim()?.toLongOrNull() ?: return null
        if (offset <= 0 || offset >= totalSize) return null
        return MotionPhotoLayout.of(totalSize - offset, offset).takeIf { it.isValid() }
    }
}

/**
 * Google-style container formats, as emitted by Pixel, Samsung and Vivo.
 *
 * Every observed variant declares the appended video length and nothing else that moves
 * where it starts:
 *  - `GContainer:ItemLength = "0, N"` (Pixel)
 *  - `Container Item Length = "0, N"` (Samsung, Vivo)
 *  - `GCamera:MotionPhoto = 1` alongside either of the above
 *
 * The video is therefore always the trailing [N] bytes. This is what the reference
 * implementation actually computes for every real file: its `Item:*` collector always
 * synthesises the `GContainer:ItemLength` key, so the length-only branch runs first and
 * the padding branch below it is unreachable.
 *
 * `Container Item Padding` is deliberately ignored. Samsung writes `Item:Padding="138,0"`
 * where 138 is the gap *after* the JPEG, which places the video at `size - N`; treating
 * it as an offset shift instead pushes the end past EOF and rejects the file. Verified
 * against `samples/Samsung.jpg`: the MP4 box starts at exactly `size - 5421673`.
 */
object GoogleReader : MotionFormatReader {

    override val format = MotionFormat.GOOGLE

    private const val TIMESTAMP_KEY = "GCamera:MotionPhotoPresentationTimestampUs"
    private const val LEGACY_TIMESTAMP_KEY = "Motion Photo Presentation Timestamp Us"
    private const val MOTION_PHOTO_KEY = "GCamera:MotionPhoto"

    private val LENGTH_KEYS = listOf(
        "GContainer:ItemLength",
        "Container Item Length",
        "Directory Item Length",
    )

    fun presentationTimestamp(fields: Map<String, String>): Long? =
        fields[TIMESTAMP_KEY]?.trim()?.toLongOrNull()
            ?: fields[LEGACY_TIMESTAMP_KEY]?.trim()?.toLongOrNull()

    override fun read(fields: Map<String, String>, totalSize: Long): MotionPhotoLayout? {
        val lengthKey = LENGTH_KEYS.firstOrNull { fields.containsKey(it) } ?: return null
        val videoLength = parseVideoLength(fields[lengthKey]) ?: return null
        if (videoLength <= 0 || videoLength >= totalSize) return null

        val videoStart = totalSize - videoLength
        if (videoStart <= 0) return null

        return MotionPhotoLayout.of(videoStart, videoLength).takeIf { it.isValid() }
    }

    /** Reads the video length out of the `"imageLength, videoLength"` pair these keys carry. */
    private fun parseVideoLength(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        val parts = raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size < 2) return null
        return parts[1].toLongOrNull()
    }

    fun declaresMotion(fields: Map<String, String>): Boolean =
        fields.containsKey(MOTION_PHOTO_KEY)
}

/**
 * Huawei writes no motion photo XMP at all, so identification relies on the EXIF
 * Make/Manufacturer; the video itself is then located by signature.
 */
object HuaweiReader : MotionFormatReader {

    override val format = MotionFormat.HUAWEI

    override fun read(fields: Map<String, String>, totalSize: Long): MotionPhotoLayout? =
        null

    fun matchesMake(make: String?, manufacturer: String?): Boolean =
        listOfNotNull(make, manufacturer).any { it.equals("HUAWEI", ignoreCase = true) }
}

/**
 * vivo writes Google's container plus its own `VCamera` version markers.
 *
 * The layout is byte-for-byte Google's — the video is the trailing `Item:Length` bytes — so
 * this reader owns no parsing of its own and delegates to [GoogleReader]. All it adds is
 * identity, and it must be tried *before* [GoogleReader] or every vivo photo would be
 * reported as "Google / Pixel / 三星".
 *
 * Identity is what steers the user: the detected origin is the hint at which merge target to
 * pick, and picking Google for a vivo phone produces a file that looks valid and plays as a
 * still, because vivo's gallery requires these very markers before it will show motion.
 */
object VivoReader : MotionFormatReader {

    override val format = MotionFormat.VIVO

    private const val V_MOTION_PHOTO_VERSION = "VCamera:VMotionPhotoVersion"

    override fun read(fields: Map<String, String>, totalSize: Long): MotionPhotoLayout? {
        if (!declaresVivoMotionPhoto(fields)) return null
        return GoogleReader.read(fields, totalSize)
    }

    /**
     * The marker must be present *and* declare motion; a `VMotionPhotoVersion` on a file with
     * no video item is a still that vivo stamped, not a motion photo.
     */
    fun declaresVivoMotionPhoto(fields: Map<String, String>): Boolean =
        fields.containsKey(V_MOTION_PHOTO_VERSION) && GoogleReader.declaresMotion(fields)
}

/**
 * Identifies a motion photo, in the same priority order as the reference implementation:
 * XMP-declared formats first, then a raw MP4 signature scan that also covers Huawei.
 *
 * A layout derived from XMP is only accepted once the bytes at the declared offset are
 * confirmed to be a real MP4 box. Stale XMP is the failure mode that otherwise slips
 * through: extracting a motion photo writes out the still frame with the original XMP
 * packet intact, so the still keeps advertising a video offset that now points into the
 * middle of the JPEG.
 */
object MotionProbeReader {

    // Vivo before Google: vivo is a superset of Google's markers, so the reverse order would
    // find a layout first and never get as far as checking who actually wrote the file.
    private val xmpReaders: List<MotionFormatReader> = listOf(XiaomiReader, VivoReader, GoogleReader)

    fun probe(
        sampler: ByteSampler,
        totalSize: Long,
        make: String? = null,
        manufacturer: String? = null,
    ): MotionProbe {
        val fields = readXmpFields(sampler, totalSize)

        for (reader in xmpReaders) {
            val layout = reader.read(fields, totalSize) ?: continue
            if (!verifyVideoStart(sampler, layout, totalSize)) continue
            return MotionProbe.Detected(
                origin = reader.format.origin,
                layout = layout,
                presentationTimestampUs = when (reader) {
                    is GoogleReader -> reader.presentationTimestamp(fields)
                    // vivo embeds Google's timestamp attribute unchanged, so its own value
                    // still applies; it is the same key the sample carries.
                    is VivoReader -> GoogleReader.presentationTimestamp(fields)
                    else -> null
                },
            )
        }

        val scan = scanForSignature(sampler, totalSize) ?: return MotionProbe.NotMotionPhoto
        val origin = if (HuaweiReader.matchesMake(make, manufacturer)) {
            MotionOrigin.HUAWEI
        } else {
            MotionOrigin.MP4_SIGNATURE
        }
        return MotionProbe.Detected(origin, scan, presentationTimestampUs = null)
    }

    /**
     * Parses the XMP packet out of the head window; empty when the file carries none.
     *
     * Located with [XmpPacket.locateInJpeg] rather than a raw byte scan. Cameras keep a second
     * copy of the packet in Exif tag 700, and rewriting that copy is append-only, so a file this
     * app has just merged still contains the *superseded* packet as dead space in its Exif
     * block — ahead of the live APP1 copy in memory, because `Exif\0\0` sorts before
     * `http://…`. A raw scan finds the dead copy and reports the layout the file used to have,
     * which made every merge read back as `MP4_SIGNATURE` and lose the vendor it had just been
     * given. See [XmpPacket.locateInJpeg].
     */
    private fun readXmpFields(sampler: ByteSampler, totalSize: Long): Map<String, String> {
        val headLength = minOf(ProbeWindows.HEAD_BYTES.toLong(), totalSize).toInt()
        val head = sampler.read(0, headLength) ?: return emptyMap()
        val range = XmpPacket.locateInJpeg(head) ?: return emptyMap()
        return XmpFields.parse(head.copyOfRange(range.start, range.endExclusive))
    }

    /** Confirms a valid MP4 box really begins where the metadata said the video starts. */
    private fun verifyVideoStart(
        sampler: ByteSampler,
        layout: MotionPhotoLayout,
        totalSize: Long,
    ): Boolean {
        val start = layout.videoOffset
        if (start <= 0 || start >= totalSize) return false
        val window = sampler.read(start, ProbeWindows.VERIFY_BYTES) ?: return false
        return Mp4BoxScanner.isVideoStartAt(window, start, start)
    }

    /**
     * Walks the file in overlapping windows looking for the MP4 box, mirroring the
     * reference implementation, which scans the entire blob rather than sampling one end.
     *
     * Only reached when the XMP readers found nothing, so files that declare themselves
     * cost one head read. The scan holds a single window at a time; the overlap guarantees
     * a box header split across a window boundary is still matched in the next window.
     */
    private fun scanForSignature(sampler: ByteSampler, totalSize: Long): MotionPhotoLayout? {
        val chunk = ProbeWindows.SCAN_CHUNK_BYTES
        val step = (chunk - ProbeWindows.SCAN_OVERLAP_BYTES).toLong()
        var offset = 0L

        while (offset < totalSize) {
            val length = minOf(chunk.toLong(), totalSize - offset).toInt()
            val window = sampler.read(offset, length) ?: return null

            Mp4BoxScanner.findVideoRegion(window, offset, totalSize)?.let { region ->
                MotionPhotoLayout(region.offset, region.length, totalSize)
                    .takeIf { it.isValid() }
                    ?.let { return it }
            }

            if (length < chunk) break
            offset += step
        }
        return null
    }
}
