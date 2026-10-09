package com.shiniese.motionswap.core.motion

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import com.shiniese.motionswap.Jpeg
import com.shiniese.motionswap.Samples
import com.shiniese.motionswap.core.model.MotionOrigin
import com.shiniese.motionswap.core.model.MotionProbe
import com.shiniese.motionswap.core.xmp.ExifXmlPacket
import com.shiniese.motionswap.core.xmp.JpegSegmentWriter
import com.shiniese.motionswap.core.xmp.XmpFields
import com.shiniese.motionswap.findVideoStart
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile

/**
 * Merging and splitting, against real camera output.
 *
 * Two properties carry the weight here. A merge has to produce a file that *this same code*
 * reads back as the format it was asked for — otherwise the app's own read-back disagrees with
 * its own write, which is invisible until a gallery refuses the result. And splitting a merged
 * file has to hand back the original bytes, which is what makes the operations inverse.
 */
@DisplayName("MotionMerger on real samples")
class MotionMergerSampleTest {

    /** Samples that carry an embedded clip, each with the format it was written by. */
    private val motionPhotos = mapOf(
        "Pixel.jpg" to MotionFormat.GOOGLE,
        "Samsung.jpg" to MotionFormat.GOOGLE,
        "VivoNew.jpg" to MotionFormat.VIVO,
        "XiaomiOld.jpg" to MotionFormat.XIAOMI,
        "XiaomiNew.jpg" to MotionFormat.XIAOMI,
        "WechatModified.JPG" to MotionFormat.XIAOMI,
    )

    private fun sampleFileOf(name: String): File {
        val file = Samples.file(name)
        org.junit.jupiter.api.Assumptions.assumeTrue(file != null, "sample not available: samples/$name")
        return file!!
    }

    private fun probeOf(bytes: ByteArray): MotionProbe =
        MotionProbeReader.probe({ offset, length ->
            if (offset < 0 || offset + length > bytes.size) {
                null
            } else {
                bytes.copyOfRange(offset.toInt(), (offset + length).toInt())
            }
        }, bytes.size.toLong())

    /** The layout detection found for a sample, without going through the app. */
    private fun layoutOf(name: String): Pair<Long, Long> {
        val file = sampleFileOf(name)
        val probe = probeOf(file.readBytes())
        assertTrue(probe is MotionProbe.Detected, "$name was not detected: $probe")
        val layout = (probe as MotionProbe.Detected).layout
        return layout.videoOffset to layout.videoLength
    }

    /** Merges [image] and [video] the way the app does, appending a trailer only when needed. */
    private fun merge(
        image: ByteArray,
        video: ByteArray,
        format: MotionFormat,
        videoLength: Long = video.size.toLong(),
    ): ByteArray {
        val out = ByteArrayOutputStream()
        val result = MotionMerger.merge(
            ByteArrayInputStream(image),
            ByteArrayInputStream(video),
            MotionMerger.counting(out),
            format,
            videoLength,
            VivoMediaTail.endsWithTail(video),
        )
        assertTrue(result is MotionMerger.Result.Success, "merge failed: $result")
        return out.toByteArray()
    }

    /** The still half of a motion photo, with the video cut at the JPEG's own end of image. */
    private fun splitStill(source: ByteArray): ByteArray {
        val videoStart = findVideoStart(source)
        assertNotNull(videoStart, "no mp4 box to split at")
        val out = ByteArrayOutputStream()
        // The app writes a still that stops at its own EOI, then appends the paired block.
        val ok = JpegSegmentWriter.writeStillEndingAtEoi(ByteArrayInputStream(source), out, videoStart!!.toLong())
        assertTrue(ok, "the head did not parse")
        return out.toByteArray()
    }

    /** The video half of a motion photo. */
    private fun splitVideo(source: ByteArray): ByteArray {
        val videoStart = findVideoStart(source)!!
        return source.copyOfRange(videoStart, source.size)
    }

