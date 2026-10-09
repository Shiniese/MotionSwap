package com.shiniese.motionswap.core.motion

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import com.shiniese.motionswap.Jpeg
import com.shiniese.motionswap.Samples
import com.shiniese.motionswap.core.model.MotionOrigin
import com.shiniese.motionswap.core.model.MotionProbe
import com.shiniese.motionswap.findVideoStart
import java.io.File
import java.io.RandomAccessFile

/** Image extensions among the samples; the exiftool dumps and clips are not motion photos. */
private val IMAGE_NAME = Regex(""".*\.(jpg|jpeg|JPG|Jpeg)""")

/**
 * Detection against real camera output.
 *
 * The expectations here are not the code's own answers written down after the fact. Each offset
 * and length is independently derivable from the file — an `ftyp` box four bytes past its size
 * field, a length quoted in the exiftool dumps sitting next to each photo (`PixelXMP`,
 * `XiaomiOldXMP`, …), or the size of the matching `*_extracted_video.mp4` on disk — so agreement
 * means something. `HUAWEI.jpeg` is the case that matters most: it carries no XMP at all and its
 * box sits 7.8 MB in, which no fixed-size head or tail probe can reach.
 */
@DisplayName("MotionProbeReader on real samples")
class MotionProbeReaderSampleTest {

    /** A [ByteSampler] over a file, so detection never loads a whole motion photo into memory. */
    private fun samplerFor(file: File): ByteSampler {
        val size = file.length()
        val raf = RandomAccessFile(file, "r")
        return ByteSampler { offset, length ->
            if (offset < 0 || length < 0 || offset + length > size) {
                null
            } else {
                val buffer = ByteArray(length)
                raf.seek(offset)
                raf.readFully(buffer)
                buffer
            }
        }
    }

    private fun probe(name: String, make: String? = null): MotionProbe {
        val file = sampleFileOf(name)
        return MotionProbeReader.probe(samplerFor(file), file.length(), make = make)
    }

    private fun sampleFileOf(name: String): File {
        val file = Samples.file(name)
        org.junit.jupiter.api.Assumptions.assumeTrue(file != null, "sample not available: samples/$name")
        return file!!
    }

    private fun detected(probe: MotionProbe): MotionProbe.Detected {
        assertTrue(probe is MotionProbe.Detected, "expected a detection, got $probe")
        return probe as MotionProbe.Detected
    }

    @Nested
    @DisplayName("vendor identification")
    inner class Identification {

        @ParameterizedTest(name = "{0} is a {1} motion photo")
        @CsvSource(
            "Pixel.jpg, GOOGLE",
            "Samsung.jpg, GOOGLE",
            "VivoNew.jpg, VIVO",
            "XiaomiOld.jpg, XIAOMI",
            "XiaomiNew.jpg, XIAOMI",
            "WechatModified.JPG, XIAOMI",
            "HUAWEI.jpeg, MP4_SIGNATURE",
        )
        fun `each vendor is recognised`(name: String, expected: String) {
            assertEquals(MotionOrigin.valueOf(expected), detected(probe(name)).origin)
        }

        @Test
        fun `a vivo photo is not reported as Google`() {
            // vivo's bytes read the same way as Google's, so only the VCamera markers separate
            // them — and picking the wrong one steers the user to a merge target whose output
            // vivo's gallery refuses.
            val file = sampleFileOf("VivoNew.jpg")
            val packet = Jpeg.app1Xmp(file.readBytes())!!
            assertTrue("VCamera:" in packet, "sample no longer carries vivo's own markers")
            assertEquals(MotionOrigin.VIVO, detected(probe("VivoNew.jpg")).origin)
        }

        @Test
        fun `huawei is identified by its signature, having no xmp at all`() {
            val file = sampleFileOf("HUAWEI.jpeg")
            assertNull(Jpeg.app1Xmp(file.readBytes()), "HUAWEI.jpeg is expected to carry no XMP packet")
            val found = detected(probe("HUAWEI.jpeg"))
            assertEquals(MotionOrigin.MP4_SIGNATURE, found.origin)
        }
    }

