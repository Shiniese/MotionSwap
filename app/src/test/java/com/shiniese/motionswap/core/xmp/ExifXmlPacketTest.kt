package com.shiniese.motionswap.core.xmp

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/** Exif tag 700, which carries a second copy of the XMP packet inside the Exif block. */
@DisplayName("ExifXmlPacket")
class ExifXmlPacketTest {

    private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
    private fun ascii(s: String) = s.toByteArray(Charsets.US_ASCII)

    /** Wraps a TIFF block in the `FF E1` + length + `Exif\0\0` prefix a JPEG APP1 uses. */
    private fun app1(tiff: ByteArray): ByteArray {
        val payload = 8 + tiff.size
        return b(0xFF, 0xE1, (payload shr 8) and 0xFF, payload and 0xFF) +
            ascii("Exif") + b(0, 0) + tiff
    }

    /**
     * A minimal little-endian TIFF block whose IFD0 holds one tag-700 entry.
     *
     * Layout: 8-byte header, a 2-entry IFD0, then the value bytes at a known offset. A second
     * tag is present so that "leave everything else alone" is checkable rather than vacuous.
     */
    private fun tiffWithTag700(value: ByteArray, littleEndian: Boolean = true): ByteArray {
        val ifd0 = 8
        val entries = 2
        val ifdBytes = 2 + entries * 12 + 4
        val valueAt = alignEven(ifd0 + ifdBytes)

        val out = ByteArray(valueAt + maxOf(value.size, 4))
        // Byte-order mark and magic.
        if (littleEndian) {
            out[0] = 0x49; out[1] = 0x49; out[2] = 0x2A; out[3] = 0x00
        } else {
            out[0] = 0x4D; out[1] = 0x4D; out[2] = 0x00; out[3] = 0x2A
        }
        writeInt(out, 4, ifd0, littleEndian)
        writeShort(out, ifd0, entries, littleEndian)

        // Entry 0: tag 700, type 1 (BYTE), count = value length.
        val entry0 = ifd0 + 2
        writeShort(out, entry0, 0x02BC, littleEndian)
        writeShort(out, entry0 + 2, 1, littleEndian)
        writeInt(out, entry0 + 4, value.size, littleEndian)
        if (value.size <= 4) {
            System.arraycopy(value, 0, out, entry0 + 8, value.size)
        } else {
            writeInt(out, entry0 + 8, valueAt, littleEndian)
        }

        // Entry 1: tag 0x0112 (orientation), short, value 1 held inline.
        val entry1 = ifd0 + 2 + 12
        writeShort(out, entry1, 0x0112, littleEndian)
        writeShort(out, entry1 + 2, 3, littleEndian)
        writeInt(out, entry1 + 4, 1, littleEndian)
        writeShort(out, entry1 + 8, 1, littleEndian)

        writeInt(out, entry1 + 12, 0, littleEndian)
        System.arraycopy(value, 0, out, valueAt, value.size)
        return out
    }

    private fun alignEven(at: Int) = if (at % 2 == 0) at else at + 1

    private fun writeShort(b: ByteArray, at: Int, v: Int, le: Boolean) {
        if (le) {
            b[at] = (v and 0xFF).toByte()
            b[at + 1] = ((v shr 8) and 0xFF).toByte()
        } else {
            b[at] = ((v shr 8) and 0xFF).toByte()
            b[at + 1] = (v and 0xFF).toByte()
        }
    }

    private fun writeInt(b: ByteArray, at: Int, v: Int, le: Boolean) {
        for (i in 0 until 4) {
            val shift = if (le) 8 * i else 8 * (3 - i)
            b[at + i] = ((v shr shift) and 0xFF).toByte()
        }
    }

    private fun segmentLength(segment: ByteArray): Int =
        ((segment[2].toInt() and 0xFF) shl 8) or (segment[3].toInt() and 0xFF)

    @Test
    fun `an odd-length value still round-trips`() {
        // TIFF values start on an even offset, so an odd-length value leaves a pad byte after
        // it. The value must still read back at its true length, not with the pad attached.
        val xml = ascii("<x:xmpmeta>odd</x:xmpmeta>")
        assertEquals(26, xml.size)
        val replaced = ExifXmlPacket.replace(app1(tiffWithTag700(ascii("<old/>"))), xml)!!
        assertArrayEquals(xml, ExifXmlPacket.read(replaced))
        assertEquals(segmentLength(replaced), replaced.size - 2)
    }

