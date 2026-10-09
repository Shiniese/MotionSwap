package com.shiniese.motionswap.core.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

/**
 * Which same-named video pairs with a still image.
 *
 * This is the part of merge that can fail *quietly*: a wrong pairing still produces a file that
 * opens perfectly, it just plays someone else's clip. So the assertions are mostly about refusal —
 * when the evidence does not decide, the answer has to be "no video", never a guess.
 */
@DisplayName("SiblingMatch")
class SiblingMatchTest {

    private fun video(path: String, millis: Long = 0) = VideoCandidate(path, millis)
    private fun still(path: String = "", millis: Long = 0) = StillRef(path, millis)

    @Nested
    @DisplayName("when the folder is known")
    inner class FolderKnown {

        @Test
        fun `only candidates in that folder are eligible`() {
            val candidates = listOf(
                video("Camera/", 1_000),   // 1.2s away
                video("Camera/Other/", 1_001),
                video("DCIM/", 1_000),
            )
            // The two out-of-folder rows are strictly closer in time; the folder still wins,
            // because a wrong folder is a wrong video with no way for the user to notice.
            assertEquals(0, SiblingMatch.pickIndex(still("Camera/", 2_200), candidates))
        }

        @Test
        fun `the closest in capture time breaks the tie`() {
            val candidates = listOf(
                video("Camera/", 5_000),
                video("Camera/", 1_200),
                video("Camera/", 9_000),
            )
            assertEquals(1, SiblingMatch.pickIndex(still("Camera/", 1_000), candidates))
        }

        @Test
        fun `a tie inside the folder still pairs`() {
            // With the folder known, a capture-time tie is harmless: both candidates are in the
            // right place, so either one is as likely to be the right clip as the other.
            val candidates = listOf(video("Camera/", 5_000), video("Camera/", 5_000))
            assertEquals(0, SiblingMatch.pickIndex(still("Camera/", 1_000), candidates))
        }

        @Test
        fun `no candidate in the folder refuses`() {
            val candidates = listOf(video("DCIM/", 1_000), video("Download/", 1_000))
            assertNull(SiblingMatch.pickIndex(still("Camera/", 1_000), candidates))
        }
    }

    @Nested
    @DisplayName("when the folder is unknown")
    inner class FolderUnknown {

        @Test
        fun `capture time decides, since the folder cannot`() {
            val candidates = listOf(
                video("Camera/", 5_000),
                video("WhatsApp/Media/", 1_200),
                video("Download/", 9_000),
            )
            assertEquals(1, SiblingMatch.pickIndex(still("", 1_000), candidates))
        }

        @Test
        fun `a tie refuses rather than trusting row order`() {
            // This is the case the whole fallback exists for: the cursor order is arbitrary, so
            // returning index 0 would mean pairing with whichever row happened to come back first.
            val candidates = listOf(video("Camera/", 1_000), video("Download/", 1_000))
            assertNull(SiblingMatch.pickIndex(still("", 1_000), candidates))
        }

        @Test
        fun `a single candidate pairs without needing to be unique`() {
            // One row cannot be tied with anything, so there is nothing to decide.
            assertEquals(0, SiblingMatch.pickIndex(still("", 1_000), listOf(video("Camera/", 9_000))))
        }

        @Test
        fun `one zero timestamp among several refuses`() {
            // A camera-app video with no usable DATE_TAKEN lands on 0, which is further from the
            // image than everything else — so it loses to a real timestamp rather than winning
            // as "the closest".
            val candidates = listOf(video("Camera/", 0), video("DCIM/", 1_100))
            assertEquals(1, SiblingMatch.pickIndex(still("", 1_000), candidates))
        }

        @Test
        fun `all zero timestamps refuse`() {
            val candidates = listOf(video("Camera/", 0), video("DCIM/", 0))
            assertNull(SiblingMatch.pickIndex(still("", 1_000), candidates))
        }
    }

