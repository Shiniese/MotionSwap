package com.shiniese.motionswap.core.xmp

/**
 * The byte-level shape of a JPEG's marker chain.
 *
 * A JPEG is `FF D8`, then a sequence of marker segments: `FF xx`, a 2-byte big-endian length
 * that counts itself, then `length - 2` payload bytes. Most markers carry such a payload; TEM and
 * the restart markers carry neither, and getting that wrong desynchronises everything after it.
 *
 * These constants were duplicated in [JpegSegmentWriter], [XmpPacket] and [ExifXmlPacket], and
 * duplication there is not a tidiness problem — the APP1 identifier decides *which* segment is
 * the XMP packet, so a writer and a reader that disagreed about it would each act on a different
 * segment and leave a file whose metadata neither of them can read. One definition, so they
 * cannot.
 */
internal object JpegMarkers {

    /** Start of image: the two bytes every JPEG opens with. */
    val SOI: ByteArray = byteArrayOf(0xFF.toByte(), 0xD8.toByte())

    /** The `FF` that introduces every marker. */
    const val MARKER_PREFIX = 0xFF

    /** Application segment 1, where both the Exif and the XMP packets live. */
    const val MARKER_APP1 = 0xE1

    /** Start of scan: everything past this is entropy-coded image data, not metadata. */
    const val MARKER_SOS = 0xDA

    /** End of image. */
    const val MARKER_EOI = 0xD9

    /** Marks the end of a segment: 2 marker bytes + 2 length bytes, before any payload. */
    const val SEGMENT_HEADER_SIZE = 4

    /** `http://ns.adobe.com/xap/1.0/\0` — the standard XMP packet. */
    val XMP_IDENTIFIER: ByteArray =
        "http://ns.adobe.com/xap/1.0/".toByteArray(Charsets.US_ASCII) + byteArrayOf(0)

    /** `Exif\0\0` — the Exif APP1, which carries tag 700. */
    val EXIF_IDENTIFIER: ByteArray =
        "Exif".toByteArray(Charsets.US_ASCII) + byteArrayOf(0, 0)

    /**
     * Whether [marker] is followed by a length and a payload, or stands alone.
     *
     * TEM (`01`) and the restart markers (`D0`–`D8`) carry nothing after the marker byte.
     */
    fun hasPayload(marker: Int): Boolean = marker != 0x01 && marker !in 0xD0..0xD8

    /** Whether [segment] — marker, length and payload — is the Exif APP1. */
    fun isExifApp1(segment: ByteArray): Boolean = hasIdentifier(segment, EXIF_IDENTIFIER)

    /** Whether [segment] is an APP1 whose payload begins with the standard XMP identifier. */
    fun isXmpApp1(segment: ByteArray): Boolean = hasIdentifier(segment, XMP_IDENTIFIER)

    /**
     * Whether an APP1 [segment] begins with [identifier].
     *
     * The size check is [ByteSearch.startsWith]'s own: a segment too short to hold the marker,
     * the length and the identifier is not that kind of APP1.
     */
    private fun hasIdentifier(segment: ByteArray, identifier: ByteArray): Boolean =
        segment.size >= 2 &&
            (segment[0].toInt() and 0xFF) == MARKER_PREFIX &&
            (segment[1].toInt() and 0xFF) == MARKER_APP1 &&
            ByteSearch.startsWith(segment, identifier, SEGMENT_HEADER_SIZE)
}