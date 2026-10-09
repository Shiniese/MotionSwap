package com.shiniese.motionswap.core.motion

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import com.shiniese.motionswap.core.model.MotionPhotoLayout

/**
 * Finding the MP4 by its `ftyp` box.
 *
 * The box is the only signal available for a file with no usable XMP, and the size and brand
 * checks are what stop a coincidental `ftyp` inside compressed image data from being mistaken
 * for a video.
 */
@DisplayName("Mp4BoxScanner")
class Mp4BoxScannerTest {

    private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
    private fun ascii(s: String) = s.toByteArray(Charsets.US_ASCII)

    /**
     * A camera-sized `ftyp` box: a 4-byte big-endian size, the `ftyp` type, then brands.
     *
     * `mp4` is written into the brand area unless [withMp4] is false, because the scanner only
     * accepts a box whose brands contain `mp4` — a QuickTime box has to be rejected.
     */
    private fun ftypBox(
        size: Int = 32,
        declaredSize: Int = size,
        majorBrand: String = "mp42",
        withMp4: Boolean = true,
    ): ByteArray {
        val out = ByteArray(size)
        out[0] = ((declaredSize shr 24) and 0xFF).toByte()
        out[1] = ((declaredSize shr 16) and 0xFF).toByte()
        out[2] = ((declaredSize shr 8) and 0xFF).toByte()
        out[3] = (declaredSize and 0xFF).toByte()
        System.arraycopy(ascii("ftyp"), 0, out, 4, 4)
        System.arraycopy(majorBrand.toByteArray(Charsets.US_ASCII), 0, out, 8, minOf(4, majorBrand.length))
        if (withMp4) System.arraycopy(ascii("mp4"), 0, out, 12, 3)
        for (i in 16 until size) out[i] = 0x11
        return out
    }

    @Nested
    @DisplayName("findVideoRegion")
    inner class FindVideoRegion {

        @Test
        fun `finds a box at a known offset`() {
            val region = Mp4BoxScanner.findVideoRegion(ftypBox(), windowOffset = 0, totalSize = 5_000)
            assertNotNull(region)
            assertEquals(0L, region!!.offset)
            assertEquals(5_000L, region.length)
        }

        @Test
        fun `maps a window offset into an absolute file offset`() {
            // This is what makes scanning the whole file in windows work at all: the result has
            // to be an absolute position, not an index into the window that was handed over.
            val region = Mp4BoxScanner.findVideoRegion(ftypBox(), windowOffset = 7_843_538, totalSize = 17_675_998)
            assertEquals(7_843_538L, region!!.offset)
            assertEquals(17_675_998L - 7_843_538, region.length)
        }

        @Test
        fun `finds a box that follows noise`() {
            // Four filler bytes, then the box: the returned offset is the *box* start, four
            // bytes before the `ftyp` signature, so it has to land after the filler.
            val window = b(0, 0, 0, 0) + ftypBox() + ByteArray(500) { 0x7A }
            val region = Mp4BoxScanner.findVideoRegion(window, windowOffset = 100, totalSize = 10_000)
            assertEquals(104L, region!!.offset)
        }

        @Test
        fun `takes the first credible box, not the first ftyp bytes`() {
            // Entropy-coded image data contains `ftyp` sequences by chance; the size and brand
            // checks are what reject them, so the real box further in must win.
            val noise = ascii("ftyp")
            val window = noise + ByteArray(40) + ftypBox()
            val region = Mp4BoxScanner.findVideoRegion(window, windowOffset = 0, totalSize = 10_000)
            assertEquals(44L, region!!.offset)
        }

        @Test
        fun `a box at the very end of the window is found`() {
            val window = ByteArray(100) + ftypBox()
            val region = Mp4BoxScanner.findVideoRegion(window, windowOffset = 0, totalSize = 132)
            assertEquals(100L, region!!.offset)
        }

        @Test
        fun `returns null when there is no ftyp at all`() {
            assertNull(Mp4BoxScanner.findVideoRegion(ByteArray(2_000) { 0x33 }, 0, 2_000))
            assertNull(Mp4BoxScanner.findVideoRegion(ByteArray(0), 0, 0))
        }

        @Test
        fun `returns null for an implausibly small box`() {
            // A camera `ftyp` is never 8 bytes; that size means the four bytes before it were
            // not a length field at all.
            val window = b(0, 0, 0, 8) + ascii("ftyp") + ascii("mp4") + ByteArray(20)
            assertNull(Mp4BoxScanner.findVideoRegion(window, 0, 100))
        }

        @Test
        fun `returns null for an implausibly large box`() {
            // The size field claims far more than the box could be; four random bytes before an
            // `ftyp` are read as a length, and this is what that field looks like by accident.
            assertNull(Mp4BoxScanner.findVideoRegion(ftypBox(declaredSize = 0x00FF_FFFF), 0, 10_000))
        }

        @Test
        fun `returns null when the brands do not include mp4`() {
            // A QuickTime box has the same type marker, so the brand list is the only thing
            // telling the two apart.
            assertNull(Mp4BoxScanner.findVideoRegion(ftypBox(majorBrand = "qt  ", withMp4 = false), 0, 10_000))
        }

        @Test
        fun `the region runs to the end of the file`() {
            // The video is at the tail, so length is whatever is left rather than the box size.
            val region = Mp4BoxScanner.findVideoRegion(ftypBox(), windowOffset = 1_000, totalSize = 3_000)
            assertEquals(2_000L, region!!.length)
        }

        @Test
        fun `a box at the very end of the file yields nothing`() {
            // Offset equals totalSize, so the region would be empty — a video of zero length is
            // not a video, and reporting it would produce a merge that writes nothing.
            assertNull(Mp4BoxScanner.findVideoRegion(ftypBox(), windowOffset = 5_000, totalSize = 5_000))
        }

        @Test
        fun `a big-endian length over Int range does not overflow`() {
            // 0xFFFFFFFF would be -1 as a signed Int; reading it as a Long keeps the comparison
            // against MAX_BOX_SIZE meaningful instead of silently passing.
            assertNull(Mp4BoxScanner.findVideoRegion(ftypBox(declaredSize = -1), 0, 10_000))
        }
    }