    @Nested
    @DisplayName("layout")
    inner class Layouts {

        @ParameterizedTest(name = "{0}: two halves cover the file exactly")
        @ValueSource(
            strings = [
                "Pixel.jpg", "Samsung.jpg", "VivoNew.jpg", "XiaomiNew.jpg",
                "XiaomiOld.jpg", "WechatModified.JPG", "HUAWEI.jpeg",
            ],
        )
        fun `the layout covers the whole file`(name: String) {
            val file = sampleFileOf(name)
            val layout = detected(probe(name)).layout
            assertTrue(layout.isValid(), "$name: $layout")
            assertEquals(file.length(), layout.totalSize, "$name total size")
            assertEquals(layout.totalSize, layout.videoOffset + layout.videoLength, "$name halves")
            assertEquals(layout.videoOffset, layout.imageLength, "$name image length")
        }

        @ParameterizedTest(name = "{0}: video offset matches an independent ftyp scan")
        @ValueSource(
            strings = [
                "Pixel.jpg", "Samsung.jpg", "VivoNew.jpg", "XiaomiNew.jpg",
                "XiaomiOld.jpg", "WechatModified.JPG", "HUAWEI.jpeg",
            ],
        )
        fun `the offset agrees with where the mp4 box really is`(name: String) {
            // Found here by walking raw bytes for `ftyp` from the end of the JPEG header, which
            // shares no code with the scanners under test.
            val file = sampleFileOf(name)
            val expected = findVideoStart(file.readBytes())!!.toLong()
            assertEquals(expected, detected(probe(name)).layout.videoOffset, "$name video offset")
        }

        @Test
        fun `the detected offset really does begin with a valid mp4 box`() {
            for (name in listOf("Pixel.jpg", "VivoNew.jpg", "XiaomiOld.jpg", "HUAWEI.jpeg")) {
                val file = sampleFileOf(name)
                val offset = detected(probe(name)).layout.videoOffset
                val raf = RandomAccessFile(file, "r")
                val head = ByteArray(32)
                raf.seek(offset)
                raf.readFully(head)
                raf.close()

                assertEquals("ftyp", String(head, 4, 4, Charsets.US_ASCII), "$name box type")
                val declared = ((head[0].toLong() and 0xFF) shl 24) or
                    ((head[1].toLong() and 0xFF) shl 16) or
                    ((head[2].toLong() and 0xFF) shl 8) or
                    (head[3].toLong() and 0xFF)
                assertTrue(declared in 16..1024, "$name box size $declared")
                assertTrue(
                    String(head, Charsets.ISO_8859_1).contains("mp4"),
                    "$name brands: ${String(head, Charsets.ISO_8859_1)}",
                )
            }
        }

        @Test
        fun `a length quoted in the xmp dump matches the detected layout`() {
            // `XiaomiNewXMP` and `XiaomiOldXMP` are exiftool dumps sitting next to the files.
            // Their Micro Video Offset is the byte length of the appended clip.
            assertEquals(1_943_922L, detected(probe("XiaomiNew.jpg")).layout.videoLength)
            assertEquals(5_466_684L, detected(probe("XiaomiOld.jpg")).layout.videoLength)
        }

        @Test
        fun `a length quoted in a google container matches the detected layout`() {
            // `PixelXMP` / `SamsungXMP` carry "Directory Item Length : 0, N"; N is the clip.
            assertEquals(1_619_843L, detected(probe("Pixel.jpg")).layout.videoLength)
            assertEquals(5_421_673L, detected(probe("Samsung.jpg")).layout.videoLength)
        }

        @Test
        fun `the still side matches the extracted image on disk, byte for byte in size`() {
            // The `*_extracted_image.jpg` files were produced from these by the app itself, so
            // their size is an independent record of where the video began.
            val pairs = listOf(
                "HUAWEI.jpeg" to "HUAWEI_extracted_image.jpg",
                "WechatModified.JPG" to "WechatModified_extracted_image.jpg",
            )
            for ((motionPhoto, still) in pairs) {
                val offset = detected(probe(motionPhoto)).layout.videoOffset
                assertEquals(sampleFileOf(still).length(), offset, "$motionPhoto -> $still")
            }
        }

        @Test
        fun `the video side matches the extracted clip on disk, byte for byte in size`() {
            val pairs = listOf(
                "HUAWEI.jpeg" to "HUAWEI_extracted_video.mp4",
                "WechatModified.JPG" to "WechatModified_extracted_video.mp4",
            )
            for ((motionPhoto, clip) in pairs) {
                val length = detected(probe(motionPhoto)).layout.videoLength
                assertEquals(sampleFileOf(clip).length(), length, "$motionPhoto -> $clip")
            }
        }

        @Test
        fun `samsung's padding does not shift the clip`() {
            // Samsung writes `Item:Padding="138,0"`, where 138 is a gap after the JPEG. Reading
            // that as an offset would put the end past EOF and reject the file outright.
            val file = sampleFileOf("Samsung.jpg")
            val packet = Jpeg.app1Xmp(file.readBytes())!!
            assertTrue("138" in packet, "sample no longer carries the padding value")
            val found = detected(probe("Samsung.jpg"))
            assertEquals(file.length() - 5_421_673L, found.layout.videoOffset)
        }
    }

