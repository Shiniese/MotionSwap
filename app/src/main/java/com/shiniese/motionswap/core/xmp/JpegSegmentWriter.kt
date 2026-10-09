package com.shiniese.motionswap.core.xmp

import java.io.InputStream
import java.io.OutputStream

/**
 * Rewrites the XMP APP1 segment of a JPEG without touching the compressed image data.
 *
 * A JPEG is `FFD8` followed by a chain of marker segments, each `FFxx` + 2-byte
 * big-endian length (length includes itself) + payload. The XMP packet lives in an APP1
 * whose payload starts with the `http://ns.adobe.com/xap/1.0/\0` identifier. To swap in
 * a new packet we drop every existing XMP APP1 and put ours back where it was, leaving
 * the Exif APP1 and all entropy-coded data byte-identical.
 *
 * Position is not cosmetic. Every camera-made sample in `samples/` writes Exif first and
 * XMP second, and MotionSwap was the only thing in the set writing them the other way
 * round — the one file vivo's gallery refused. Reading a vendor's parser is guesswork;
 * writing a segment back where the vendor wrote it is not.
 *
 * The Exif APP1 is preserved byte for byte apart from tag 700, which holds the same packet a
 * second time. Which copy a gallery reads is not something the spec settles: vivo's reads
 * tag 700 and ignores the APP1 entirely, so a file whose tag 700 still advertises the old
 * layout is refused no matter how correct the APP1 is. Leaving the two copies out of step is
 * the failure, so a rewritten packet is mirrored into both. See [ExifXmlPacket].
 */
object JpegSegmentWriter {

    /**
     * A sanity bound while *reading* a length field, not a format limit.
     *
     * Distinct from `ExifXmlPacket`'s ceiling of `0xFFFF`: that one is the width of the length
     * field itself, whereas this rejects a length that is legal-looking but plainly wrong — a
     * segment that claims 1 MiB inside a 4 KB photo is a misparse, and allocating for it would
     * turn a bad file into an allocation failure.
     */
    private const val MAX_SEGMENT = 1 shl 20

    /** The `FF xx` marker prefix: two bytes on the wire, always. */
    private const val MARKER_SIZE = 2L

    /**
     * The leading segments of a JPEG with its XMP packet set aside.
     *
     * [xmp] is null when the file carries none. It is a string rather than bytes because the
     * editor works on XML text; keeping it here means callers never have to re-parse the
     * head to find out whether there is a packet to preserve.
     *
     * [xmpSlot] is where the packet belongs in [segments] — the position of the XMP APP1 it
     * replaces, or the spot just past the Exif APP1 when the photo had no packet. Keeping the
     * vendor's own ordering is the point; see the note on segment position above.
     *
     * [consumed] is how many bytes were taken off the stream, which differs from what the
     * rewritten head writes back. It is what a caller's byte budget has to be measured from.
     */
    private class Head(
        val segments: List<ByteArray>,
        val xmp: String?,
        val xmpSlot: Int,
        val consumed: Long,
    )

    /**
     * Streams [input] to [output], replacing any XMP packet with [newXmp].
     * Returns false when the input is not a JPEG we can safely rewrite.
     */
    fun writeWithXmp(input: InputStream, output: OutputStream, newXmp: ByteArray?): Boolean =
        write(input, output, { newXmp }, Long.MAX_VALUE, stopAtEoi = false)

    /**
     * Streams [input] to [output], editing whatever XMP packet it already carries.
     *
     * [edit] receives the existing packet and returns the replacement, so a merge can add a
     * video to a photo's own metadata instead of overwriting it. Returning null drops the
     * packet, which is what a photo that had none keeps.
     */
    fun writeEditingXmp(
        input: InputStream,
        output: OutputStream,
        edit: (String?) -> ByteArray?,
    ): Boolean = write(input, output, edit, Long.MAX_VALUE, stopAtEoi = false)

    /**
     * Streams the first [limit] bytes of [input] to [output] with every motion-photo marker
     * removed from its XMP.
     *
     * Used when splitting: the still that comes out is a photograph, and a packet still
     * announcing a video points the gallery at bytes that are no longer there. A file with no
     * motion markers keeps its packet exactly as it was — gain maps and HDR hints stay.
     *
     * [limit] is honoured because the caller's input is the whole motion photo and only the
     * image portion of it is wanted. It is measured against the bytes read off the input, not
     * against the bytes written back, so a rewritten head cannot shift the cut point and let
     * the still end mid-scan or run on into the video.
     */
    fun writeWithoutMotion(input: InputStream, output: OutputStream, limit: Long): Boolean =
        write(input, output, ::editWithoutMotion, limit, stopAtEoi = false)

