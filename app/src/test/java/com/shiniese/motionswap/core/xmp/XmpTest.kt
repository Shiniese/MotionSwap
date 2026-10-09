package com.shiniese.motionswap.core.xmp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import com.shiniese.motionswap.core.motion.MotionFormat

/** Locating the XMP packet inside raw image bytes. */
@DisplayName("XmpPacket")
class XmpPacketTest {

    private fun s(v: String) = v.toByteArray(Charsets.UTF_8)

    private fun ByteArray.text(range: ByteRange): String =
        String(copyOfRange(range.start, range.endExclusive), Charsets.UTF_8)

    @Nested
    @DisplayName("locate")
    inner class Locate {

        @Test
        fun `finds a well-formed xmpmeta envelope`() {
            val blob = s("""junk<prefix><x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF/></x:xmpmeta>trailer""")
            val range = XmpPacket.locate(blob)!!
            val located = blob.text(range)
            assertTrue(located.startsWith("<x:xmpmeta"), located.take(30))
            assertTrue(located.endsWith("</x:xmpmeta>"), located.takeLast(30))
            // The envelope, not the surrounding junk.
            assertTrue(range.start > 0 && range.endExclusive < blob.size)
        }

        @Test
        fun `falls back to an xpacket block when there is no envelope`() {
            // Some cameras wrap the packet in `<?xpacket ...?>` with no `<x:xmpmeta>` at all.
            val blob = s("""<head><?xpacket begin="﻿" id="W5M0MpCehiHzreSzNTczkc9d"?><rdf:RDF/><?xpacket end="w"?>""")
            val range = XmpPacket.locate(blob)
            assertNotNull(range)
            val text = blob.text(range!!)
            assertTrue(text.startsWith("<?xpacket begin="), text.take(40))
            assertTrue(text.endsWith("?>"), text.takeLast(20))
        }

        @Test
        fun `prefers the envelope when both forms are present`() {
            val blob = s("""<?xpacket begin="" id="x"?><x:xmpmeta></x:xmpmeta><?xpacket end="w"?>""")
            assertEquals("<x:xmpmeta></x:xmpmeta>", blob.text(XmpPacket.locate(blob)!!))
        }

        @Test
        fun `an envelope with no closing tag falls back to the xpacket block`() {
            // A self-closing `<x:xmpmeta/>` has no `</x:xmpmeta>` to find, so the search for one
            // fails and the xpacket fallback takes over. Taking the envelope anyway would slice
            // out a range that was never delimited.
            val blob = s("""<?xpacket begin="" id="x"?><x:xmpmeta/><?xpacket end="w"?>""")
            val range = XmpPacket.locate(blob)!!
            assertTrue(blob.text(range).startsWith("<?xpacket"), blob.text(range).take(30))
        }

        @Test
        fun `returns null when there is no packet at all`() {
            assertNull(XmpPacket.locate(ByteArray(0)))
            assertNull(XmpPacket.locate(s("no xmp here, just entropy data")))
        }

        @Test
        fun `an unterminated envelope is not a packet`() {
            // Half a packet is worse than none: parsing it yields attributes that were never
            // really there. Detection must fall through to the signature scan instead.
            assertNull(XmpPacket.locate(s("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF/>")))
            assertNull(XmpPacket.locate(s("<x:xmpmeta")))
        }

        @Test
        fun `an unterminated xpacket block is not a packet either`() {
            assertNull(XmpPacket.locate(s("<?xpacket begin=\"\" id=\"x\"?><rdf/>")))
        }

        @Test
        fun `the closing envelope is the last one, so a nested-looking pair still works`() {
            val blob = s("<x:xmpmeta/><x:xmpmeta>inner</x:xmpmeta>")
            // indexOf scans forward from the opening tag, so the first close is the one taken.
            assertTrue(blob.text(XmpPacket.locate(blob)!!).endsWith("</x:xmpmeta>"))
        }
    }