    @Nested
    @DisplayName("edge cases")
    inner class Edges {

        @Test
        fun `no candidates at all`() {
            assertNull(SiblingMatch.pickIndex(still("Camera/", 1_000), emptyList()))
        }

        @Test
        fun `a blank folder is unknown, so a time tie refuses`() {
            // A whitespace-only path is not a folder called "  " — treating it as one would make
            // every pathless candidate an exact match and then pick by row order. Read as
            // unknown, the stricter rule applies and the tie refuses.
            val candidates = listOf(video("", 1_000), video("", 1_000))
            assertNull(SiblingMatch.pickIndex(still("   ", 1_000), candidates))
        }

        @Test
        fun `a blank folder still pairs when time decides`() {
            val candidates = listOf(video("Camera/", 1_100), video("Download/", 1_000))
            assertEquals(1, SiblingMatch.pickIndex(still("  ", 1_000), candidates))
        }

        @Test
        fun `the trailing slash is normalised away`() {
            // MediaStore records `Camera/`, a picker may report `Camera`. If those were compared
            // literally the image's own folder would match nothing and it would report no sibling.
            val candidates = listOf(video("Camera/", 9_000), video("Camera", 1_000))
            assertEquals(1, SiblingMatch.pickIndex(still("Camera/", 1_000), candidates))
            assertEquals(1, SiblingMatch.pickIndex(still("Camera", 1_000), candidates))
        }

        @Test
        fun `the index points at the original list, not the eligible subset`() {
            // The caller uses the index to fetch from its own list, so an index counted over the
            // filtered candidates would point at the wrong video whenever anything was excluded.
            val candidates = listOf(video("Download/", 1_001), video("Camera/", 1_000))
            assertEquals(1, SiblingMatch.pickIndex(still("Camera/", 1_000), candidates))
            assertEquals("Camera/", candidates[SiblingMatch.pickIndex(still("Camera/", 1_000), candidates)!!].relativePath)
        }

        @Test
        fun `pick returns the candidate the index names`() {
            val candidates = listOf(video("Download/", 1_001), video("Camera/", 1_000))
            assertEquals(candidates[1], SiblingMatch.pick(still("Camera/", 1_000), candidates))
        }

        @Test
        fun `pick is null exactly when the index is`() {
            val candidates = listOf(video("Download/", 1_001))
            assertNull(SiblingMatch.pick(still("Camera/", 1_000), candidates))
        }

        @ParameterizedTest(name = "gap of {0}ms in either direction is the same distance")
        @ValueSource(longs = [0, 1, 1_000, 60_000])
        fun `the gap is unsigned`(gap: Long) {
            val after = listOf(video("Camera/", 1_000 + gap))
            val before = listOf(video("Camera/", 1_000 - gap))
            assertEquals(
                SiblingMatch.pickIndex(still("Camera/", 1_000), after),
                SiblingMatch.pickIndex(still("Camera/", 1_000), before),
            )
        }

        @Test
        fun `normalize trims whitespace and trailing slashes`() {
            assertEquals("Camera", SiblingMatch.normalize("  Camera//  "))
            assertEquals("", SiblingMatch.normalize("   "))
        }
    }