    @Nested
    @DisplayName("isVideoStartAt")
    inner class IsVideoStartAt {

        @Test
        fun `confirms a box at the declared offset`() {
            // Read from the start of the file: the box sits at index 200, so 200 is the
            // absolute offset it should be confirmed at.
            val fromStart = ByteArray(200) + ftypBox()
            assertTrue(Mp4BoxScanner.isVideoStartAt(fromStart, windowOffset = 0, absoluteOffset = 200))

            // Read from a window that begins at 200: the same box is now index 0 of the window
            // and still sits at absolute 200, which is what the windowOffset parameter is for.
            val windowAt200 = ftypBox()
            assertTrue(Mp4BoxScanner.isVideoStartAt(windowAt200, windowOffset = 200, absoluteOffset = 200))
        }

        @Test
        fun `rejects an offset that does not land on a box`() {
            // This is the check that rejects a still extracted out of a motion photo, which
            // keeps advertising the original video offset while now being pure JPEG. The
            // declared offset lands in the middle of the still, not on the box at 200.
            val window = ByteArray(200) { 0x41 } + ftypBox()
            assertTrue(!Mp4BoxScanner.isVideoStartAt(window, windowOffset = 0, absoluteOffset = 50))
            assertTrue(Mp4BoxScanner.isVideoStartAt(window, windowOffset = 0, absoluteOffset = 200))
        }

        @Test
        fun `rejects an offset outside the window`() {
            val window = ftypBox()
            assertTrue(!Mp4BoxScanner.isVideoStartAt(window, windowOffset = 0, absoluteOffset = 5_000))
            assertTrue(!Mp4BoxScanner.isVideoStartAt(window, windowOffset = 10, absoluteOffset = 5))
        }
    }

    @Nested
    @DisplayName("MotionPhotoLayout")
    inner class Layout {

        @Test
        fun `is valid when the two halves cover the file exactly`() {
            assertTrue(MotionPhotoLayout.of(videoOffset = 100, videoLength = 900).isValid())
        }

        @Test
        fun `is invalid when a length is zero or negative`() {
            assertTrue(!MotionPhotoLayout(0, 100, 100).isValid())
            assertTrue(!MotionPhotoLayout(100, 0, 100).isValid())
            assertTrue(!MotionPhotoLayout(100, -1, 99).isValid())
        }

        @Test
        fun `imageLength is the video offset`() {
            assertEquals(100L, MotionPhotoLayout.of(100, 900).imageLength)
        }

        @Test
        fun `of derives the total from the two halves`() {
            val layout = MotionPhotoLayout.of(100, 900)
            assertEquals(1_000L, layout.totalSize)
        }
    }
}