    /**
     * As [writeWithoutMotion], but ends the still at the JPEG's own end-of-image marker
     * instead of at [limit].
     *
     * [limit] is where the container says the video begins, and vendors disagree about
     * whether anything sits in between: `Pixel.jpg` has 32,364 bytes of `aecDebug` data
     * after its EOI, `Samsung.jpg` has 138 bytes of `Image_UTC_Data`. Copying to [limit]
     * drags that blob into the extracted still. Dropping it is what a consumer of the
     * block that follows such a still expects — vivo looks for its `streamdataDEGS` marker
     * immediately after the EOI, and the only device-verified layout has it there — so
     * extraction into a paired file stops at the EOI.
     */
    fun writeStillEndingAtEoi(
        input: InputStream,
        output: OutputStream,
        limit: Long,
    ): Boolean = write(input, output, ::editWithoutMotion, limit, stopAtEoi = true)

    /**
     * Runs unconditionally on a packet that exists, rather than only when it announces a
     * video: the extended-XMP reference has to go either way, because the extended APP1 is not
     * carried over.
     */
    private fun editWithoutMotion(existing: String?): ByteArray? =
        existing?.let { MotionXmpEditor.stripMotion(it).toByteArray(Charsets.UTF_8) }

    private fun write(
        input: InputStream,
        output: OutputStream,
        edit: (String?) -> ByteArray?,
        limit: Long,
        stopAtEoi: Boolean,
    ): Boolean {
        val head = readHead(input) ?: return false
        val packet = edit(head.xmp)
        val app1 = packet?.let { buildXmpApp1(it) }
        val segments = if (packet == null) head.segments else syncExifTag(head.segments, packet)

        output.write(JpegMarkers.SOI)
        if (app1 == null) {
            for (bytes in segments) output.write(bytes)
        } else {
            segments.forEachIndexed { index, bytes ->
                if (index == head.xmpSlot) output.write(app1)
                output.write(bytes)
            }
            if (head.xmpSlot >= segments.size) output.write(app1)
        }
        // readHead consumed exactly the bytes held in `segments`, so the stream is already
        // positioned at the entropy-coded image data. The remaining budget comes off `limit`
        // by what was *read*: the rewritten head is usually a different size from the one it
        // replaces, and measuring the cut point against what was written would let a shorter
        // packet push the still's tail into the video.
        val budget = (limit - head.consumed).coerceAtLeast(0)
        if (stopAtEoi) StreamIo.copyRangeUntilEoi(input, output, budget)
        else StreamIo.copyRange(input, output, budget)
        return true
    }

    /**
     * Mirrors [packet] into Exif tag 700, in place, keeping segment order.
     *
     * Skipped when the segment already holds exactly those bytes. [ExifXmlPacket.replace] is
     * append-only by design, so rewriting an unchanged packet would still append a copy of it:
     * the file would grow by the packet's length on every pass, and each pass would leave one
     * more superseded packet behind as dead space. Splitting a photo the app has already stripped
     * is an ordinary thing for a user to do, so that repeat is the common case, not an edge one.
     * The comparison is byte-for-byte because a segment that differs is one that needs mirroring
     * — narrowing it to "differs in some field" would still append on a mere re-serialisation.
     *
     * A photo with no Exif block is left alone: there is no TIFF header to hang a tag off,
     * and inventing one is a bigger claim than this fix rests on. A segment that cannot be
     * edited is kept as it was rather than risk a partial rewrite.
     */
    private fun syncExifTag(segments: List<ByteArray>, packet: ByteArray): List<ByteArray> =
        segments.map { segment ->
            if (!JpegMarkers.isExifApp1(segment)) segment
            else if (ExifXmlPacket.read(segment)?.contentEquals(packet) == true) segment
            else ExifXmlPacket.replace(segment, packet) ?: segment
        }

