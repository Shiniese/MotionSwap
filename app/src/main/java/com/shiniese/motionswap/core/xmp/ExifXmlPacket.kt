package com.shiniese.motionswap.core.xmp

/**
 * Exif tag `0x02BC` — the XMP packet, duplicated inside the Exif block.
 *
 * Cameras that write both an APP1 packet and this tag leave two copies of the same document
 * in one file, and they are not kept in step: editing the APP1 copy leaves this one describing
 * whatever the photo was before. Which copy a gallery consults is vendor-specific and cannot
 * be read off the spec, so the merge keeps them consistent instead of guessing.
 *
 * Every edit here is append-only. New value bytes go at the end of the TIFF block and the
 * entry is repointed at them; nothing already in the block moves. That is what makes it safe
 * to touch a photograph's metadata at all — MakerNotes carry offsets of their own (Xiaomi's
 * does), GPS pointers point into the block, and the thumbnail's `JPEGInterchangeFormat` is an
 * absolute offset from the TIFF header. Relocating any of that to make room would mean
 * rewriting offsets inside data whose layout is vendor-private. The superseded bytes are left
 * behind as dead space, which costs a few hundred bytes and cannot be observed.
 *
 * A tag that is absent is added by rebuilding IFD0 at the end and repointing the header,
 * again without moving what is already there.
 */
object ExifXmlPacket {

    /** Tag `700`, the XMP packet. */
    const val TAG = 0x02BC

    /** Byte order markers; the magic number 42 follows whichever one the file uses. */
    private val LITTLE_ENDIAN = byteArrayOf(0x49, 0x49, 0x2A, 0x00)
    private val BIG_ENDIAN = byteArrayOf(0x4D, 0x4D, 0x00, 0x2A)

    /**
     * `FF E1`, the two-byte length, and the `Exif\0\0` identifier put the TIFF header here.
     *
     * Derived from the segment layout rather than written out, so it cannot drift from the
     * identifier it is measured from.
     */
    private val TIFF_START = JpegMarkers.SEGMENT_HEADER_SIZE + JpegMarkers.EXIF_IDENTIFIER.size

    private const val ENTRY_SIZE = 12

    /** TIFF values begin on an even offset. */
    private fun alignEven(at: Int): Int = if (at % 2 == 0) at else at + 1

    /** The ceiling is the length field's: a segment cannot address more than `0xFFFF` bytes. */
    private const val MAX_SEGMENT = 0xFFFF

    private class Tiff(val bytes: ByteArray, val littleEndian: Boolean, val ifd0: Int)

    private class Entry(val at: Int)

    /**
     * Reads tag 700 out of an Exif APP1 segment, or null when the segment carries none or is
     * not a TIFF block we recognise.
     */
    fun read(exifApp1: ByteArray): ByteArray? {
        val tiff = locate(exifApp1) ?: return null
        val le = tiff.littleEndian
        val entry = entryFor(tiff.bytes, le, tiff.ifd0) ?: return null
        val size = readInt(tiff.bytes, entry.at + 4, le)
        if (size < 0 || size > MAX_SEGMENT) return null
        // Four bytes or fewer live in the entry itself; anything larger is an offset.
        val at = if (size <= 4) entry.at + 8 else readInt(tiff.bytes, entry.at + 8, le)
        if (at < 0 || at + size > tiff.bytes.size) return null
        return tiff.bytes.copyOfRange(at, at + size)
    }

    /**
     * Returns [exifApp1] with tag 700 set to [xml], or null when the segment cannot be edited
     * safely — a malformed TIFF header, or one that would outgrow the segment length field.
     */
    fun replace(exifApp1: ByteArray, xml: ByteArray): ByteArray? {
        val tiff = locate(exifApp1) ?: return null
        val le = tiff.littleEndian

        val existing = entryFor(tiff.bytes, le, tiff.ifd0)
        var buf = tiff.bytes
        var entryAt: Int
        var type: Int
        if (existing != null) {
            entryAt = existing.at
            type = readShort(buf, entryAt + 2, le)
        } else {
            val added = appendIfd0(buf, le, tiff.ifd0) ?: return null
            buf = added.first
            entryAt = added.second
            type = 1
        }

        // A value of four bytes or fewer is held in the entry itself and never appended;
        // anything larger goes at the end of the block and is pointed at.
        val inline = xml.size <= ENTRY_SIZE - 8
        val valueAt = if (inline) 0 else alignEven(buf.size)
        if (!inline && valueAt + xml.size > MAX_SEGMENT) return null
        val grown = if (inline) buf else buf.copyOf(valueAt + xml.size)
        if (!inline) System.arraycopy(xml, 0, grown, valueAt, xml.size)
        writeShort(grown, entryAt, TAG, le)
        writeShort(grown, entryAt + 2, type, le)
        writeInt(grown, entryAt + 4, xml.size, le)
        if (inline) {
            // Zero first, so a longer value that used to live here leaves nothing behind.
            for (i in 0 until 4) grown[entryAt + 8 + i] = 0
            System.arraycopy(xml, 0, grown, entryAt + 8, xml.size)
        } else {
            writeInt(grown, entryAt + 8, valueAt, le)
        }
        return wrap(exifApp1, grown)
    }

