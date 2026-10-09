package com.shiniese.motionswap

import java.io.File

/**
 * Reads JPEG structure out of the real samples, independently of the code under test.
 *
 * Deliberately a separate implementation rather than a call into `core/`: these helpers answer
 * "what is actually in this file" so a test can check what `core/` says against it. Sharing code
 * would make both wrong in the same direction and the comparison worthless.
 */
object Jpeg {

    private val XMP_ID = "http://ns.adobe.com/xap/1.0/".toByteArray(Charsets.US_ASCII) + byteArrayOf(0)
    private val EXIF_ID = "Exif".toByteArray(Charsets.US_ASCII) + byteArrayOf(0, 0)

    /** Offset just past the SOS marker: where entropy-coded data begins. */
    fun entropyStart(data: ByteArray): Int {
        var p = 2
        while (p + 4 <= data.size) {
            if ((data[p].toInt() and 0xFF) != 0xFF) break
            val marker = data[p + 1].toInt() and 0xFF
            if (marker == 0xDA) {
                val len = ((data[p + 2].toInt() and 0xFF) shl 8) or (data[p + 3].toInt() and 0xFF)
                return p + 2 + len
            }
            if (marker == 0xD9) break
            if (marker == 0x01 || marker in 0xD0..0xD8) {
                p += 2
                continue
            }
            val len = ((data[p + 2].toInt() and 0xFF) shl 8) or (data[p + 3].toInt() and 0xFF)
            if (len < 2) break
            p += 2 + len
        }
        return data.size
    }

    /**
     * Offset just past the first `FF D9` at or after [from].
     *
     * Scanned forward from the entropy-coded data rather than searched backwards, because
     * `Pixel.jpg` has 32 KB of vendor padding between the image and its video and the clip
     * itself contains `FF D9` pairs. A backwards search lands tens of megabytes too early.
     */
    fun endOfImage(data: ByteArray, from: Int = entropyStart(data)): Int {
        var i = from
        while (i + 1 < data.size) {
            if ((data[i].toInt() and 0xFF) == 0xFF && (data[i + 1].toInt() and 0xFF) == 0xD9) return i + 2
            i++
        }
        return -1
    }

    /** The marker segments before SOS, each including its two length bytes. */
    fun leadingSegments(data: ByteArray): List<ByteArray> {
        val segments = mutableListOf<ByteArray>()
        var p = 2
        while (p + 4 <= data.size) {
            if ((data[p].toInt() and 0xFF) != 0xFF) break
            val marker = data[p + 1].toInt() and 0xFF
            if (marker == 0xD8 || marker == 0x01 || marker in 0xD0..0xD7) {
                p += 2
                continue
            }
            if (marker == 0xDA) break
            val len = ((data[p + 2].toInt() and 0xFF) shl 8) or (data[p + 3].toInt() and 0xFF)
            if (len < 2) break
            segments += data.copyOfRange(p, p + 2 + len)
            p += 2 + len
        }
        return segments
    }

    /**
     * The APP1 payload that begins with the Adobe XMP identifier.
     *
     * Located the way a reader does rather than by searching raw bytes: cameras keep a *second*
     * copy of the packet in Exif tag 700, and that is a different document from the one the
     * merger edits.
     */
    fun app1Xmp(data: ByteArray): String? {
        val segment = leadingSegments(data).firstOrNull { segment ->
            segment.size > 4 + XMP_ID.size &&
                (segment[0].toInt() and 0xFF) == 0xFF &&
                (segment[1].toInt() and 0xFF) == 0xE1 &&
                regionMatches(segment, 4, XMP_ID)
        } ?: return null
        val from = 4 + XMP_ID.size
        return String(segment, from, segment.size - from, Charsets.UTF_8)
    }

    /** The APP1 Exif segment, including marker, length and identifier. */
    fun app1Exif(data: ByteArray): ByteArray? = leadingSegments(data).firstOrNull { segment ->
        segment.size > 10 &&
            (segment[0].toInt() and 0xFF) == 0xFF &&
            (segment[1].toInt() and 0xFF) == 0xE1 &&
            regionMatches(segment, 4, EXIF_ID)
    }

    /** The order the leading APP1 segments appear in, e.g. `Exif,XMP`. */
    fun app1Order(data: ByteArray): String = leadingSegments(data)
        .filter { (it[0].toInt() and 0xFF) == 0xFF && (it[1].toInt() and 0xFF) == 0xE1 }
        .joinToString(",") { if (regionMatches(it, 4, EXIF_ID)) "Exif" else "XMP" }

    /** The `Container:Item` entries in packet order, as (semantic, length) pairs. */
    fun containerItems(packet: String): List<Pair<String, String?>> =
        Regex("<Container:Item\\b[^>]*>").findAll(packet).map { match ->
            val tag = match.value
            val semantic = Regex("Item:Semantic=\"([^\"]*)\"").find(tag)?.groupValues?.get(1) ?: ""
            val length = Regex("Item:Length=\"([^\"]*)\"").find(tag)?.groupValues?.get(1)
            semantic to length
        }.toList()

    /** Parses with the JDK's XML parser, so "well-formed" means what a reader would accept. */
    fun isWellFormedXml(xml: String): Boolean = try {
        javax.xml.parsers.DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(org.xml.sax.InputSource(java.io.StringReader(xml)))
        true
    } catch (e: Exception) {
        false
    }

    private fun regionMatches(data: ByteArray, at: Int, needle: ByteArray): Boolean {
        if (at + needle.size > data.size) return false
        for (i in needle.indices) if (data[at + i] != needle[i]) return false
        return true
    }
}

/** Convenience for the tests that read one sample and do several things with it. */
fun sampleBytes(name: String): ByteArray = Samples.read(name)

/** The video region of a motion photo: everything from the MP4 start to the end of the file. */
fun videoRegionOf(data: ByteArray, videoStart: Int): ByteArray =
    data.copyOfRange(videoStart, data.size)

/**
 * Where the clip starts, found by scanning for `ftyp` and stepping back over its size field.
 *
 * Only meaningful for a source whose clip really is an MP4; null when there is none to find.
 */
fun findVideoStart(data: ByteArray): Int? {
    val ftyp = "ftyp".toByteArray(Charsets.US_ASCII)
    var i = Jpeg.entropyStart(data)
    while (i + 4 <= data.size) {
        if (regionEquals(data, i, ftyp)) return i - 4
        i++
    }
    return null
}

private fun regionEquals(data: ByteArray, at: Int, needle: ByteArray): Boolean {
    if (at < 0 || at + needle.size > data.size) return false
    for (i in needle.indices) if (data[at + i] != needle[i]) return false
    return true
}

/** A file under `samples/`, or a skipped test when this checkout has none. */
fun sampleFile(name: String): File {
    val file = Samples.file(name)
    org.junit.jupiter.api.Assumptions.assumeTrue(file != null, "sample not available: samples/$name")
    return file!!
}