    /**
     * Reads every leading segment up to and including the SOS marker, setting aside XMP APP1
     * segments. Each returned segment is a verbatim byte array, so nothing is lost.
     * Returns null when the stream is not a well-formed JPEG.
     */
    private fun readHead(input: InputStream): Head? {
        val soi = ByteArray(2)
        if (StreamIo.readFully(input, soi) != 2L) return null
        if (!ByteSearch.startsWith(soi, JpegMarkers.SOI, 0)) return null

        val segments = mutableListOf<ByteArray>()
        var xmp: String? = null
        var xmpSlot = -1
        var consumed = JpegMarkers.SOI.size.toLong()
        while (true) {
            val marker = readMarker(input) ?: return null
            consumed += MARKER_SIZE

            // EOI means the image carries no scan data; the file simply ends here.
            if (marker == JpegMarkers.MARKER_EOI) {
                segments += byteArrayOf(JpegMarkers.MARKER_PREFIX.toByte(), JpegMarkers.MARKER_EOI.toByte())
                return head(segments, xmp, xmpSlot, consumed)
            }
            if (!JpegMarkers.hasPayload(marker)) {
                segments += byteArrayOf(JpegMarkers.MARKER_PREFIX.toByte(), marker.toByte())
                continue
            }

            val lengthBytes = ByteArray(2)
            if (StreamIo.readFully(input, lengthBytes) != 2L) return null
            val segmentLength = ByteSearch.readUInt16BE(lengthBytes, 0)
            if (segmentLength < 2 || segmentLength > MAX_SEGMENT) return null

            val payload = ByteArray(segmentLength - 2)
            if (StreamIo.readFully(input, payload) != payload.size.toLong()) return null
            consumed += segmentLength

            if (marker == JpegMarkers.MARKER_APP1 &&
                ByteSearch.startsWith(payload, JpegMarkers.XMP_IDENTIFIER, 0)
            ) {
                // A camera may write a second APP1 holding extended XMP: a whole separate
                // document, not a continuation (`samples/Pixel.jpg` has three). Only the first
                // packet carries the motion-photo markup, so that is the one kept; concatenating
                // the rest would glue two documents together into something unparseable.
                if (xmp == null) {
                    xmp = String(
                        payload,
                        JpegMarkers.XMP_IDENTIFIER.size,
                        payload.size - JpegMarkers.XMP_IDENTIFIER.size,
                        Charsets.UTF_8,
                    )
                    xmpSlot = segments.size
                }
                continue
            }

            segments += ByteArray(JpegMarkers.SEGMENT_HEADER_SIZE + payload.size).also {
                it[0] = JpegMarkers.MARKER_PREFIX.toByte()
                it[1] = marker.toByte()
                System.arraycopy(lengthBytes, 0, it, 2, 2)
                System.arraycopy(payload, 0, it, 4, payload.size)
            }

            // Everything from SOS onward is entropy-coded image data.
            if (marker == JpegMarkers.MARKER_SOS) return head(segments, xmp, xmpSlot, consumed)
        }
    }

    /**
     * Decides where the replacement packet goes.
     *
     * A photo that already had one gets it back in the vendor's own slot. A photo that had
     * none gets the slot cameras use — immediately after the Exif APP1, which is where all
     * of them in `samples/` put it.
     */
    private fun head(
        segments: List<ByteArray>,
        xmp: String?,
        xmpSlot: Int,
        consumed: Long,
    ): Head {
        if (xmpSlot >= 0) return Head(segments, xmp, xmpSlot, consumed)
        val afterExif = segments.indexOfFirst { JpegMarkers.isExifApp1(it) } + 1
        return Head(segments, xmp, afterExif, consumed)
    }

    /**
     * Reads the `FF xx` marker prefix. Runs of `FF` are legal fill bytes, so every
     * one of them is consumed and only the identifying byte is returned.
     */
    private fun readMarker(input: InputStream): Int? {
        var byte = readByte(input) ?: return null
        while (byte != JpegMarkers.MARKER_PREFIX) {
            // Not a marker prefix: the stream is not a well-formed JPEG.
            return null
        }
        do {
            byte = readByte(input) ?: return null
        } while (byte == JpegMarkers.MARKER_PREFIX)
        return byte
    }

    private fun readByte(input: InputStream): Int? {
        val buffer = ByteArray(1)
        return if (StreamIo.readFully(input, buffer) == 1L) buffer[0].toInt() and 0xFF else null
    }

    private fun buildXmpApp1(xmp: ByteArray): ByteArray {
        val header = JpegMarkers.SEGMENT_HEADER_SIZE
        val segmentLength = JpegMarkers.XMP_IDENTIFIER.size + xmp.size + 2
        val out = ByteArray(header + JpegMarkers.XMP_IDENTIFIER.size + xmp.size)
        out[0] = JpegMarkers.MARKER_PREFIX.toByte()
        out[1] = JpegMarkers.MARKER_APP1.toByte()
        out[2] = (segmentLength shr 8).toByte()
        out[3] = (segmentLength and 0xFF).toByte()
        System.arraycopy(JpegMarkers.XMP_IDENTIFIER, 0, out, header, JpegMarkers.XMP_IDENTIFIER.size)
        System.arraycopy(xmp, 0, out, header + JpegMarkers.XMP_IDENTIFIER.size, xmp.size)
        return out
    }
}