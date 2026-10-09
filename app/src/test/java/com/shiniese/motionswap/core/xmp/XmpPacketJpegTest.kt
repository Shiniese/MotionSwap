package com.shiniese.motionswap.core.xmp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Locating the XMP packet the way a reader does, as opposed to by searching raw bytes.
 *
 * The distinction is not stylistic. Cameras keep a second copy of the packet in Exif tag 700,
 * and rewriting that copy is append-only, so a file this app has just merged still holds the
 * *superseded* packet as dead space in its Exif block — and it sits ahead of the live APP1 copy
 * in memory, because `Exif\0\0` sorts before `http://…`. A raw scan finds the dead copy.
 *
 * These cases build that situation rather than hoping a sample contains it.
 */
@DisplayName("XmpPacket.locateInJpeg")
class XmpPacketJpegTest {

    private fun ascii(s: String) = s.toByteArray(Charsets.US_ASCII)
    private val xmpId = ascii("http://ns.adobe.com/xap/1.0/") + byteArrayOf(0)
    private val exifId = ascii("Exif") + byteArrayOf(0, 0)

    /** A well-formed packet, distinguishable by [tag] so a test can tell copies apart. */
    private fun packet(tag: String) = """
        <x:xmpmeta xmlns:x="adobe:ns:meta/">
          <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
            <rdf:Description xmlns:GCamera="http://ns.google.com/photos/1.0/camera/"
              GCamera:MicroVideoOffset="$tag"/>
          </rdf:RDF>
        </x:xmpmeta>
    """.trimIndent().toByteArray()

    private fun app1(identifier: ByteArray, payload: ByteArray): ByteArray {
        val length = identifier.size + payload.size + 2
        val out = ByteArray(4 + identifier.size + payload.size)
        out[0] = 0xFF.toByte(); out[1] = 0xE1.toByte()
        out[2] = (length shr 8).toByte(); out[3] = (length and 0xFF).toByte()
        System.arraycopy(identifier, 0, out, 4, identifier.size)
        System.arraycopy(payload, 0, out, 4 + identifier.size, payload.size)
        return out
    }

    private fun segment(marker: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        val length = payload.size + 2
        val out = ByteArray(4 + payload.size)
        out[0] = 0xFF.toByte(); out[1] = marker.toByte()
        out[2] = (length shr 8).toByte(); out[3] = (length and 0xFF).toByte()
        System.arraycopy(payload, 0, out, 4, payload.size)
        return out
    }

    private fun sos() = segment(0xDA, ByteArray(8) { 0x55 })

    /** TEM and the restart markers: the `FF xx` prefix and nothing else. */
    private fun standalone(marker: Int) = byteArrayOf(0xFF.toByte(), marker.toByte())

    private fun jpeg(vararg parts: ByteArray): ByteArray =
        byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + parts.reduce { a, b -> a + b }

    /** What [XmpPacket.locateInJpeg] points at, as the `GCamera:MicroVideoOffset` value. */
    private fun offsetOf(data: ByteArray): String? {
        val range = XmpPacket.locateInJpeg(data) ?: return null
        return Regex("MicroVideoOffset=\"(\\d+)\"").find(String(data, range.start,
            range.endExclusive - range.start, Charsets.UTF_8))?.groupValues?.get(1)
    }

    @Nested
    @DisplayName("the bug it prevents")
    inner class StaleExifCopy {

        /**
         * An Exif APP1 that *contains* a copy of the packet inside its TIFF value area, plus the
         * live APP1 after it. Byte-for-byte the shape `ExifXmlPacket.replace` leaves behind.
         */
        private fun exifCarrying(inner: ByteArray): ByteArray {
            // A TIFF block big enough to hold the packet, with the payload landing at the end.
            val tiff = ByteArray(64 + inner.size)
            System.arraycopy(byteArrayOf(0x49, 0x49, 0x2A, 0x00), 0, tiff, 0, 4)  // LE header
            tiff[4] = 0x08                                            // IFD0 at 8
            tiff[8] = 0x01                                            // one entry
            tiff[10] = 0xBC.toByte(); tiff[11] = 0x02                  // tag 700
            tiff[12] = 0x01; tiff[13] = 0x00                          // type BYTE
            tiff[14] = (inner.size and 0xFF).toByte()                  // count
            tiff[15] = (inner.size shr 8).toByte()
            tiff[16] = (64 and 0xFF).toByte(); tiff[17] = 0x00        // value at 64
            tiff[18] = 0x00; tiff[19] = 0x00
            System.arraycopy(inner, 0, tiff, 64, inner.size)
            val body = byteArrayOf(0x4D, 0x4D, 0x00, 0x2A) + tiff   // big-endian TIFF block
            return app1(exifId, body)
        }

        @Test
        fun `the live APP1 wins over a stale copy inside the Exif block`() {
            val data = jpeg(
                exifCarrying(packet("111")),
                app1(xmpId, packet("222")),
                sos(),
            )
            assertEquals("222", offsetOf(data))
        }

        @Test
        fun `a raw byte scan really does pick the wrong one`() {
            // Without this, the test above would pass for the wrong reason — e.g. if the Exif
            // copy had stopped being findable and the fix were really just "no packet at all".
            val data = jpeg(
                exifCarrying(packet("111")),
                app1(xmpId, packet("222")),
                sos(),
            )
            val range = XmpPacket.locate(data)!!
            assertEquals(
                "111",
                Regex("MicroVideoOffset=\"(\\d+)\"")
                    .find(String(data, range.start, range.endExclusive - range.start))?.groupValues?.get(1),
            )
            assertNotEquals(range, XmpPacket.locateInJpeg(data))
        }

        @Test
        fun `order does not decide it`() {
            // Both orders, same answer: what matters is that it read a segment, not the first
            // packet-shaped byte run in memory.
            val exifFirst = jpeg(exifCarrying(packet("111")), app1(xmpId, packet("222")), sos())
            val app1First = jpeg(app1(xmpId, packet("222")), exifCarrying(packet("111")), sos())
            assertEquals("222", offsetOf(exifFirst))
            assertEquals("222", offsetOf(app1First))
        }
    }