    @Nested
    @DisplayName("presentation timestamp")
    inner class Timestamps {

        @Test
        fun `pixel's timestamp matches the exiftool dump`() {
            assertEquals(33_327L, detected(probe("Pixel.jpg")).presentationTimestampUs)
        }

        @Test
        fun `samsung's timestamp matches the exiftool dump`() {
            assertEquals(3_095_755L, detected(probe("Samsung.jpg")).presentationTimestampUs)
        }

        @Test
        fun `vivo reuses google's timestamp key`() {
            // The value is embedded unchanged, so Google's reader still reads it.
            val found = detected(probe("VivoNew.jpg"))
            assertNotNull(found.presentationTimestampUs, "vivo should surface its embedded timestamp")
        }

        @Test
        fun `xiaomi reports no timestamp`() {
            assertNull(detected(probe("XiaomiOld.jpg")).presentationTimestampUs)
        }
    }

    @Nested
    @DisplayName("rejections")
    inner class Rejections {

        @ParameterizedTest(name = "{0} is not a motion photo")
        @ValueSource(
            strings = [
                // A plain JPEG with trailing bytes but no clip in them.
                "Unknown_novideo_contained.jpg",
                "VivoOld_novideo_contained.jpg",
                // The still half of a motion photo: no video to find.
                "HUAWEI_extracted_image.jpg",
                "WechatModified_extracted_image.jpg",
            ],
        )
        fun `files without an embedded clip are not motion photos`(name: String) {
            assertEquals(MotionProbe.NotMotionPhoto, probe(name))
        }

        @Test
        fun `a bare clip is not a motion photo`() {
            // A standalone .mp4 has no JPEG head for the readers to key off.
            assertEquals(MotionProbe.NotMotionPhoto, probe("VivoOld.mp4"))
        }

        @Test
        fun `an extracted still is rejected even though its xmp still advertises a video`() {
            // This is the case `Mp4BoxScanner.isVideoStartAt` exists for. The still was split out
            // of a motion photo, so its packet still names the original clip's offset and length
            // — but there are no clip bytes there now, and trusting the metadata would carve
            // megabytes of image data out as "video".
            val file = sampleFileOf("WechatModified_extracted_image.jpg")
            val packet = Jpeg.app1Xmp(file.readBytes())!!
            assertTrue(
                "MicroVideoOffset" in packet,
                "the stale offset this test relies on is gone from the sample",
            )
            assertTrue(packet.contains("2724081"), packet.take(400))
            assertEquals(MotionProbe.NotMotionPhoto, probe("WechatModified_extracted_image.jpg"))
        }
    }

    @Nested
    @DisplayName("probing strategy")
    inner class Strategy {

        @Test
        fun `huawei's box is out of reach of a fixed head or tail window`() {
            // Documents why the scan has to walk the whole file: the offset here is beyond any
            // fixed probe size, and it is not near the end either.
            val file = sampleFileOf("HUAWEI.jpeg")
            val offset = detected(probe("HUAWEI.jpeg")).layout.videoOffset
            assertTrue(offset > 256 * 1024, "box unexpectedly close to the head: $offset")
            assertTrue(file.length() - offset > 256 * 1024, "box unexpectedly close to the tail: $offset")
        }

        @Test
        fun `a sampler that cannot read yields no detection`() {
            // Every window unavailable is what a missing file looks like; detection has to
            // report "not a motion photo" rather than throw.
            val probe = MotionProbeReader.probe({ _, _ -> null }, totalSize = 4_000_000)
            assertEquals(MotionProbe.NotMotionPhoto, probe)
        }

        @Test
        fun `an empty file is not a motion photo`() {
            assertEquals(
                MotionProbe.NotMotionPhoto,
                MotionProbeReader.probe({ _, _ -> null }, totalSize = 0),
            )
        }

        @Test
        fun `a sampler that over-runs is never trusted`() {
            // Head window larger than the file must be clamped, not padded with zeros that
            // could form a box header.
            var requested = 0
            val probe = MotionProbeReader.probe(
                { offset, length ->
                    requested = length
                    if (offset + length > 64) null else ByteArray(length) { 0x41 }
                },
                totalSize = 64,
            )
            assertEquals(MotionProbe.NotMotionPhoto, probe)
            assertTrue(requested <= 64, "asked for $requested bytes of a 64-byte file")
        }
    }