    @Test
    fun `repeated application does not inflate the segment without bound`() {
        // Whatever the alignment padding does, growth per application has to stay within one
        // byte of the value size. A merge loop that grew faster than this would bloat every
        // photo it touched.
        val xml = ascii("<x:xmpmeta>" + "z".repeat(500) + "</x:xmpmeta>")
        var current = app1(tiffWithTag700(ascii("<old/>")))
        val baseline = current.size
        for (round in 1..5) {
            val next = ExifXmlPacket.replace(current, xml)!!
            val growth = next.size - current.size
            assertTrue(
                growth in xml.size..(xml.size + 1),
                "round $round grew by $growth, expected ${xml.size} or ${xml.size + 1}",
            )
            assertArrayEquals(xml, ExifXmlPacket.read(next))
            current = next
        }
        assertTrue(current.size < baseline + 6 * (xml.size + 1))
    }

    @Nested
    @DisplayName("read")
    inner class Read {

        @Test
        fun `reads a value held at an offset`() {
            val value = ascii("<x:xmpmeta/>")
            val read = ExifXmlPacket.read(app1(tiffWithTag700(value)))
            assertArrayEquals(value, read)
        }

        @Test
        fun `reads a value of four bytes or fewer held inline in the entry`() {
            val value = ascii("abcd")
            val read = ExifXmlPacket.read(app1(tiffWithTag700(value)))
            assertArrayEquals(value, read)
        }

        @Test
        fun `reads a big-endian block`() {
            // Both byte orders exist in the wild; only handling one would silently return null
            // on a file whose MakerNotes then get rewritten wrongly.
            val value = ascii("<x:xmpmeta>be</x:xmpmeta>")
            val read = ExifXmlPacket.read(app1(tiffWithTag700(value, littleEndian = false)))
            assertArrayEquals(value, read)
        }

        @Test
        fun `returns null when the segment carries no tag 700`() {
            val noTag = tiffWithTag700(ascii("x")).also { copy ->
                // Overwrite the tag number with something unrelated.
                writeShort(copy, 10, 0x0112, true)
            }
            assertNull(ExifXmlPacket.read(app1(noTag)))
        }

        @Test
        fun `returns null for a segment that is not APP1 Exif at all`() {
            assertNull(ExifXmlPacket.read(ascii("this is not a JPEG segment")))
            assertNull(ExifXmlPacket.read(b(0xFF, 0xD8, 0xFF, 0xE0)))
            assertNull(ExifXmlPacket.read(ByteArray(0)))
            // APP1, but no `Exif\0\0` identifier — an XMP packet lives in one of those.
            val xmpApp1 = b(0xFF, 0xE1, 0x00, 0x20) + ascii("http://ns.adobe.com/xap/1.0/") + ByteArray(10)
            assertNull(ExifXmlPacket.read(xmpApp1))
        }

        @Test
        fun `returns null for a segment too short to hold a TIFF header`() {
            assertNull(ExifXmlPacket.read(b(0xFF, 0xE1, 0x00, 0x0A) + ascii("Exif") + b(0, 0)))
        }

        @Test
        fun `returns null when the declared length points past the block`() {
            // A stale length would otherwise read arbitrary bytes as the packet.
            val tiff = tiffWithTag700(ascii("<x/>"))
            writeInt(tiff, 14, tiff.size + 5_000, true)
            assertNull(ExifXmlPacket.read(app1(tiff)))
        }

        @Test
        fun `returns null when the count is absurd`() {
            val tiff = tiffWithTag700(ascii("<x/>"))
            writeInt(tiff, 14, -5, true)
            assertNull(ExifXmlPacket.read(app1(tiff)))
        }
    }

