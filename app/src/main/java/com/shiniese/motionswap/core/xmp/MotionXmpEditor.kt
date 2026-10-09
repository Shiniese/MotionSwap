package com.shiniese.motionswap.core.xmp

import com.shiniese.motionswap.core.motion.MotionFormat

/**
 * Edits the XMP packet that is already in the image, rather than replacing it wholesale.
 *
 * Earlier revisions regenerated the packet from a template, which silently discarded
 * everything else the camera had written there. The HDR gain map is the case that bites:
 * its JPEG bytes stay in the file through a merge, so dropping its `Container:Item` leaves
 * an undeclared blob that vendors read as a corrupt file. Vendor namespaces such as
 * `MiCamera` are lost the same way.
 *
 * Both operations below therefore start from the packet on hand. Existing motion markers
 * are cleared first, so re-merging an already-merged image converges to a single video item
 * instead of stacking a second one.
 */
object MotionXmpEditor {

    private const val MOTION_SEMANTIC = "Item:Semantic=\"MotionPhoto\""

    /**
     * Points at the extended-XMP APP1 that follows the main packet. Whoever keeps only the
     * main packet has to drop this, or the metadata names a document that is not there.
     */
    private const val EXTENDED_XMP_REF = "xmpNote:HasExtendedXMP"
    private const val LIST_ITEM_OPEN = "<rdf:li"
    private const val LIST_ITEM_CLOSE = "</rdf:li>"

    private const val RDF_DESCRIPTION_OPEN = "<rdf:Description"
    private const val CONTAINER_ITEM_OPEN = "<Container:Item"
    private val RDF_SEQ_OPEN = "<rdf:Seq>".toRegex()
    private val RDF_DESCRIPTION_CLOSE = "</rdf:Description>".toRegex()
    private val RDF_SEQ_CLOSE = "</rdf:Seq>".toRegex()
    private val CONTAINER_DIRECTORY = Regex("<Container:Directory>.*?</Container:Directory>", RegexOption.DOT_MATCHES_ALL)

    /**
     * Removes every trace of an embedded video from [packet]: the camera attributes that
     * announce one, and the `Container:Item` that declares its length.
     *
     * Used when splitting a motion photo, so the still that is written out is an ordinary
     * photograph. Leaving the markers behind points galleries at a video that is not there,
     * which is worse than never having claimed one.
     *
     * Everything else survives — a gain map item, `hdrgm`, vendor namespaces — because the
     * bytes those describe are still in the image.
     */
    fun stripMotion(packet: String): String {
        var out = packet
        for (attribute in MotionXmpBuilder.motionAttributes()) {
            out = out.replace(attributeRegex(attribute), " ")
        }
        out = out.replace(attributeRegex(EXTENDED_XMP_REF), " ")
        out = removeMotionItem(out)
        // A directory whose only item was the video now describes nothing; drop it rather
        // than leave an empty sequence behind.
        if (!out.contains("<Container:Item")) {
            out = CONTAINER_DIRECTORY.replace(out, "")
        }
        return out
    }

    /**
     * Returns [packet] with a video of [videoLength] bytes declared for [format].
     *
     * The caller's fallback is [MotionXmpBuilder.build], for a source that carries no packet.
     */
    fun addMotion(
        packet: String,
        format: MotionFormat,
        videoLength: Long,
    ): ByteArray {
        var out = stripMotion(packet)
        out = insertNamespaces(out, MotionXmpBuilder.namespaces(format))
        val attributes = MotionXmpBuilder.motionAttributes(format, videoLength)
        if (attributes.isNotEmpty()) {
            out = insertIntoDescription(out, renderAttributes(attributes))
        }
        if (MotionXmpBuilder.usesDirectory(format)) {
            out = insertVideoItem(out, videoLength)
        }
        return out.toByteArray(Charsets.UTF_8)
    }

    /** True when [packet] announces an embedded video and should be stripped on extraction. */
    fun declaresMotion(packet: String): Boolean =
        MotionXmpBuilder.motionAttributes().any { attributeRegex(it).containsMatchIn(packet) } ||
            packet.contains(MOTION_SEMANTIC)

    /**
     * Deletes the `rdf:li` holding the video item. Items are not nested, so the enclosing
     * open and close tags are found by scanning rather than by tracking depth.
     */
    private fun removeMotionItem(packet: String): String {
        val semantic = packet.indexOf(MOTION_SEMANTIC)
        if (semantic < 0) return packet
        val open = packet.lastIndexOf(LIST_ITEM_OPEN, semantic)
        val close = packet.indexOf(LIST_ITEM_CLOSE, semantic)
        if (open < 0 || close < 0) return packet
        return packet.removeRange(open, close + LIST_ITEM_CLOSE.length)
    }

    /**
     * Appends the video item to the photo's directory, creating the directory when the packet
     * has none — a Xiaomi-style packet carries its length as a plain attribute instead.
     *
     * Either way the still has to be in there. A directory holding only the video parses as
     * valid XML, passes every structural check, and still plays as a plain JPEG: readers pair
     * the image and video entries and there is no image entry to pair with. That is exactly
     * how `samples/vivo_problem_pictures/Vivo_can_not_identify-MotionSwap_extrated_then_merged.jpg`
     * came to be a file vivo refuses and Xiaomi plays.
     */
    private fun insertVideoItem(packet: String, videoLength: Long): String {
        val video = MotionXmpBuilder.videoItemXml(videoLength)
        val seqClose = RDF_SEQ_CLOSE.find(packet)
        if (seqClose != null) {
            val at = seqClose.range.first
            return declarePrimary(packet.substring(0, at) + video + packet.substring(at))
        }
        val descriptionClose = RDF_DESCRIPTION_CLOSE.find(packet) ?: return packet
        val directory = "<Container:Directory><rdf:Seq>" +
            MotionXmpBuilder.primaryItemXml() + video +
            "</rdf:Seq></Container:Directory>"
        return packet.substring(0, descriptionClose.range.first) + directory +
            packet.substring(descriptionClose.range.first)
    }