    @Nested
    @DisplayName("normalize")
    inner class Normalize {

        @Test
        fun `trims leading junk so the payload starts at a tag`() {
            val payload = s("﻿  \u0000\u0007<x:xmpmeta/>")
            assertEquals("<x:xmpmeta/>", String(XmpPacket.normalize(payload), Charsets.UTF_8))
        }

        @Test
        fun `leaves a payload that already starts at a tag alone`() {
            val payload = s("<rdf:RDF xmlns:rdf=\"x\"/>")
            assertArrayEqualsExact(payload, XmpPacket.normalize(payload))
        }

        @Test
        fun `falls back to the earliest of the three accepted openings`() {
            // A payload can begin with `<?xpacket` and still contain `<x:` further in; the cut
            // has to be the earliest, or the leading declaration is left behind.
            val payload = s("\u0000<?xpacket begin=\"\"?><x:xmpmeta/>")
            assertTrue(String(XmpPacket.normalize(payload), Charsets.UTF_8).startsWith("<?xpacket"))
        }

        @Test
        fun `returns the input unchanged when there is no tag to find`() {
            val payload = s("plain bytes")
            assertArrayEqualsExact(payload, XmpPacket.normalize(payload))
        }

        private fun assertArrayEqualsExact(a: ByteArray, b: ByteArray) {
            assertEquals(String(a, Charsets.UTF_8), String(b, Charsets.UTF_8))
        }
    }
}

/**
 * Normalising vendor XMP shapes into one flat map.
 *
 * The samples disagree about everything — attribute names vs elements, qualified vs bare,
 * one `Container:Directory` per item vs a comma list — so these cover the three shapes the
 * readers actually have to cope with.
 */
@DisplayName("XmpFields")
class XmpFieldsTest {

    @Test
    fun `reads a qualified attribute`() {
        val fields = XmpFields.parse("""<x:xmpmeta><rdf:Description GCamera:MicroVideo="1"/></x:xmpmeta>""".toByteArray())
        assertEquals("1", fields["GCamera:MicroVideo"])
    }

    @Test
    fun `declaresMotionPhoto recognises each vendor's spelling`() {
        assertTrue(XmpFields.declaresMotionPhoto(XmpFields.parse("""<a GCamera:MotionPhoto="1"/>""".toByteArray())))
        assertTrue(XmpFields.declaresMotionPhoto(XmpFields.parse("""<a GCamera:MicroVideo="1"/>""".toByteArray())))
    }

    @Test
    fun `an element form of the marker is recognised too`() {
        assertTrue(XmpFields.declaresMotionPhoto(XmpFields.parse("""<GCamera:MotionPhoto>1</GCamera:MotionPhoto>""".toByteArray())))
    }

    @Test
    fun `a bare 0 is not a declaration`() {
        // Being present is not being set: `MotionPhoto="0"` is how a camera records that it
        // did *not* write a video into this file.
        assertTrue(!XmpFields.declaresMotionPhoto(XmpFields.parse("""<a GCamera:MotionPhoto="0"/>""".toByteArray())))
    }

    @Test
    fun `drops attributes that say nothing about motion photos`() {
        // A photo's packet is mostly unrelated; keeping it all would bloat every field map.
        val fields = XmpFields.parse(
            """<rdf:Description xmlns:dc="http://purl.org/dc/elements/1.1/" dc:creator="Someone"/>""".toByteArray(),
        )
        assertTrue(fields.isEmpty(), "unexpected keys: ${fields.keys}")
    }

    @Test
    fun `keeps an element value under both the qualified and bare name`() {
        // Readers look up one or the other depending on the vendor.
        val fields = XmpFields.parse(
            """<GCamera:MotionPhoto>1</GCamera:MotionPhoto>""".toByteArray(),
        )
        assertEquals("1", fields["GCamera:MotionPhoto"])
        assertEquals("1", fields["MotionPhoto"])
    }

    @Test
    fun `collapses repeated Item attributes into one comma-joined list`() {
        // Google's writer emits one Container:Directory per item, so the raw attribute repeats.
        val packet = """
            <Container:Directory>
              <rdf:Seq>
                <rdf:li rdf:parseType="Resource" Item:Semantic="Primary" Item:Length="0"/>
                <rdf:li rdf:parseType="Resource" Item:Semantic="MotionPhoto" Item:Length="8282540"/>
              </rdf:Seq>
            </Container:Directory>
        """.trimIndent()
        val fields = XmpFields.parse(packet.toByteArray())
        assertEquals("Primary, MotionPhoto", fields["Container Item Semantic"])
        assertEquals("0, 8282540", fields["Container Item Length"])
    }

