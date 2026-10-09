package com.shiniese.motionswap.core.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * Whether a MediaStore timestamp can be believed.
 *
 * `DATE_TAKEN` is the column the sibling match sorts on, and both of its failure modes are
 * plausible-looking longs rather than obviously absent — which is why a `> 0` test is not enough.
 */
@DisplayName("MediaTime")
class MediaTimeTest {

    private val year2024 = 1_704_067_200_000L
    private val year2015 = 1_421_577_600_000L

    @Nested
    @DisplayName("isPlausible")
    inner class Plausible {

        @Test
        fun `an ordinary capture time is plausible`() {
            assertTrue(MediaTime.isPlausible(year2024))
        }

        @ParameterizedTest(name = "{0} is not a real capture time")
        @ValueSource(longs = [0L, -1L, 1L, 946_684_799_999L])
        fun `absent and sub-2000 values are rejected`(millis: Long) {
            assertFalse(MediaTime.isPlausible(millis))
        }

        @Test
        fun `the window is wide enough to absorb clock skew`() {
            // Deliberately: the range is not tightened to a "sensible" decade, because a device
            // with a wrong clock still writes a plausible photo and refusing it would be worse.
            assertTrue(MediaTime.isPlausible(946_684_800_000L))   // exactly 2000-01-01
            assertTrue(MediaTime.isPlausible(1_577_836_800_000L)) // 2020-01-01
            assertTrue(MediaTime.isPlausible(4_102_444_800_000L)) // exactly 2100-01-01
        }

        @Test
        fun `the 1601-epoch sentinel is rejected`() {
            // Some devices store the timestamp in seconds since 1601, which surfaces as ~9e12 —
            // positive, plausible-looking, and out by three centuries.
            assertFalse(MediaTime.isPlausible(9_000_000_000_000L))
            assertFalse(MediaTime.isPlausible(13_000_000_000_000L))
        }

        @Test
        fun `the window boundaries are inclusive`() {
            val earliest = 946_684_800_000L  // 2000-01-01T00:00:00Z
            val latest = 4_102_444_800_000L  // 2100-01-01T00:00:00Z
            assertTrue(MediaTime.isPlausible(earliest))
            assertTrue(MediaTime.isPlausible(latest))
            assertFalse(MediaTime.isPlausible(earliest - 1))
            assertFalse(MediaTime.isPlausible(latest + 1))
        }
    }

    @Nested
    @DisplayName("resolve")
    inner class Resolve {

        @Test
        fun `a plausible DATE_TAKEN wins`() {
            assertEquals(year2024, MediaTime.resolve(year2024, 1_700_000_000))
        }

        @Test
        fun `DATE_ADDED stands in when DATE_TAKEN is zero`() {
            // The common camera-app case: the scanner could not extract DATE_TAKEN from the
            // video, but DATE_ADDED is always populated because it had to be written to index it.
            assertEquals(1_700_000_000_000L, MediaTime.resolve(0, 1_700_000_000))
        }

        @Test
        fun `DATE_ADDED stands in for the 1601-epoch sentinel too`() {
            assertEquals(1_700_000_000_000L, MediaTime.resolve(9_000_000_000_000L, 1_700_000_000))
        }

        @Test
        fun `DATE_ADDED is scaled from seconds`() {
            // Compared unscaled, a seconds value would look like a time 1000x too early and
            // sort every candidate incorrectly rather than failing visibly.
            assertEquals(1_700_000_000_000L, MediaTime.resolve(0, 1_700_000_000))
        }

        @Test
        fun `neither column usable yields zero`() {
            assertEquals(0L, MediaTime.resolve(0, 0))
            assertEquals(0L, MediaTime.resolve(-1, 0))
            assertEquals(0L, MediaTime.resolve(9_000_000_000_000L, 0))
        }

        @Test
        fun `zero means every candidate ties, which the matcher reports as undecidable`() {
            // The contract between the two: `resolve` returning 0 is only safe because
            // `SiblingMatch` refuses rather than picking a nearest row among equals.
            val candidates = listOf(
                VideoCandidate("Camera/", 0),
                VideoCandidate("Download/", 0),
            )
            assertNull(SiblingMatch.pickIndex(StillRef("", year2015), candidates))
        }

        @Test
        fun `a resolved DATE_ADDED does decide the pairing`() {
            // Two candidates, both missing DATE_TAKEN, resolved from DATE_ADDED seconds: the one
            // whose timestamp is nearest the image must win, which only works if scaling happened.
            val still = StillRef("", 1_700_000_000_000L)
            val candidates = listOf(
                VideoCandidate("Camera/", MediaTime.resolve(0, 1_700_050_000)),
                VideoCandidate("Download/", MediaTime.resolve(0, 1_700_001_000)),
            )
            assertEquals(1, SiblingMatch.pickIndex(still, candidates))
        }
    }

    private fun assertNull(actual: Any?) = assertEquals(null, actual)
}