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
 * Which file name to trust when the picker and the collection disagree.
 *
 * The sibling lookup keys off this name. When the wrong one wins, the search finds nothing and the
 * image reports no sibling video regardless of how well the folder and timestamps line up.
 */
@DisplayName("SiblingName")
class SiblingNameTest {

    @Nested
    @DisplayName("isUsableFileName")
    inner class Usable {

        @ParameterizedTest(name = "{0} is a plausible name")
        @ValueSource(strings = ["IMG_0001.jpg", "a.b.c.jpeg", "photo1.JPG", "中文名字.jpg", ".hidden.jpg"])
        fun `a name with an extension is usable`(name: String) {
            assertTrue(SiblingName.isUsableFileName(name))
        }

        @ParameterizedTest(name = "{0} is not a plausible name")
        @ValueSource(strings = ["image_42", "IMG_0001", "", "   ", "photo.", ".", "trailing."])
        fun `an extension-less or blank name is not`(name: String) {
            // A picker row the provider declined to name reports a bare id. This app only merges
            // JPEGs, so a missing extension is evidence the string is synthetic — which is what
            // makes it safe to go looking for the real name instead.
            assertFalse(SiblingName.isUsableFileName(name))
        }

        @Test
        fun `a trailing dot is rejected even though a dot is present`() {
            assertFalse(SiblingName.isUsableFileName("photo."))
        }
    }

    @Nested
    @DisplayName("authoritative")
    inner class Authoritative {

        @Test
        fun `the collection name wins when both are usable`() {
            // A row read back out of MediaStore is closer to the file than whatever the picker
            // chose to hand over, so it wins when both are real names.
            assertEquals("IMG_0001.jpg", SiblingName.authoritative("picked_42", "IMG_0001.jpg"))
        }

        @Test
        fun `a usable picked name beats an unusable collection name`() {
            assertEquals("IMG_0001.jpg", SiblingName.authoritative("IMG_0001.jpg", "image_42"))
        }

        @Test
        fun `a synthetic collection name is replaced, not trusted`() {
            // The exact case the object exists for: the picker handed over a placeholder, so the
            // collection's real name is what makes the sibling search match anything at all.
            assertEquals("IMG_0001.jpg", SiblingName.authoritative("image_42", "IMG_0001.jpg"))
        }

        @Test
        fun `a null collection name falls through to the picked name`() {
            assertEquals("IMG_0001.jpg", SiblingName.authoritative("IMG_0001.jpg", null))
        }

        @Test
        fun `when neither is usable, a non-blank collection name is still preferred`() {
            // Both are synthetic, so neither can drive a search — but the collection row is
            // still the closer read of the two, and it is what the caller should display.
            assertEquals("image_42", SiblingName.authoritative("picked_42", "image_42"))
        }

        @Test
        fun `the result is never blank`() {
            assertFalse(SiblingName.authoritative("image_42", null).isBlank())
            assertFalse(SiblingName.authoritative("", "image_42").isBlank())
        }

        @Test
        fun `a good name is never overwritten with a blank one`() {
            // The rule's floor: metadata is evidence, not fact, but a blank is not evidence of
            // anything — losing a real name would break every sibling lookup that worked.
            assertEquals("IMG_0001.jpg", SiblingName.authoritative("IMG_0001.jpg", "   "))
            assertEquals("IMG_0001.jpg", SiblingName.authoritative("IMG_0001.jpg", null))
        }

        @Test
        fun `both blank falls back to the picked name`() {
            assertEquals("", SiblingName.authoritative("", ""))
        }

        @Test
        fun `the chosen name can actually drive a sibling search`() {
            // The end-to-end consequence: with the synthetic name the LIKE prefilter matches
            // nothing, and with the collection name it finds the pair.
            val candidates = listOf(VideoCandidate("Camera/", 0))
            val baseName = SiblingName.authoritative("image_42", "IMG_0001.jpg")
            assertEquals("IMG_0001", baseName.substringBeforeLast('.'))
            assertEquals(0, SiblingMatch.pickIndex(StillRef("Camera/", 0), candidates))
        }
    }
}