    @Test
    fun `aliases the length list as GContainer ItemLength`() {
        val fields = XmpFields.parse(
            """<Container:Item Item:Length="7"/><Container:Item Item:Length="9"/>""".toByteArray(),
        )
        assertEquals("7, 9", fields["GContainer:ItemLength"])
    }

    @Test
    fun `unescapes the five XML entities`() {
        // The key has to contain something `isInteresting` recognises or it is dropped before
        // unescaping is reached, so these use the vendor-prefixed forms a camera actually emits.
        val fields = XmpFields.parse(
            """<rdf:Description GCamera:MotionPhoto="a&amp;b" GCamera:MicroVideo="&lt;t&gt;" GCamera:MicroVideoVersion="&quot;q&quot;" GCamera:MicroVideoOffset="&apos;s&apos;"/>""".toByteArray(),
        )
        assertEquals("a&b", fields["GCamera:MotionPhoto"])
        assertEquals("<t>", fields["GCamera:MicroVideo"])
        assertEquals("\"q\"", fields["GCamera:MicroVideoVersion"])
        assertEquals("'s'", fields["GCamera:MicroVideoOffset"])
    }

    @Test
    fun `container item attributes are unescaped on the way into the collapsed list`() {
        // Each attribute lands under its own suffix, so the join is per-attribute, not across
        // attributes of different names.
        val fields = XmpFields.parse(
            """<Container:Item Item:Mime="a&amp;b" Item:Semantic="&quot;q&quot;"/>""".toByteArray(),
        )
        assertEquals("a&b", fields["Container Item Mime"])
        assertEquals("\"q\"", fields["Container Item Semantic"])
    }

    @Test
    fun `an empty value is skipped for elements`() {
        val fields = XmpFields.parse("""<GCamera:MotionPhoto></GCamera:MotionPhoto>""".toByteArray())
        assertTrue(fields.isEmpty(), "unexpected keys: ${fields.keys}")
    }

    @Test
    fun `an unparseable payload yields an empty map rather than throwing`() {
        assertTrue(XmpFields.parse(ByteArray(0)).isEmpty())
        assertTrue(XmpFields.parse("not xml at all".toByteArray()).isEmpty())
    }

    @Test
    fun `accepts either quote style`() {
        val fields = XmpFields.parse("""<rdf:Description GCamera:MotionPhoto='1'/>""".toByteArray())
        assertEquals("1", fields["GCamera:MotionPhoto"])
    }
}

/** Building and rewriting the packet a merge writes. */
@DisplayName("MotionXmpBuilder / MotionXmpEditor")
class MotionXmpTest {

    @ParameterizedTest
    @EnumSource(MotionFormat::class, names = ["XIAOMI", "GOOGLE", "VIVO"])
    fun `every metadata-bearing format produces a well-formed packet`(format: MotionFormat) {
        val xml = MotionXmpBuilder.build(format, 8_282_540)!!.toString(Charsets.UTF_8)
        assertTrue(XmpIsWellFormed.parse(xml), "not well-formed: ${xml.take(300)}")
    }

    @ParameterizedTest
    @EnumSource(MotionFormat::class, names = ["HUAWEI", "MP4_SIGNATURE"])
    fun `formats that carry no metadata write no packet at all`(format: MotionFormat) {
        // The image bytes pass through untouched instead, so there is nothing to build.
        assertNull(MotionXmpBuilder.build(format, 5_000))
        assertTrue(MotionXmpBuilder.carriesNoMetadata(format))
    }

    @ParameterizedTest
    @EnumSource(MotionFormat::class, names = ["XIAOMI", "GOOGLE", "VIVO"])
    fun `each format declares its own markers`(format: MotionFormat) {
        val xml = MotionXmpBuilder.build(format, 1_234)!!.toString(Charsets.UTF_8)
        assertTrue(MotionXmpEditor.declaresMotion(xml), format.name)
    }

