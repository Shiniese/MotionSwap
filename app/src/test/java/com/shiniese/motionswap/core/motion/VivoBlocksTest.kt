package com.shiniese.motionswap.core.motion

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Test

/**
 * vivo's two trailer mechanisms.
 *
 * Both blocks are byte literals copied off a device, so what is worth testing is not that they
 * "look right" but the properties that make them work: exact sizes, the shared id, and the
 * length-preserving substitution. A bug here produces a file vivo silently refuses, which is the
 * failure mode the device-verified variant table in each object's KDoc was assembled to rule out.
 */
@DisplayName("vivo trailer blocks")
class VivoBlocksTest {

    private fun hexOf(hex: String): ByteArray {
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            out[i] = ((Character.digit(hex[i * 2], 16) shl 4) or Character.digit(hex[i * 2 + 1], 16)).toByte()
        }
        return out
    }

    @Nested
    @DisplayName("VivoMediaTail (embedded mode)")
    inner class Tail {

        @Test
        fun `BOX is exactly SIZE bytes`() {
            // The two are separate declarations, so a typo in one would silently corrupt every
            // length arithmetic that uses the other.
            assertEquals(VivoMediaTail.SIZE, VivoMediaTail.BOX.size)
        }

        @Test
        fun `BOX declares its own size in the header`() {
            // A `uuid` box leads with a big-endian length covering the whole box.
            val declared = ByteSearch32.readUInt32BE(VivoMediaTail.BOX)
            assertEquals(VivoMediaTail.SIZE.toLong(), declared)
        }

        @Test
        fun `BOX is a uuid box naming vivoMediaExtInfo`() {
            assertEquals("uuid", String(VivoMediaTail.BOX.copyOfRange(4, 8), Charsets.US_ASCII))
            assertTrue(
                String(VivoMediaTail.BOX, Charsets.ISO_8859_1).contains("vivoMediaExtInfo"),
                "usertype not found in the box",
            )
        }

        @Test
        fun `endsWithTail is true only for a video carrying it at the very end`() {
            val video = ByteArray(1_000) { 0x5A } + VivoMediaTail.BOX
            assertTrue(VivoMediaTail.endsWithTail(video))
        }

        @Test
        fun `a trailer anywhere but the end does not count`() {
            // Merging must not append a second box to a clip that has one mid-file.
            val video = VivoMediaTail.BOX + ByteArray(1_000) { 0x5A }
            assertFalse(VivoMediaTail.endsWithTail(video))
        }

        @Test
        fun `a video shorter than the box cannot carry it`() {
            assertFalse(VivoMediaTail.endsWithTail(ByteArray(0)))
            assertFalse(VivoMediaTail.endsWithTail(ByteArray(VivoMediaTail.SIZE - 1) { 0x5A }))
        }

        @Test
        fun `same length but different bytes is not the box`() {
            val video = ByteArray(VivoMediaTail.SIZE) { 0x5A }
            assertFalse(VivoMediaTail.endsWithTail(video))
        }

        @Test
        fun `a single flipped byte in the tail defeats detection`() {
            // vivo's reader keys off the box; a corrupt one must not be mistaken for a present
            // one, or the merger would append a second copy of a box that is already there.
            val video = ByteArray(1_000) { 0x5A } + VivoMediaTail.BOX
            val corrupted = video.copyOf()
            corrupted[corrupted.size - 1] = (corrupted.last().toInt() xor 0x01).toByte()
            assertFalse(VivoMediaTail.endsWithTail(corrupted))
        }
    }

    @Nested
    @DisplayName("VivoPairBlocks (pair mode)")
    inner class Pair {

        @Test
        fun `degs is exactly DEGS_SIZE bytes`() {
            assertEquals(VivoPairBlocks.DEGS_SIZE, VivoPairBlocks.degs(Fixture.id).size)
        }

        @Test
        fun `uuidBox is exactly UUID_BOX_SIZE bytes`() {
            assertEquals(VivoPairBlocks.UUID_BOX_SIZE, VivoPairBlocks.uuidBox(Fixture.id).size)
        }

        @Test
        fun `the two blocks are different sizes and different boxes`() {
            // They share a box type but nothing else, which is why substituting one for the
            // other does not work on device.
            assertNotEquals(VivoPairBlocks.DEGS_SIZE, VivoPairBlocks.UUID_BOX_SIZE)
            assertNotEquals(
                VivoPairBlocks.degs(Fixture.id).toList(),
                VivoPairBlocks.uuidBox(Fixture.id).toList(),
            )
        }

        @Test
        fun `each block carries the id twice`() {
            // Two occurrences, because each block names the photo in both its JSON and its
            // binary tail. `patch` fails the build if that count is not exactly two.
            assertEquals(2, countOccurrences(VivoPairBlocks.degs(Fixture.id), Fixture.id))
            assertEquals(2, countOccurrences(VivoPairBlocks.uuidBox(Fixture.id), Fixture.id))
        }

        @Test
        fun `the id is written at every position the template held`() {
            val other = "1800000000000aaaaaaa00000000".toByteArray(Charsets.US_ASCII)
            assertNotEquals(Fixture.id.toList(), other.toList())
            val degs = VivoPairBlocks.degs(other)
            assertEquals(2, countOccurrences(degs, other))
            // And the template's own id is gone, not merely shadowed.
            assertEquals(0, countOccurrences(degs, VivoPairBlocksTemplateId))
        }

        @Test
        fun `patching with the template id yields the block unchanged`() {
            // Substitution has to write the id it is given, even when that id happens to be the
            // one already in the template. An implementation that wrote the template's own id
            // over the placeholders instead would look correct on this call and silently keep
            // every photo's id equal to the sample's.
            val degs = VivoPairBlocks.degs(VivoPairBlocksTemplateId)
            assertEquals(2, countOccurrences(degs, VivoPairBlocksTemplateId))
            assertArrayEquals(VivoPairBlocks.degs(Fixture.id), VivoPairBlocks.degs(Fixture.id))
        }

        @Test
        fun `patching changes nothing but the two id runs`() {
            // Length-preserving substitution: the blocks carry big-endian lengths for the run
            // before each id, so a single moved byte would leave those pointing at the wrong
            // offset. The id used here shares no byte with the template's, so every one of the
            // 2 x 28 differing positions is attributable to the id and nothing else.
            val distinct = DisjointFromTemplateId
            assertEquals(0, sharedByteCount(distinct, VivoPairBlocksTemplateId))

            val patched = VivoPairBlocks.degs(distinct)
            val pristine = VivoPairBlocks.degs(VivoPairBlocksTemplateId)
            assertEquals(VivoPairBlocks.DEGS_SIZE, patched.size)
            assertEquals(VivoPairBlocks.DEGS_SIZE, pristine.size)

            val changed = (0 until patched.size).filter { patched[it] != pristine[it] }.toSet()
            // Exactly the two id ranges, at the offsets where the template's id sat.
            val runs = indexRuns(pristine, VivoPairBlocksTemplateId)
assertEquals(2, runs.size)
            assertTrue(runs.all { it.last - it.first + 1 == VivoPairBlocks.ID_LENGTH }, "runs: $runs")
            assertEquals(VivoPairBlocks.ID_LENGTH * 2, runs.sumOf { it.last - it.first + 1 })
            assertTrue(
                changed.all { at -> runs.any { at >= it.first && at <= it.last } },
                "bytes changed outside the id runs: ${changed.filter { at -> runs.none { at >= it.first && at <= it.last } }}",
            )
            assertEquals(changed.size, VivoPairBlocks.ID_LENGTH * 2)
        }

        @Test
        fun `an id of the wrong length is rejected`() {
            val tooShort = ByteArray(VivoPairBlocks.ID_LENGTH - 1)
            val tooLong = ByteArray(VivoPairBlocks.ID_LENGTH + 1)
            assertThrows(IllegalArgumentException::class.java) { VivoPairBlocks.degs(tooShort) }
            assertThrows(IllegalArgumentException::class.java) { VivoPairBlocks.uuidBox(tooLong) }
            assertThrows(IllegalArgumentException::class.java) { VivoPairBlocks.degs(ByteArray(0)) }
        }

        @Test
        fun `the two blocks carry the same id, which is the whole point`() {
            // A still and a clip are only paired when their ids match; D_hex was refused on
            // device precisely because the two disagreed.
            val id = "1700000000000bbbbccc00000000".toByteArray(Charsets.US_ASCII)
            assertEquals(2, countOccurrences(VivoPairBlocks.degs(id), id))
            assertEquals(2, countOccurrences(VivoPairBlocks.uuidBox(id), id))
        }

        @Test
        fun `a non-hex id is accepted because vivo does not check`() {
            // G was accepted on device with neutral strings, so only uniqueness matters. This
            // documents that the code does not over-validate.
            val odd = "zzzzzzzn0000000000000nothex1".toByteArray(Charsets.US_ASCII)
            assertEquals(VivoPairBlocks.DEGS_SIZE, VivoPairBlocks.degs(odd).size)
        }

        @Nested
        @DisplayName("newId")
        inner class Ids {

            @Test
            fun `is ID_LENGTH long and all lowercase hex`() {
                repeat(50) {
                    val id = VivoPairBlocks.newId()
                    assertEquals(VivoPairBlocks.ID_LENGTH, id.size)
                    val text = String(id, Charsets.US_ASCII)
                    assertTrue(
                        text.all { it in "0123456789abcdef" },
                        "non-hex id generated: $text",
                    )
                }
            }

            @Test
            fun `ends in zeros`() {
                // Both real samples are shaped this way. Not proven necessary, but matching the
                // device's own shape costs nothing.
                assertTrue(String(VivoPairBlocks.newId(), Charsets.US_ASCII).endsWith("00000000"))
            }

            @Test
            fun `newIdString agrees with newId`() {
                val fromString = VivoPairBlocks.newIdString()
                assertEquals(VivoPairBlocks.ID_LENGTH, fromString.length)
                assertTrue(fromString.all { it in "0123456789abcdef" }, fromString)
            }

            @RepeatedTest(20)
            fun `ids are distinct within a batch`() {
                // The timestamp makes a batch distinct without coordination, and only the random
                // middle separates ids created inside the same millisecond.
                val ids = (0 until 200).map { String(VivoPairBlocks.newId(), Charsets.US_ASCII) }
                assertEquals(ids.size, ids.toSet().size, "duplicate ids within one batch")
            }

            @Test
            fun `a generated id drops straight into both blocks`() {
                val id = VivoPairBlocks.newId()
                assertEquals(VivoPairBlocks.DEGS_SIZE, VivoPairBlocks.degs(id).size)
                assertEquals(VivoPairBlocks.UUID_BOX_SIZE, VivoPairBlocks.uuidBox(id).size)
            }
        }
    }

    private companion object Fixture {
        val id = "1700000000000aaaa11100000000".toByteArray(Charsets.US_ASCII)
    }
}