    /**
     * Ensures the directory names the still, first in the sequence, and gives that entry the
     * `Item:Length`/`Item:Padding` the cameras write.
     *
     * A packet that already had a Primary keeps its own entry — only the length is filled in.
     * One that had none gets the canonical entry. Either path lands on the same shape, which
     * is the point: `samples/vivo_problem_pictures/Vivo_can_identify.jpg` and
     * `Vivo_can_not_identify.jpg` differ in almost everything and are recognised by opposite
     * galleries, but both carry exactly one Primary, ahead of the video.
     */
    private fun declarePrimary(packet: String): String {
        val declared = if (packet.contains(MotionXmpBuilder.PRIMARY_SEMANTIC)) {
            packet
        } else {
            val seqOpen = RDF_SEQ_OPEN.find(packet) ?: return packet
            val at = seqOpen.range.last + 1
            packet.substring(0, at) + MotionXmpBuilder.primaryItemXml() + packet.substring(at)
        }
        return normalizePrimary(declared)
    }

    /**
     * Fills in the still's missing `Item:Length`/`Item:Padding`.
     *
     * Only the Primary entry is touched: a gain map carries a real length
     * (`Item:Length="507547"` in `samples/VivoOld_novideo_contained.jpg`) and rewriting that
     * one would point readers at bytes that are not there.
     */
    private fun normalizePrimary(packet: String): String {
        val semantic = packet.indexOf(MotionXmpBuilder.PRIMARY_SEMANTIC)
        if (semantic < 0) return packet
        val start = packet.lastIndexOf(CONTAINER_ITEM_OPEN, semantic)
        if (start < 0) return packet
        val end = tagEnd(packet, start + CONTAINER_ITEM_OPEN.length) ?: return packet
        val tag = packet.substring(start, end + 1)
        val filled = when {
            tag.contains("Item:Length=") && tag.contains("Item:Padding=") -> return packet
            tag.contains("Item:Length=") -> insertAttribute(tag, "Item:Padding=\"0\"")
            tag.contains("Item:Padding=") -> insertAttribute(tag, "Item:Length=\"0\"")
            else -> insertAttribute(tag, "Item:Length=\"0\" Item:Padding=\"0\"")
        }
        return packet.substring(0, start) + filled + packet.substring(end + 1)
    }

    /**
     * Inserts [declaration] into an already-rendered tag, right after the element name so the
     * attributes stay inside the tag rather than becoming part of its name.
     */
    private fun insertAttribute(tag: String, declaration: String): String {
        val nameEnd = tag.indexOfFirst { it == ' ' || it == '/' || it == '>' }
            .let { if (it < 0) tag.length else it }
        return tag.substring(0, nameEnd) + " " + declaration + tag.substring(nameEnd)
    }

    /**
     * Adds attributes to the opening `rdf:Description` tag, converting a self-closing tag
     * into an open/close pair first since the new markup has to go inside it.
     */
    private fun insertIntoDescription(packet: String, addition: String): String {
        val end = descriptionTagEnd(packet) ?: return packet
        return if (packet[end - 1] == '/') {
            packet.substring(0, end - 1) + addition + "></rdf:Description>" +
                packet.substring(end + 1)
        } else {
            packet.substring(0, end) + addition + packet.substring(end)
        }
    }

    /**
     * Declares the namespaces the new markup uses, skipping any the packet already declares.
     * An unused prefix is noise every reader has to tolerate, so this only ever adds what is
     * missing.
     */
    private fun insertNamespaces(
        packet: String,
        namespaces: List<Pair<String, String>>,
    ): String {
        val missing = namespaces.filterNot { (prefix, _) -> packet.contains("xmlns:$prefix=") }
        if (missing.isEmpty()) return packet
        val declaration = missing.joinToString("") { (prefix, uri) ->
            " xmlns:$prefix=\"$uri\""
        }
        val end = descriptionTagEnd(packet) ?: return packet
        return if (packet[end - 1] == '/') {
            packet.substring(0, end - 1) + declaration + "/>" + packet.substring(end + 1)
        } else {
            packet.substring(0, end) + declaration + packet.substring(end)
        }
    }

    /**
     * Index of the `>` closing the opening `rdf:Description` tag, or null when the packet has
     * no such tag.
     */
    private fun descriptionTagEnd(packet: String): Int? {
        val start = packet.indexOf(RDF_DESCRIPTION_OPEN)
        if (start < 0) return null
        return tagEnd(packet, start + RDF_DESCRIPTION_OPEN.length)
    }

    /**
     * Index of the `>` closing a tag whose name ended at [from], or null when it never closes.
     *
     * The tag name alone is not the end of the tag — real packets put a dozen attributes
     * after it — so the scan runs to the `>` and steps over quoted values, which may legally
     * contain one.
     */
    private fun tagEnd(packet: String, from: Int): Int? {
        var index = from
        var quote: Char? = null
        while (index < packet.length) {
            val char = packet[index]
            when {
                quote != null -> if (char == quote) quote = null
                char == '"' || char == '\'' -> quote = char
                char == '>' -> return index
            }
            index++
        }
        return null
    }

    private fun renderAttributes(attributes: List<Pair<String, String>>): String =
        attributes.joinToString("") { (key, value) -> " $key=\"${escape(value)}\"" }

    private fun attributeRegex(attribute: String) =
        Regex("\\s+" + Regex.escape(attribute) + "=\"[^\"]*\"")

    private fun escape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}