    @Test
    fun `xiaomi declares the offset and no directory`() {
        val xml = MotionXmpBuilder.build(MotionFormat.XIAOMI, 1_234)!!.toString(Charsets.UTF_8)
        // The length goes in MicroVideoOffset; MicroVideo itself is just the flag.
        assertTrue("GCamera:MicroVideo=\"1\"" in xml, xml.take(400))
        assertTrue("GCamera:MicroVideoOffset=\"1234\"" in xml, xml.take(400))
        assertTrue("<Container:Directory" !in xml, xml.take(400))
    }

    @Test
    fun `vivo declares both its own markers and a directory`() {
        val xml = MotionXmpBuilder.build(MotionFormat.VIVO, 1_234)!!.toString(Charsets.UTF_8)
        assertTrue("VCamera:VMotionPhotoVersion=\"1\"" in xml, xml.take(600))
        assertTrue("VCamera:VMediaKitVersion=" in xml)
        assertTrue("<Container:Directory" in xml)
        // Primary must precede MotionPhoto: the Swift reference reads the lengths as a pair.
        assertTrue(xml.indexOf("Item:Semantic=\"Primary\"") < xml.indexOf("Item:Semantic=\"MotionPhoto\""))
    }

    @Test
    fun `google declares a directory but no vivo markers`() {
        val xml = MotionXmpBuilder.build(MotionFormat.GOOGLE, 1_234)!!.toString(Charsets.UTF_8)
        assertTrue("GCamera:MotionPhoto=\"1\"" in xml)
        assertTrue("VCamera:" !in xml, xml.take(600))
    }

    @Test
    fun `every namespace the markup uses is declared`() {
        // An undeclared prefix makes the document invalid, and no reader can reach the
        // attributes at all — so this is checked per format rather than trusted.
        for (format in MotionFormat.entries) {
            val xml = MotionXmpBuilder.build(format, 1_234)?.toString(Charsets.UTF_8) ?: continue
            val declared = Regex("""xmlns:([A-Za-z_][\w.\-]*)\s*=""")
                .findAll(xml).map { it.groupValues[1] }.toSet()
            val used = Regex("""<([A-Za-z_][\w.\-]*):""")
                .findAll(xml).map { it.groupValues[1] }.toSet() +
                Regex("""\s([A-Za-z_][\w.\-]*):[A-Za-z_][\w.\-]*\s*=""")
                    .findAll(xml).map { it.groupValues[1] }.toSet()
            assertEquals(
                emptySet<String>(),
                used - declared - setOf("rdf", "x", "xmlns"),
                "$format uses undeclared prefixes: ${used - declared}",
            )
        }
    }

    @Test
    fun `stripMotion removes every announcement of a video`() {
        for (format in listOf(MotionFormat.XIAOMI, MotionFormat.GOOGLE, MotionFormat.VIVO)) {
            val merged = MotionXmpBuilder.build(format, 8_282_540)!!.toString(Charsets.UTF_8)
            val stripped = MotionXmpEditor.stripMotion(merged)
            assertTrue(!MotionXmpEditor.declaresMotion(stripped), "$format: ${stripped.take(300)}")
        }
    }

    @Test
    fun `stripMotion drops the extended-XMP reference either way`() {
        // The extended APP1 is not carried across, so a surviving reference would name a
        // document that is not in the file.
        val withRef = """<x:xmpmeta><rdf:Description xmpNote:HasExtendedXMP="GUID"/></x:xmpmeta>"""
        val stripped = MotionXmpEditor.stripMotion(withRef)
        assertTrue("HasExtendedXMP" !in stripped, stripped)
    }

    @Test
    fun `stripMotion leaves an unrelated gain map alone`() {
        // The gain map's bytes are still in the image, so its declaration has to survive.
        val packet = MotionXmpBuilder.build(MotionFormat.VIVO, 8_282_540)!!
            .toString(Charsets.UTF_8)
            .replace("</rdf:Seq>", """<rdf:li Item:Semantic="GainMap" Item:Length="507547"/></rdf:Seq>""")
        val stripped = MotionXmpEditor.stripMotion(packet)
        assertTrue("GainMap" in stripped, stripped.take(600))
    }