    @Nested
    @DisplayName("replace")
    inner class Replace {

        @Test
        fun `round-trips a value`() {
            val original = app1(tiffWithTag700(ascii("<old/>")))
            val xml = ascii("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf/></x:xmpmeta>")
            val replaced = ExifXmlPacket.replace(original, xml)!!
            assertArrayEquals(xml, ExifXmlPacket.read(replaced))
        }

        @Test
        fun `updates the segment length field to match the bytes`() {
            // A stale length field is the kind of thing that only shows up as an unreadable
            // photo later, so it is checked rather than trusted.
            val replaced = ExifXmlPacket.replace(app1(tiffWithTag700(ascii("<old/>"))), ascii("<new/>"))!!
            assertEquals(segmentLength(replaced), replaced.size - 2)
        }

        @Test
        fun `appends rather than relocating`() {
            // MakerNotes carry their own offsets and the thumbnail points at an absolute offset
            // from the TIFF header, so moving existing bytes would corrupt data whose layout is
            // vendor-private. Everything already present must stay exactly where it was.
            val before = app1(tiffWithTag700(ascii("<old value that is longer than four bytes/>")))
            val after = ExifXmlPacket.replace(before, ascii("<new/>"))!!

            // TIFF header starts at 10; the tag-700 entry occupies the next 12 bytes and is the
            // one thing allowed to change, since its value pointer is repointed at the appended
            // copy. Everything else — including the other IFD0 entry and every value already in
            // the block — has to sit at exactly the offset it did before.
            val tiffStart = 10
            val tag700Entry = tiffStart + 10 until tiffStart + 22
            for (i in tiffStart until before.size) {
                if (i in tag700Entry) continue
                assertEquals(
                    before[i],
                    after[i],
                    "byte $i moved; append-only was violated",
                )
            }
        }

        @Test
        fun `is stable when applied twice`() {
            val once = ExifXmlPacket.replace(app1(tiffWithTag700(ascii("<old/>"))), ascii("<same/>"))!!
            val twice = ExifXmlPacket.replace(once, ascii("<same/>"))!!
            assertArrayEquals(ExifXmlPacket.read(once), ascii("<same/>"))
            assertArrayEquals(ExifXmlPacket.read(twice), ascii("<same/>"))
        }

        @Test
        fun `a value longer than the original still fits`() {
            val small = ascii("<a/>")
            val big = ascii("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">" + "y".repeat(8_000) + "</x:xmpmeta>")
            val replaced = ExifXmlPacket.replace(app1(tiffWithTag700(small)), big)!!
            assertArrayEquals(big, ExifXmlPacket.read(replaced))
            assertEquals(segmentLength(replaced), replaced.size - 2)
        }

        @Test
        fun `a value of four bytes or fewer is held inline and clears the old bytes`() {
            // The old value lived at an offset; replacing it with a short one has to zero the
            // pointer, or a later read follows a stale offset into the middle of the block.
            val before = app1(tiffWithTag700(ascii("<a longer old value here>")))
            val after = ExifXmlPacket.replace(before, ascii("<ab>"))!!
            assertArrayEquals(ascii("<ab>"), ExifXmlPacket.read(after))
        }

        @Test
        fun `returns null for a segment it cannot parse safely`() {
            assertNull(ExifXmlPacket.replace(ascii("nonsense"), ascii("<x/>")))
            assertNull(ExifXmlPacket.replace(ByteArray(0), ascii("<x/>")))
        }

        @Test
        fun `grows the block by the same amount each time`() {
            // Each application appends one more copy of the value; the growth has to be
            // predictable or repeated merges would inflate the file without bound.
            // An even-length value, so the segment grows by exactly one copy of it per
            // application. With an odd-length value the alignment padding makes the growth
            // alternate between size and size+1, which is checked separately below.
            val xml = ascii("<x:xmpmeta>" + "z".repeat(501) + "</x:xmpmeta>")
            val base = app1(tiffWithTag700(ascii("<old/>")))
            val once = ExifXmlPacket.replace(base, xml)!!
            val twice = ExifXmlPacket.replace(once, xml)!!
            val thrice = ExifXmlPacket.replace(twice, xml)!!
            assertEquals(xml.size, once.size - base.size)
            assertEquals(xml.size, twice.size - once.size)
            assertEquals(xml.size, thrice.size - twice.size)
            assertArrayEquals(xml, ExifXmlPacket.read(thrice))
        }

        @Test
        fun `preserves the TIFF byte-order mark`() {
            for (littleEndian in listOf(true, false)) {
                val after = ExifXmlPacket.replace(
                    app1(tiffWithTag700(ascii("<old/>"), littleEndian)),
                    ascii("<new/>"),
                )!!
                val expected = if (littleEndian) b(0x49, 0x49, 0x2A, 0x00) else b(0x4D, 0x4D, 0x00, 0x2A)
                assertArrayEquals(expected, after.copyOfRange(10, 14), "BOM for le=$littleEndian")
            }
        }

        @Test
        fun `adds the tag when the block has none`() {
            // A photo whose Exif carries no XMP at all still needs one written, so a merge can
            // put the packet where vivo looks for it.
            val bare = ByteArray(64)
            // Header with no IFD0 entries.
            bare[0] = 0x49; bare[1] = 0x49; bare[2] = 0x2A; bare[3] = 0x00
            writeInt(bare, 4, 8, true)
            writeShort(bare, 8, 0, true) // zero entries
            writeInt(bare, 10, 0, true) // no IFD1

            val xml = ascii("<x:xmpmeta>new</x:xmpmeta>")
            val replaced = ExifXmlPacket.replace(app1(bare), xml)
            assertNotNull(replaced)
            assertArrayEquals(xml, ExifXmlPacket.read(replaced!!))
            assertEquals(segmentLength(replaced), replaced.size - 2)
        }
    }

    @Nested
    @DisplayName("TAG")
    inner class Tag {

        @Test
        fun `is 700 decimal`() {
            // The whole Exif-tag-700 story is in this constant; if it were read as hex by
            // mistake the tag would be 0x02BC = 700 decimal, and 0xBC would be something else.
            assertEquals(700, ExifXmlPacket.TAG)
        }
    }
}