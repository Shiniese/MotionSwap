package com.shiniese.motionswap.core.xmp

/**
 * Locates and slices the XMP packet out of a raw image byte blob.
 *
 * XMP in a JPEG always lives in a leading APP1 segment, so callers may pass just a
 * header prefix; passing the whole blob also works.
 */
object XmpPacket {

    private val XMP_META_OPEN = "<x:xmpmeta".toByteArray(Charsets.US_ASCII)
    private val XMP_META_CLOSE = "</x:xmpmeta>".toByteArray(Charsets.US_ASCII)
    private val XPACKET_BEGIN = "<?xpacket begin=".toByteArray(Charsets.US_ASCII)
    private val XPACKET_END = "<?xpacket end=".toByteArray(Charsets.US_ASCII)
    private val XPACKET_TERMINATOR = "?>".toByteArray(Charsets.US_ASCII)

    /**
     * Locates the packet the way a reader does: inside the APP1 whose payload begins with the
     * Adobe XMP identifier.
     *
     * [locate] on its own searches raw bytes, which is the wrong tool for a JPEG. Cameras keep a
     * *second* copy of the packet in Exif tag 700, and it is not the same document — after a
     * merge the two are kept in step, but rewriting tag 700 is append-only (see
     * [ExifXmlPacket]), so the packet it supersedes is still in the block as dead space. A raw
     * scan hits that dead copy first and reports the layout the file *used* to have. Since
     * `Exif\0\0` sorts before `http://…` in the Exif block, the stale copy also comes first in
     * memory, so this is not a rare ordering.
     *
     * Scanning is bounded to the segments before SOS: past that point is entropy-coded image
     * data, and a packet cannot legitimately appear there. The range returned is absolute, so
     * callers slice [data] with it directly.
     *
     * Returns null when the blob is not a JPEG or carries no standard XMP APP1. A file with only
     * an extended-XMP APP1 is a separate document that the motion markup does not live in, so it
     * is skipped rather than concatenated — see [JpegSegmentWriter].
     */
    fun locateInJpeg(data: ByteArray): ByteRange? {
        if (!ByteSearch.startsWith(data, JpegMarkers.SOI, 0)) return null

        var at = 2
        while (at + JpegMarkers.SEGMENT_HEADER_SIZE <= data.size) {
            if ((data[at].toInt() and 0xFF) != JpegMarkers.MARKER_PREFIX) return null
            val marker = data[at + 1].toInt() and 0xFF
            if (marker == JpegMarkers.MARKER_SOS || marker == JpegMarkers.MARKER_EOI) return null
            if (!JpegMarkers.hasPayload(marker)) {
                at += 2
                continue
            }

            val segmentLength = ByteSearch.readUInt16BE(data, at + 2)
            if (segmentLength < 2) return null
            val segmentEnd = at + 2 + segmentLength
            if (segmentEnd > data.size) return null

            if (marker == JpegMarkers.MARKER_APP1 &&
                ByteSearch.startsWith(data, JpegMarkers.XMP_IDENTIFIER, at + 4)
            ) {
                val from = at + 4 + JpegMarkers.XMP_IDENTIFIER.size
                val range = locate(data.copyOfRange(from, segmentEnd)) ?: return null
                return ByteRange(from + range.start, from + range.endExclusive)
            }
            at = segmentEnd
        }
        return null
    }

    /**
     * Returns the byte range of the XMP packet, or null when the blob carries none.
     * Prefers the `<x:xmpmeta>` envelope, falls back to a raw `<?xpacket ?>` block.
     */
    fun locate(data: ByteArray): ByteRange? {
        val metaOpen = ByteSearch.indexOf(data, XMP_META_OPEN)
        if (metaOpen >= 0) {
            val metaClose = ByteSearch.indexOf(data, XMP_META_CLOSE, metaOpen)
            if (metaClose >= 0) {
                return ByteRange(metaOpen, metaClose + XMP_META_CLOSE.size)
            }
        }

        val xpOpen = ByteSearch.indexOf(data, XPACKET_BEGIN)
        if (xpOpen >= 0) {
            val xpEnd = ByteSearch.indexOf(data, XPACKET_END, xpOpen)
            if (xpEnd >= 0) {
                val tail = ByteSearch.indexOf(data, XPACKET_TERMINATOR, xpEnd)
                if (tail >= 0) return ByteRange(xpOpen, tail + XPACKET_TERMINATOR.size)
            }
        }
        return null
    }

    /**
     * Trims leading junk (BOM, padding, stray bytes) so the payload starts at a real
     * XML tag. Mirrors the Swift implementation's cleaning step.
     */
    fun normalize(payload: ByteArray): ByteArray {
        val asText = String(payload, Charsets.UTF_8)
        val cut = firstTagIndex(asText) ?: return payload
        if (cut <= 0) return payload
        return asText.substring(cut).toByteArray(Charsets.UTF_8)
    }

    private fun firstTagIndex(text: String): Int? =
        listOf("<x:", "<?xpacket", "<")
            .map { text.indexOf(it) }
            .filter { it >= 0 }
            .minOrNull()
}