    @Test
    fun `stripMotion removes the video item but keeps Primary`() {
        val merged = MotionXmpBuilder.build(MotionFormat.VIVO, 8_282_540)!!.toString(Charsets.UTF_8)
        val stripped = MotionXmpEditor.stripMotion(merged)
        assertTrue("Item:Semantic=\"Primary\"" in stripped, stripped.take(600))
        assertTrue("Item:Semantic=\"MotionPhoto\"" !in stripped)
    }

    @Test
    fun `an emptied directory is dropped rather than left empty`() {
        val merged = MotionXmpBuilder.build(MotionFormat.VIVO, 8_282_540)!!.toString(Charsets.UTF_8)
        val stripped = MotionXmpEditor.stripMotion(merged)
        // Whatever remains must not be a Container:Directory with nothing in it.
        Regex("<Container:Directory>(.*?)</Container:Directory>", RegexOption.DOT_MATCHES_ALL)
            .findAll(stripped)
            .forEach { assertTrue("<Container:Item" in it.value, "empty directory left: ${it.value}") }
    }

    @Test
    fun `re-merging is idempotent in shape`() {
        // Merging an already-merged file must converge rather than stack up items.
        val once = MotionXmpEditor.addMotion(
            MotionXmpBuilder.build(MotionFormat.VIVO, 8_282_540)!!.toString(Charsets.UTF_8),
            MotionFormat.VIVO, 8_282_540,
        ).toString(Charsets.UTF_8)
        val twice = MotionXmpEditor.addMotion(once, MotionFormat.VIVO, 8_282_540).toString(Charsets.UTF_8)
        assertEquals(countOccurrences(once, "Item:Semantic=\"Primary\""), countOccurrences(twice, "Item:Semantic=\"Primary\""))
        assertEquals(countOccurrences(once, "Item:Semantic=\"MotionPhoto\""), countOccurrences(twice, "Item:Semantic=\"MotionPhoto\""))
        assertEquals(countOccurrences(once, "GCamera:MotionPhoto="), countOccurrences(twice, "GCamera:MotionPhoto="))
    }

    @Test
    fun `addMotion rewrites the declared length`() {
        val first = MotionXmpEditor.addMotion(
            MotionXmpBuilder.build(MotionFormat.VIVO, 100)!!.toString(Charsets.UTF_8),
            MotionFormat.VIVO, 200,
        ).toString(Charsets.UTF_8)
        assertTrue("Item:Length=\"200\"" in first, first.take(600))
        assertTrue("Item:Length=\"100\"" !in first, first.take(600))
    }

    @Test
    fun `addMotion adds the namespaces a bare packet lacks`() {
        // A packet that only declared a gain map has no GCamera prefix; the result must still
        // parse, or the attributes just added are unreachable.
        val bare = """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF><rdf:Description/></rdf:RDF></x:xmpmeta>"""
        val edited = MotionXmpEditor.addMotion(bare, MotionFormat.XIAOMI, 4242).toString(Charsets.UTF_8)
        assertTrue(XmpIsWellFormed.parse(edited), edited.take(400))
        assertTrue("GCamera:MicroVideoOffset=\"4242\"" in edited, edited.take(600))
    }

    @Test
    fun `declaresMotion is true for a bare marker and false for a plain packet`() {
        assertTrue(MotionXmpEditor.declaresMotion("""<rdf:Description GCamera:MicroVideo="1"/>"""))
        assertTrue(MotionXmpEditor.declaresMotion("""<rdf:li Item:Semantic="MotionPhoto"/>"""))
        assertTrue(!MotionXmpEditor.declaresMotion("""<rdf:Description dc:creator="Someone"/>"""))
    }

    @Test
    fun `stripMotion is safe on a packet with no markers`() {
        val plain = """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF/></x:xmpmeta>"""
        assertEquals(plain, MotionXmpEditor.stripMotion(plain))
    }

    private fun countOccurrences(haystack: String, needle: String): Int =
        Regex(Regex.escape(needle)).findAll(haystack).count()
}

/** Parses with the JDK's XML parser, so "well-formed" means what a reader would accept. */
private object XmpIsWellFormed {
    fun parse(xml: String): Boolean = try {
        javax.xml.parsers.DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(org.xml.sax.InputSource(java.io.StringReader(xml)))
        true
    } catch (e: Exception) {
        false
    }
}