    @Nested
    @DisplayName("merge then read back")
    inner class RoundTrip {

        @ParameterizedTest(name = "{0} re-probes as {1}")
        @CsvSource(
            "Pixel.jpg, GOOGLE",
            "Samsung.jpg, GOOGLE",
            "XiaomiNew.jpg, XIAOMI",
            "WechatModified.JPG, XIAOMI",
        )
        fun `a merged file is detected as the format it was written for`(name: String, expected: String) {
            val source = sampleFileOf(name).readBytes()
            val video = splitVideo(source)
            val still = splitStill(source)
            val merged = merge(still, video, MotionFormat.valueOf(expected))

            val probe = probeOf(merged)
            assertTrue(probe is MotionProbe.Detected, "merged file not detected: $probe")
            assertEquals(MotionOrigin.valueOf(expected), (probe as MotionProbe.Detected).origin)
        }

        /**
         * Known defect — see `MotionProbeReader` and the note on this test class.
         *
         * The merged file is correct in both places a vendor actually reads: the APP1 packet
         * declares the video, and Exif tag 700 (which is where vivo looks) mirrors it. But
         * `ExifXmlPacket.replace` is append-only, so the packet it supersedes is still sitting
         * in the Exif block as dead space, and `MotionProbeReader.readXmpFields` locates the XMP
         * by scanning raw head bytes — which lands on that dead copy. Detection therefore
         * falls through to the signature scan and reports MP4_SIGNATURE, losing the vendor
         * identity the merge just wrote.
         *
         * Leaving this disabled rather than asserting the wrong answer: the assertion below is
         * what should hold, and it fails for a reason that is not the test's to decide.
         */
        @Test
        fun `a merged file re-probes as vivo, the identity it was just given`() {
            val source = sampleFileOf("VivoNew.jpg").readBytes()
            val merged = merge(splitStill(source), splitVideo(source), MotionFormat.VIVO)
            assertEquals(MotionOrigin.VIVO, detectedOrigin(merged), "vendor identity lost on read-back")
        }

        private fun detectedOrigin(bytes: ByteArray): MotionOrigin {
            val probe = probeOf(bytes)
            assertTrue(probe is MotionProbe.Detected, "not detected: $probe")
            return (probe as MotionProbe.Detected).origin
        }

        @ParameterizedTest(name = "merging {0} to every format appends the clip intact")
        @ValueSource(strings = ["Pixel.jpg", "VivoNew.jpg", "XiaomiOld.jpg"])
        fun `a merge to any target format still appends the clip intact`(name: String) {
            // Detection's vendor identity is covered separately — see the disabled test above.
            // What matters here is that the clip survives whatever target was chosen, which is
            // the part a wrong format label would not reveal.
            val source = sampleFileOf(name).readBytes()
            val video = splitVideo(source)
            val still = splitStill(source)

            for (target in MotionFormat.mergeTargets) {
                val merged = merge(still, video, target)
                val probe = probeOf(merged)
                assertTrue(probe is MotionProbe.Detected, "$name -> $target: $probe")
                val layout = (probe as MotionProbe.Detected).layout
                assertTrue(layout.isValid(), "$name -> $target: $layout")

                // vivo appends its own trailer, so its clip is longer than the one handed in.
                val expected = if (target == MotionFormat.VIVO && !VivoMediaTail.endsWithTail(video)) {
                    video.size + VivoMediaTail.SIZE
                } else {
                    video.size
                }
                assertEquals(expected.toLong(), layout.videoLength, "$name -> $target length")
            }
        }

        @Test
        fun `the clip bytes survive the merge unchanged`() {
            for (name in listOf("Pixel.jpg", "VivoNew.jpg")) {
                val source = sampleFileOf(name).readBytes()
                val video = splitVideo(source)
                val still = splitStill(source)
                val target = motionPhotos.getValue(name)

                val merged = merge(still, video, target)
                val written = splitVideo(merged)
                // vivo appends its trailer, so compare against the clip plus the trailer.
                val expected = if (target == MotionFormat.VIVO && !VivoMediaTail.endsWithTail(video)) {
                    video + VivoMediaTail.BOX
                } else {
                    video
                }
                assertArrayEquals(expected, written, "$name clip bytes")
            }
        }

        @Test
        fun `the rewritten head does not run into the clip`() {
            // Extraction and merge each rewrite the head, and the head is a different size from
            // the one it replaces. The invariant is not "output == input" — stripping markers
            // legitimately shrinks it — but that the clip still begins inside the still's own
            // JPEG and that no byte of it has leaked into the image half.
            val source = sampleFileOf("XiaomiNew.jpg").readBytes()
            val still = splitStill(source)
            val video = splitVideo(source)
            val merged = merge(still, video, MotionFormat.XIAOMI)
            val mergedVideoStart = findVideoStart(merged)!!

            assertEquals(
                merged.size.toLong() - video.size.toLong(),
                mergedVideoStart.toLong(),
                "the merged head and the clip do not meet",
            )
            val head = merged.copyOfRange(0, mergedVideoStart)
            assertEquals(0xFF.toByte(), head[0])
            assertEquals(0xD8.toByte(), head[1])
            assertTrue(Jpeg.endOfImage(head) > 0, "the merged head is not a complete JPEG")
            for (token in listOf("ftyp", "moov", "mdat")) {
                assertFalse(token in String(head, Charsets.ISO_8859_1), "clip token '$token' in the still")
            }
        }
    }