    @Nested
    @DisplayName("the SQL prefilter")
    inner class Prefilter {

        @Test
        fun `unescaped, the prefilter matches a name whose 1st character differs`() {
            // The reason the wildcards are escaped at all: in a raw `LIKE 'IMG_1.%'`, `_` is a
            // single-character wildcard, so `IMGX1.mp4` is admitted for an image named `IMG_1`.
            assertTrue(likeMatches("IMGX1.mp4", "IMG_1.%"))
        }

        @Test
        fun `the trailing dot already narrows the pattern`() {
            // Worth stating because the KDoc's "also matches IMG_10.mp4" is not literally true of
            // `IMG_1.%` — the `.` is a literal there, so `IMG_10.mp4` is rejected. The over-match
            // that does happen is on the character *before* the dot.
            assertFalse(likeMatches("IMG_10.mp4", "IMG_1.%"))
            assertTrue(likeMatches("IMG_1.mp4", "IMG_1.%"))
        }

        @Test
        fun `escaping the wildcard already stops that over-match`() {
            val pattern = SiblingMatch.likePatternFor("IMG_1")
            assertTrue(likeMatches("IMG_1.mp4", pattern))
            assertFalse(likeMatches("IMGX1.mp4", pattern))
        }

        @Test
        fun `the exact check still rejects a longer base name the pattern admits`() {
            // Escaping fixes over-matching within the name, but the pattern is still a *prefix*
            // match on the whole string: `IMG_1.%` accepts `IMG_1.20240101.mp4`, whose base name
            // is not `IMG_1`. Only an exact compare rejects it.
            val pattern = SiblingMatch.likePatternFor("IMG_1")
            assertTrue(likeMatches("IMG_1.20240101.mp4", pattern))
            assertFalse(SiblingMatch.matchesBaseName("IMG_1.20240101.mp4", "IMG_1"))
        }

        @Test
        fun `the prefilter still admits the real sibling`() {
            assertTrue(likeMatches("IMG_1.mp4", SiblingMatch.likePatternFor("IMG_1")))
        }

        @Test
        fun `an escaped wildcard does not match as a wildcard`() {
            // The point of the ESCAPE clause: unescaped, `100%_off.jpg` matches `100abc.mp4`.
            val pattern = SiblingMatch.likePatternFor("100%_off.jpg")
            assertFalse(likeMatches("100abc.mp4", pattern))
            assertTrue(likeMatches("100%_off.jpg.mp4", pattern))
        }

        @ParameterizedTest(name = "{0} does not pair with {1}")
        @CsvSource(
            "IMG_10.mp4, IMG_1",
            "IMG_1 (copy).mp4, IMG_1",
            "IMG_1001.mp4, IMG_100",
        )
        fun `a longer name that merely starts the same way is rejected`(video: String, image: String) {
            assertFalse(SiblingMatch.matchesBaseName(video, image))
        }

        @ParameterizedTest(name = "{0} pairs with {1}")
        @CsvSource(
            "IMG_1.mp4, IMG_1",
            "IMG_1.MOV, IMG_1",
            "a.b.mp4, a.b",
            "a.mp4, a",
        )
        fun `the same base name pairs, whatever the extension and case`(video: String, image: String) {
            assertTrue(SiblingMatch.matchesBaseName(video, image))
        }

        @Test
        fun `an extension-less name is its own base name`() {
            // `substringBeforeLast('.', name)` falls back to the whole string, so the caller's
            // own extension filter is what rejects these — not this comparison.
            assertTrue(SiblingMatch.matchesBaseName("IMG_1", "IMG_1"))
        }

        @Test
        fun `a name that is only an extension has an empty base name`() {
            assertEquals("", ".mp4".substringBeforeLast('.', ".mp4"))
        }

        @ParameterizedTest(name = "{0} is escaped in the LIKE pattern")
        @CsvSource(
            "'100%_off.jpg', '100\\%\\_off.jpg.%'",
            "'a_b', 'a\\_b.%'",
            "'c\\d', 'c\\\\d.%'",
        )
        fun `LIKE wildcards in a file name are escaped`(name: String, expected: String) {
            // Unescaped, `100%_off.jpg` would match every video starting with `100` — and the
            // exact check afterwards cannot save it, because the row never reaches it.
            assertEquals(expected, SiblingMatch.likePatternFor(name))
        }

        @Test
        fun `a digit-free name needs no escaping`() {
            assertEquals("IMG0001.%", SiblingMatch.likePatternFor("IMG0001"))
        }

        @Test
        fun `underscore is escaped even in a plain camera name`() {
            // Worth stating because it is easy to assume `IMG_0001` passes through untouched;
            // it does not, and the escape is what makes the `_` a literal.
            assertEquals("IMG\\_0001.%", SiblingMatch.likePatternFor("IMG_0001"))
        }

        @Test
        fun `an escaped underscore matches only itself, not any character`() {
            val pattern = SiblingMatch.likePatternFor("IMG_1")
            assertTrue(likeMatches("IMG_1.mp4", pattern))
            assertFalse(likeMatches("IMGX1.mp4", pattern))
        }

        @Test
        fun `an empty base name still produces a valid pattern`() {
            assertEquals(".%", SiblingMatch.likePatternFor(""))
        }

    }
}

/**
 * A SQL `LIKE 'pattern'` match with `ESCAPE '\\'`, as SQLite would evaluate it: `%` and `_`
 * are wildcards, a backslash makes the next character literal, and everything else compares
 * exactly.
 *
 * Written out rather than delegated to a regex because the point being tested is the *escaping*
 * — a regex would be a second dialect, and a disagreement about `_` or `\\` would then be a
 * disagreement about the wrong thing.
 */
private fun likeMatches(text: String, pattern: String): Boolean {
    val escaped = buildList {
        var i = 0
        while (i < text.length) {
            if (text[i] == '\\' && i + 1 < text.length) {
                add(text[i + 1]); i += 2
            } else {
                add(text[i]); i++
            }
        }
    }

    fun match(patternIndex: Int, textIndex: Int): Boolean {
        if (patternIndex >= pattern.length) return textIndex >= escaped.size
        val c = pattern[patternIndex]
        return when {
            c == '%' -> (0..(escaped.size - textIndex)).any { skip ->
                match(patternIndex + 1, textIndex + skip)
            }
            c == '_' -> textIndex < escaped.size && match(patternIndex + 1, textIndex + 1)
            // The escape character itself: whatever follows is a literal, wildcard or not.
            c == '\\' -> {
                val literal = pattern.getOrNull(patternIndex + 1)
                literal != null && textIndex < escaped.size && literal == escaped[textIndex] &&
                    match(patternIndex + 2, textIndex + 1)
            }
            else -> textIndex < escaped.size && c == escaped[textIndex] &&
                match(patternIndex + 1, textIndex + 1)
        }
    }

    return match(0, 0)
}