    @Nested
    @DisplayName("segment walking")
    inner class Walking {

        @Test
        fun `a plain JPEG with one packet`() {
            assertEquals("7", offsetOf(jpeg(app1(xmpId, packet("7")), sos())))
        }

        @Test
        fun `an extended XMP APP1 is skipped, not concatenated`() {
            // `samples/Pixel.jpg` carries three APP1s: the real packet and two extended ones.
            // The extended document is separate, so gluing it on would produce unparseable XML.
            val data = jpeg(
                app1(ascii("http://ns.adobe.com/xmp/extension/") + byteArrayOf(0), packet("111")),
                app1(xmpId, packet("222")),
                sos(),
            )
            val range = XmpPacket.locateInJpeg(data)!!
            val text = String(data, range.start, range.endExclusive - range.start, Charsets.UTF_8)
            assertEquals("222", offsetOf(data))
            assertTrue(text.endsWith("</x:xmpmeta>"), "extended packet leaked into the result")
        }

        @Test
        fun `an extended XMP APP1 alone is not a motion packet`() {
            val data = jpeg(
                app1(ascii("http://ns.adobe.com/xmp/extension/") + byteArrayOf(0), packet("111")),
                sos(),
            )
            assertNull(XmpPacket.locateInJpeg(data))
        }

        @Test
        fun `restart markers between the segments`() {
            // RSTn carries no length field, so a walker that treated them as length-prefixed
            // would desynchronise and miss the packet entirely.
            val data = jpeg(
                standalone(0x01),
                app1(xmpId, packet("9")),
                standalone(0xD0), standalone(0xD1),
                sos(),
            )
            assertEquals("9", offsetOf(data))
        }

        @Test
        fun `an APP0 JFIF segment ahead of the XMP`() {
            assertEquals("5", offsetOf(jpeg(
                app1(ascii("JFIF") + byteArrayOf(0), ByteArray(14)),
                app1(xmpId, packet("5")),
                sos(),
            )))
        }

        @Test
        fun `a segment whose length runs past the end of the data`() {
            // A head window can cut a large segment in half; that must not throw.
            val truncated = jpeg(app1(xmpId, packet("12345")))
            assertNull(XmpPacket.locateInJpeg(truncated.copyOfRange(0, 8)))
        }

        @Test
        fun `not a JPEG at all`() {
            assertNull(XmpPacket.locateInJpeg(ByteArray(0)))
            assertNull(XmpPacket.locateInJpeg(byteArrayOf(0xFF.toByte(), 0xD8.toByte())))
            assertNull(XmpPacket.locateInJpeg(ascii("ftypmp42 not a jpeg")))
        }

        @Test
        fun `a JPEG with no XMP packet`() {
            assertNull(XmpPacket.locateInJpeg(jpeg(segment(0xDB), sos())))
        }

        @Test
        fun `a packet in the entropy-coded data is not found`() {
            // Past SOS is image data, not metadata. Bounding the walk there is what keeps a
            // coincidental `x:xmpmeta` inside a photo from being read as its own metadata.
            val data = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + sos() +
                packet("999") + byteArrayOf(0xFF.toByte(), 0xD9.toByte())
            assertNull(XmpPacket.locateInJpeg(data))
        }

        @Test
        fun `a head-only prefix is enough`() {
            // Detection reads a window, not the whole file, so the walker must not need the end.
            val data = jpeg(app1(xmpId, packet("3")), sos())
            assertEquals("3", offsetOf(data.copyOfRange(0, data.size - 4)))
        }

        @Test
        fun `the returned range indexes the original array`() {
            // An offset that had to be re-based would land on the wrong bytes, so the slice is
            // checked against the actual packet rather than against an internal range value.
            val data = jpeg(segment(0xDB, ByteArray(32)), app1(xmpId, packet("42")), sos())
            val range = XmpPacket.locateInJpeg(data)!!
            val text = String(data, range.start, range.endExclusive - range.start, Charsets.UTF_8)
            assertTrue(text.startsWith("<x:xmpmeta"), "range misaligned: ${text.take(24)}")
            assertEquals("42", Regex("MicroVideoOffset=\"(\\d+)\"").find(text)?.groupValues?.get(1))
        }

        @Test
        fun `a packet wrapped in an xpacket header is still found`() {
            val wrapped = ("<?xpacket begin=\"\uFEFF\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>" +
                String(packet("8"), Charsets.UTF_8) +
                "<?xpacket end=\"w\"?>").toByteArray()
            assertEquals("8", offsetOf(jpeg(app1(xmpId, wrapped), sos())))
        }
    }
}