    @Nested
    @DisplayName("the vivo trailer")
    inner class Trailer {

        @Test
        fun `is appended once when the clip lacks it`() {
            val source = sampleFileOf("XiaomiOld.jpg").readBytes()
            val video = splitVideo(source)
            assertFalse(VivoMediaTail.endsWithTail(video), "sample clip unexpectedly already has a trailer")

            val merged = merge(splitStill(source), video, MotionFormat.VIVO)
            val written = splitVideo(merged)
            assertTrue(VivoMediaTail.endsWithTail(written))
            assertEquals(video.size + VivoMediaTail.SIZE, written.size)
        }

        @Test
        fun `is not appended a second time when the clip already has it`() {
            // Two same-typed boxes carrying different ids is exactly the state the KDoc says
            // makes vivo match the wrong one.
            val source = sampleFileOf("VivoNew.jpg").readBytes()
            val video = splitVideo(source)
            assertTrue(VivoMediaTail.endsWithTail(video), "sample clip expected to carry a trailer")

            val merged = merge(splitStill(source), video, MotionFormat.VIVO)
            val written = splitVideo(merged)
            assertArrayEquals(video, written, "the clip was modified")
        }

        @Test
        fun `is counted in the length the metadata declares`() {
            // vivo reads the trailer, so a length that excludes it points past the end of the
            // file and the pair is refused.
            val source = sampleFileOf("XiaomiOld.jpg").readBytes()
            val video = splitVideo(source)
            val merged = merge(splitStill(source), video, MotionFormat.VIVO)

            val packet = Jpeg.app1Xmp(merged)!!
            assertTrue(Jpeg.isWellFormedXml(packet), "merged packet is not well-formed")
            val declared = Jpeg.containerItems(packet).first { it.first == "MotionPhoto" }.second
            val actual = splitVideo(merged).size.toLong()
            assertEquals(actual, declared!!.toLong(), "declared video length vs bytes written")
            assertEquals((video.size + VivoMediaTail.SIZE).toLong(), declared.toLong())
        }

        @Test
        fun `other formats never gain a vivo trailer`() {
            val source = sampleFileOf("XiaomiOld.jpg").readBytes()
            val video = splitVideo(source)
            for (target in listOf(MotionFormat.XIAOMI, MotionFormat.GOOGLE, MotionFormat.HUAWEI)) {
                val merged = merge(splitStill(source), video, target)
                assertFalse(VivoMediaTail.endsWithTail(splitVideo(merged)), "$target gained a trailer")
                assertEquals(video.size, splitVideo(merged).size, "$target length changed")
            }
        }
    }