/** The template id baked into both literals; tests compare against it to prove it was replaced. */
private val VivoPairBlocksTemplateId = "17909322347238ae839500000000".toByteArray(Charsets.US_ASCII)

private fun countOccurrences(haystack: ByteArray, needle: ByteArray): Int {
    var count = 0
    outer@ for (i in 0..haystack.size - needle.size) {
        for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
        count++
    }
    return count
}

/** Inclusive index ranges covering every occurrence of [needle] in [haystack]. */
private fun indexRuns(haystack: ByteArray, needle: ByteArray): List<IntRange> {
    val runs = mutableListOf<IntRange>()
    var i = 0
    outer@ while (i <= haystack.size - needle.size) {
        for (j in needle.indices) if (haystack[i + j] != needle[j]) {
            i++
            continue@outer
        }
        runs += i..(i + needle.size - 1)
        i += needle.size
    }
    return runs
}

/** How many byte values [a] and [b] have in common; zero means every substitution changes something. */
private fun sharedByteCount(a: ByteArray, b: ByteArray): Int = a.toSet().intersect(b.toSet()).size

/**
 * An id built only from byte values the template's id does not use, so patching with it changes
 * all 28 bytes of each run rather than coincidentally matching some of them.
 *
 * The template id is digits plus `a`, `b`, `e`, `5`, `8`; `c`, `d`, `f` and `9` are free.
 */
private val DisjointFromTemplateId: ByteArray =
    ("ccccccc" + "ddddddd" + "fffffff" + "6666666")
        .also { require(it.length == 28) { "must be 28 chars, was ${it.length}" } }
        .toByteArray(Charsets.US_ASCII)

/** Local copy of the UInt32 read, since `ByteSearch` is internal to the xmp package. */
private object ByteSearch32 {
    fun readUInt32BE(data: ByteArray): Long = ((data[0].toLong() and 0xFF) shl 24) or
        ((data[1].toLong() and 0xFF) shl 16) or
        ((data[2].toLong() and 0xFF) shl 8) or
        (data[3].toLong() and 0xFF)
}