    /** The TIFF block on its own, stripped of the marker, length and `Exif\0\0` identifier. */
    private fun locate(segment: ByteArray): Tiff? {
        if (segment.size < TIFF_START + 8) return null
        if (segment[0].toInt() and 0xFF != JpegMarkers.MARKER_PREFIX ||
            segment[1].toInt() and 0xFF != JpegMarkers.MARKER_APP1
        ) return null
        val littleEndian = when {
            ByteSearch.startsWith(segment, LITTLE_ENDIAN, TIFF_START) -> true
            ByteSearch.startsWith(segment, BIG_ENDIAN, TIFF_START) -> false
            else -> return null
        }
        val ifd0 = readInt(segment, TIFF_START + 4, littleEndian)
        val tiff = segment.copyOfRange(TIFF_START, segment.size)
        if (ifd0 < 8 || ifd0 + 2 > tiff.size) return null
        return Tiff(tiff, littleEndian, ifd0)
    }

    private fun entryFor(tiff: ByteArray, le: Boolean, ifd0: Int): Entry? {
        val count = readShort(tiff, ifd0, le)
        for (i in 0 until count) {
            val at = ifd0 + 2 + i * ENTRY_SIZE
            if (at + ENTRY_SIZE > tiff.size) return null
            if (readShort(tiff, at, le) == TAG) return Entry(at)
        }
        return null
    }

    /**
     * Copies IFD0 to the end of the block with room for one more entry, leaving the original
     * in place, and points the TIFF header at the copy. Returns the new buffer and the offset
     * of the extra entry, or null when there is no room.
     */
    private fun appendIfd0(buf: ByteArray, le: Boolean, ifd0: Int): Pair<ByteArray, Int>? {
        val count = readShort(buf, ifd0, le)
        val source = ifd0 + 2
        if (source + count * ENTRY_SIZE + 4 > buf.size) return null
        // The pointer past IFD0 is what a reader follows to IFD1, the thumbnail.
        val next = readInt(buf, source + count * ENTRY_SIZE, le)
        val ifdAt = alignEven(buf.size)
        val entryAt = ifdAt + 2 + count * ENTRY_SIZE
        val size = entryAt + ENTRY_SIZE + 4
        if (size > MAX_SEGMENT) return null
        val out = buf.copyOf(size)
        writeShort(out, ifdAt, count + 1, le)
        System.arraycopy(buf, source, out, ifdAt + 2, count * ENTRY_SIZE)
        writeInt(out, entryAt + ENTRY_SIZE, next, le)
        writeInt(out, 4, ifdAt, le)
        return out to entryAt
    }

    private fun wrap(original: ByteArray, tiff: ByteArray): ByteArray {
        val out = ByteArray(TIFF_START + tiff.size)
        out[0] = JpegMarkers.MARKER_PREFIX.toByte()
        out[1] = JpegMarkers.MARKER_APP1.toByte()
        val payload = TIFF_START - 2 + tiff.size
        out[2] = (payload shr 8).toByte()
        out[3] = (payload and 0xFF).toByte()
        System.arraycopy(original, 4, out, 4, TIFF_START - 4)
        System.arraycopy(tiff, 0, out, TIFF_START, tiff.size)
        return out
    }

    private fun readShort(b: ByteArray, at: Int, le: Boolean): Int {
        val a = b[at].toInt() and 0xFF
        val c = b[at + 1].toInt() and 0xFF
        return if (le) a or (c shl 8) else (a shl 8) or c
    }

    private fun readInt(b: ByteArray, at: Int, le: Boolean): Int {
        var v = 0
        for (i in 0 until 4) {
            val byte = b[at + i].toInt() and 0xFF
            v = if (le) v or (byte shl (8 * i)) else v or (byte shl (8 * (3 - i)))
        }
        return v
    }

    private fun writeShort(b: ByteArray, at: Int, v: Int, le: Boolean) {
        val first = if (le) v and 0xFF else (v shr 8) and 0xFF
        val second = if (le) (v shr 8) and 0xFF else v and 0xFF
        b[at] = first.toByte()
        b[at + 1] = second.toByte()
    }

    private fun writeInt(b: ByteArray, at: Int, v: Int, le: Boolean) {
        for (i in 0 until 4) {
            val byte = if (le) (v shr (8 * i)) and 0xFF else (v shr (8 * (3 - i))) and 0xFF
            b[at + i] = byte.toByte()
        }
    }
}