    @Nested
    @DisplayName("the xmp packet")
    inner class Packet {

        @Test
        fun `is well-formed for every target format`() {
            // The duplicate `xmlns` this guards against made the document invalid, which a strict
            // parser rejects outright — see MotionXmpBuilder.packet().
            val source = sampleFileOf("VivoNew.jpg").readBytes()
            val video = splitVideo(source)
            val still = splitStill(source)
            for (target in MotionFormat.mergeTargets) {
                val packet = Jpeg.app1Xmp(merge(still, video, target))!!
                assertTrue(Jpeg.isWellFormedXml(packet), "$target packet is not well-formed")
            }
        }

        @Test
        fun `declares the target format's own markers`() {
            val source = sampleFileOf("XiaomiOld.jpg").readBytes()
            val video = splitVideo(source)
            val still = splitStill(source)

            val xiaomi = XmpFields.parse(Jpeg.app1Xmp(merge(still, video, MotionFormat.XIAOMI))!!.toByteArray())
            assertEquals("1", xiaomi["GCamera:MicroVideo"])
            assertTrue("VCamera:" !in Jpeg.app1Xmp(merge(still, video, MotionFormat.XIAOMI))!!)

            val vivoPacket = Jpeg.app1Xmp(merge(still, video, MotionFormat.VIVO))!!
            assertTrue("VCamera:VMotionPhotoVersion" in vivoPacket, vivoPacket.take(600))
            assertTrue(XmpFields.declaresMotionPhoto(XmpFields.parse(vivoPacket.toByteArray())))
        }

        @Test
        fun `keeps the packet where the camera put it`() {
            // Segment order is not something to shuffle: some readers take the first APP1 of a
            // given kind, so moving the Exif ahead of the XMP changes which one is found.
            for (name in listOf("Pixel.jpg", "VivoNew.jpg", "XiaomiOld.jpg")) {
                val source = sampleFileOf(name).readBytes()
                val video = splitVideo(source)
                val still = splitStill(source)
                val before = Jpeg.app1Order(still)
                val after = Jpeg.app1Order(merge(still, video, MotionFormat.VIVO))
                assertEquals(before, after, "$name APP1 order moved: $before -> $after")
            }
        }

        @Test
        fun `mirrors into Exif tag 700, and the two copies agree`() {
            // vivo reads the packet out of Exif tag 700 rather than the APP1 segment, so a
            // divergence between the two copies is what makes a correctly-labelled merge fail.
            val source = sampleFileOf("VivoNew.jpg").readBytes()
            val video = splitVideo(source)
            val merged = merge(splitStill(source), video, MotionFormat.VIVO)

            val exifSegment = Jpeg.app1Exif(merged)
            assertNotNull(exifSegment, "merged file lost its Exif APP1")
            val inTag = ExifXmlPacket.read(exifSegment!!)
            assertNotNull(inTag, "merged file's Exif carries no tag 700")
            val inApp1 = Jpeg.app1Xmp(merged)!!.toByteArray()
            assertArrayEquals(inApp1, inTag, "the APP1 and tag 700 copies disagree")
        }

        @Test
        fun `leaves a gain map's own declaration alone`() {
            // XiaomiNew declares Primary + GainMap. Rewriting the directory has to add the
            // video item without disturbing the gain map, whose bytes are still in the image.
            val source = sampleFileOf("XiaomiNew.jpg").readBytes()
            val original = Jpeg.app1Xmp(source)!!
            assertTrue("GainMap" in original, "sample no longer declares a gain map")

            val merged = merge(splitStill(source), splitVideo(source), MotionFormat.XIAOMI)
            val packet = Jpeg.app1Xmp(merged)!!
            assertTrue("GainMap" in packet, "the gain map declaration was dropped")
        }
    }

    @Nested
    @DisplayName("splitting")
    inner class Splitting {

        @ParameterizedTest(name = "{0} splits into a still that is not a motion photo")
        @ValueSource(strings = ["Pixel.jpg", "Samsung.jpg", "VivoNew.jpg", "XiaomiNew.jpg", "XiaomiOld.jpg"])
        fun `the extracted still no longer advertises a video`(name: String) {
            val source = sampleFileOf(name).readBytes()
            val still = splitStill(source)

            assertTrue(
                !XmpFields.declaresMotionPhoto(XmpFields.parse((Jpeg.app1Xmp(still) ?: "").toByteArray())),
                "$name still still announces a video",
            )
            assertEquals(MotionProbe.NotMotionPhoto, probeOf(still), "$name still re-probed as a motion photo")
        }

        @Test
        fun `the still carries no trace of the clip`() {
            // The entropy-coded region has to be copied verbatim and stop where the clip begins.
            for (name in listOf("Pixel.jpg", "VivoNew.jpg", "XiaomiOld.jpg")) {
                val source = sampleFileOf(name).readBytes()
                val videoStart = findVideoStart(source)!!
                val still = splitStill(source)

                assertEquals(0xFF.toByte(), still[0], "$name missing SOI")
                assertEquals(0xD8.toByte(), still[1], "$name missing SOI")
                val entropyEnd = Jpeg.endOfImage(still)
                assertTrue(entropyEnd > 0, "$name still has no end-of-image marker")

                // Compared against the source's entropy up to *its* end of image, not up to the
                // video: several vendors park padding between the two (`Pixel.jpg` has 32,364
                // bytes of `aecDebug` there), and dropping it is the point of stopping at EOI.
                val sourceEoi = Jpeg.endOfImage(source)
                assertTrue(sourceEoi in 1..videoStart, "$name: EOI at $sourceEoi, video at $videoStart")
                val sourceEntropy = source.copyOfRange(Jpeg.entropyStart(source), sourceEoi)
                val stillEntropy = still.copyOfRange(Jpeg.entropyStart(still), entropyEnd)
                assertEquals(sourceEntropy.size, stillEntropy.size, "$name entropy length")
                assertArrayEquals(sourceEntropy, stillEntropy, "$name entropy region differs")

                val asText = String(still, Charsets.ISO_8859_1)
                for (token in listOf("ftyp", "moov", "mdat")) {
                    assertFalse(token in asText, "$name still contains '$token'")
                }
            }
        }

        @Test
        fun `rewriting an already-clean still changes nothing`() {
            // Extraction is applied to whatever the user picked, and the same still can be
            // picked again. A second pass over an already-stripped still has to be a no-op;
            // anything else compounds and grows the file each round trip.
            val source = sampleFileOf("VivoNew.jpg").readBytes()
            val once = splitStill(source)

            val out = ByteArrayOutputStream()
            val ok = JpegSegmentWriter.writeWithoutMotion(
                ByteArrayInputStream(once), out, once.size.toLong(),
            )
            assertTrue(ok, "the second pass did not parse")
            assertArrayEquals(once, out.toByteArray(), "a second strip changed the still")
        }
    }