    @Nested
    @DisplayName("readers in isolation")
    inner class Readers {

        @Test
        fun `xiaomi reads its offset from the metadata`() {
            val layout = XiaomiReader.read(mapOf("GCamera:MicroVideoOffset" to "1000"), totalSize = 5_000)
            assertNotNull(layout)
            assertEquals(4_000L, layout!!.videoOffset)
            assertEquals(1_000L, layout.videoLength)
        }

        @Test
        fun `xiaomi rejects an offset at or past the end`() {
            assertNull(XiaomiReader.read(mapOf("GCamera:MicroVideoOffset" to "5000"), 5_000))
            assertNull(XiaomiReader.read(mapOf("GCamera:MicroVideoOffset" to "9999"), 5_000))
            assertNull(XiaomiReader.read(mapOf("GCamera:MicroVideoOffset" to "0"), 5_000))
        }

        @Test
        fun `google reads the second half of a length pair`() {
            // Primary is 0 and the clip is second; reading the first number would place the
            // video at the start of the file.
            val layout = GoogleReader.read(mapOf("GContainer:ItemLength" to "0, 1000"), 5_000)
            assertEquals(4_000L, layout!!.videoOffset)
            assertEquals(1_000L, layout.videoLength)
        }

        @Test
        fun `google rejects a single value that is not a pair`() {
            // One number cannot say which half is the video, and guessing is what produces a
            // file whose declared offsets point into the middle of the image.
            assertNull(GoogleReader.read(mapOf("GContainer:ItemLength" to "1000"), 5_000))
            assertNull(GoogleReader.read(mapOf("GContainer:ItemLength" to ""), 5_000))
        }

        @Test
        fun `google rejects a length that leaves no image`() {
            assertNull(GoogleReader.read(mapOf("GContainer:ItemLength" to "0, 5000"), 5_000))
            assertNull(GoogleReader.read(mapOf("GContainer:ItemLength" to "0, 0"), 5_000))
        }

        @Test
        fun `huawei is identified by make or manufacturer, case-insensitively`() {
            assertTrue(HuaweiReader.matchesMake("HUAWEI", null))
            assertTrue(HuaweiReader.matchesMake(null, "huawei"))
            assertTrue(HuaweiReader.matchesMake("Huawei", "whatever"))
            assertTrue(!HuaweiReader.matchesMake("Xiaomi", null))
            assertTrue(!HuaweiReader.matchesMake(null, null))
            // A make that merely contains the word is not a match.
            assertTrue(!HuaweiReader.matchesMake("NotHuawei", null))
        }

        @Test
        fun `huawei's reader contributes no layout of its own`() {
            assertNull(HuaweiReader.read(emptyMap(), 1_000))
        }
    }

    @Nested
    @DisplayName("the whole sample set")
    inner class Sweep {

        @Test
        fun `every sample is classified, and none of them throws`() {
            val names = Samples.available().filter { IMAGE_NAME.matches(it) }
            org.junit.jupiter.api.Assumptions.assumeTrue(names.isNotEmpty(), "no samples present")

            val report = names.map { name ->
                val file = sampleFileOf(name)
                val probe = MotionProbeReader.probe(samplerFor(file), file.length())
                val verdict = when (probe) {
                    is MotionProbe.Detected ->
                        "${probe.origin} video@${probe.layout.videoOffset}+${probe.layout.videoLength}"
                    MotionProbe.NotMotionPhoto -> "not a motion photo"
                }
                // Whatever it decides, a detection has to be self-consistent.
                if (probe is MotionProbe.Detected) {
                    assertTrue(probe.layout.isValid(), "$name: $probe")
                }
                "$name -> $verdict".let { println("    $it"); it }
            }
            assertEquals(names.size, report.size)
        }
    }
}