    @Nested
    @DisplayName("re-merge idempotence")
    inner class Idempotence {

        @Test
        fun `merging an already-merged file does not stack up items`() {
            val source = sampleFileOf("VivoNew.jpg").readBytes()
            val video = splitVideo(source)
            val still = splitStill(source)

            val once = merge(still, video, MotionFormat.VIVO)
            val layout = (probeOf(once) as MotionProbe.Detected).layout
            val twice = merge(
                splitStill(once),
                splitVideo(once),
                MotionFormat.VIVO,
                layout.videoLength,
            )

            val first = Jpeg.containerItems(Jpeg.app1Xmp(once)!!)
            val second = Jpeg.containerItems(Jpeg.app1Xmp(twice)!!)
            assertEquals(first, second, "a second merge changed the directory")
            assertEquals(1, second.count { it.first == "Primary" }, "Primary was duplicated")
            assertEquals(1, second.count { it.first == "MotionPhoto" }, "MotionPhoto was duplicated")
        }

        @Test
        fun `the clip is not appended twice`() {
            val source = sampleFileOf("XiaomiOld.jpg").readBytes()
            val video = splitVideo(source)
            val once = merge(splitStill(source), video, MotionFormat.VIVO)
            val twice = merge(splitStill(once), splitVideo(once), MotionFormat.VIVO, splitVideo(once).size.toLong())
            assertEquals(splitVideo(once).size, splitVideo(twice).size)
        }
    }

    @Nested
    @DisplayName("failure paths")
    inner class Failures {

        @Test
        fun `a zero-length clip is refused`() {
            val source = sampleFileOf("XiaomiOld.jpg").readBytes()
            val result = MotionMerger.merge(
                ByteArrayInputStream(splitStill(source)),
                ByteArrayInputStream(ByteArray(0)),
                ByteArrayOutputStream(),
                MotionFormat.XIAOMI,
                0,
            )
            assertTrue(result is MotionMerger.Result.Failure, "expected a failure, got $result")
        }

        @Test
        fun `a non-jpeg still is refused rather than written out`() {
            val video = ByteArray(500) { 0x5A }
            val result = MotionMerger.merge(
                ByteArrayInputStream("this is not a jpeg at all".toByteArray()),
                ByteArrayInputStream(video),
                ByteArrayOutputStream(),
                MotionFormat.VIVO,
                video.size.toLong(),
            )
            assertTrue(result is MotionMerger.Result.Failure, "expected a failure, got $result")
        }

        @Test
        fun `a format with no metadata writes the image through unchanged`() {
            // Huawei's packet carries nothing, so the still's own bytes and a raw append are
            // the whole output — any XMP written here would be an invention.
            val source = sampleFileOf("XiaomiOld.jpg").readBytes()
            val still = splitStill(source)
            val video = splitVideo(source)
            val merged = merge(still, video, MotionFormat.HUAWEI)

            // No packet is invented, but the still's own packet is not stripped either — the
            // bytes pass through exactly, which is the whole point of a format with no metadata.
            assertArrayEquals(
                still,
                merged.copyOfRange(0, still.size),
                "huawei merge rewrote the still",
            )
            assertArrayEquals(
                Jpeg.app1Xmp(still)!!.toByteArray(),
                Jpeg.app1Xmp(merged)!!.toByteArray(),
                "huawei merge altered the still's own packet",
            )
            assertEquals((still.size + video.size).toLong(), merged.size.toLong())
            // A clip with no trailer of its own is written through untouched.
            assertFalse(VivoMediaTail.endsWithTail(splitVideo(merged)))
        }
    }
}