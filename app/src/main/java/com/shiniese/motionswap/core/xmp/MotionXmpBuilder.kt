package com.shiniese.motionswap.core.xmp

import com.shiniese.motionswap.core.motion.MotionFormat

/**
 * Describes the XMP each vendor's motion photo needs, and generates a packet from scratch
 * when the source image has none to edit.
 *
 * The shape of each packet is copied from real camera output rather than invented, because
 * the readers on the device side are unforgiving:
 *
 * - Xiaomi (`samples/XiaomiOld.jpg`) writes plain attributes on `rdf:Description` and no
 *   container block at all. Its tag is `GCamera:MicroVideoOffset`, the appended video's
 *   byte length.
 * - Google (`samples/Pixel.jpg`) writes the same camera attributes *plus* a
 *   `Container:Directory` holding two `Container:Item` entries, the second being the video
 *   with `Item:Length` equal to the video's byte length.
 * - vivo (`samples/VivoNew.jpg`) writes Google's packet **verbatim plus** two `VCamera:`
 *   attributes under `http://ns.vivo.com/photos/1.0/camera/`. Its gallery app gates on those
 *   and treats a packet without them as a plain JPEG, so a Google-targeted merge looks
 *   perfectly valid and still shows no motion. This is why vivo is a target of its own rather
 *   than an alias for Google.
 * - Huawei carries no XMP whatsoever, so there is nothing to build.
 *
 * The per-format knowledge lives in [motionAttributes], [usesDirectory] and [namespaces] so
 * that [MotionXmpEditor], which edits an existing packet rather than generating one, asks the
 * same questions and cannot drift from this file.
 *
 * Attribute names must be valid XML names. Earlier revisions emitted labels such as
 * `Motion Photo="1"`, and a space inside an attribute name makes the whole packet
 * unparseable — the merge still produced a file, but no gallery app could read it back.
 */
object MotionXmpBuilder {

    private const val MICRO_VIDEO_OFFSET = "GCamera:MicroVideoOffset"
    private const val MICRO_VIDEO = "GCamera:MicroVideo"
    private const val MICRO_VIDEO_VERSION = "GCamera:MicroVideoVersion"
    private const val MICRO_VIDEO_TIMESTAMP = "GCamera:MicroVideoPresentationTimestampUs"
    private const val MOTION_PHOTO = "GCamera:MotionPhoto"
    private const val MOTION_PHOTO_VERSION = "GCamera:MotionPhotoVersion"
    private const val MOTION_PHOTO_TIMESTAMP = "GCamera:MotionPhotoPresentationTimestampUs"

    /**
     * vivo's own version markers, verified against `samples/VivoNew.jpg`.
     *
     * `VMediaKitVersion` names the vivo media library that wrote the file, and it is the one
     * value here that could not be derived rather than copied — so it is pinned to the sample
     * instead of being incremented per release.
     */
    private const val V_MOTION_PHOTO_VERSION = "VCamera:VMotionPhotoVersion"
    private const val V_MEDIA_KIT_VERSION = "VCamera:VMediaKitVersion"
    private const val V_MEDIA_KIT_VERSION_VALUE = "1.0.0.9"

    /** The `Container:Item` attribute that names the still image. */
    const val PRIMARY_SEMANTIC = "Item:Semantic=\"Primary\""

    private const val PRIMARY_MIME = "image/jpeg"
    private const val VIDEO_MIME = "video/mp4"

    /**
     * The still's entry in a `Container:Directory`, as every camera writes it.
     *
     * Length zero is not a placeholder: the image is not a slice carved out of the file, it
     * is the whole file up to the video, so there is nothing to skip. `MotionSwap.swift`
     * depends on the entry being there — it reads the directory's lengths as an
     * image/video *pair* and refuses anything shorter than two — so a directory carrying only
     * the video cannot be read back as a motion photo at all.
     *
     * Shared with [MotionXmpEditor] on purpose. The two used to render this independently and
     * drifted: building a packet from scratch emitted the pair, while editing a packet that
     * had no directory emitted the video alone. The file still looked valid and no gallery
     * would play it.
     */
    fun primaryItemXml(): String =
        "<rdf:li rdf:parseType=\"Resource\">" +
            "<Container:Item Item:Mime=\"$PRIMARY_MIME\" $PRIMARY_SEMANTIC" +
            " Item:Length=\"0\" Item:Padding=\"0\"/>" +
            "</rdf:li>"

    /** The video's entry, paired with [primaryItemXml]. */
    fun videoItemXml(videoLength: Long): String =
        "<rdf:li rdf:parseType=\"Resource\">" +
            "<Container:Item Item:Mime=\"$VIDEO_MIME\" Item:Semantic=\"MotionPhoto\"" +
            " Item:Length=\"$videoLength\" Item:Padding=\"0\"/>" +
            "</rdf:li>"

    private const val NS_XMP = "adobe:ns:meta/"
    private const val NS_RDF = "http://www.w3.org/1999/02/22-rdf-syntax-ns#"
    private const val NS_GCAMERA = "http://ns.google.com/photos/1.0/camera/"
    private const val NS_VCAMERA = "http://ns.vivo.com/photos/1.0/camera/"
    private const val NS_CONTAINER = "http://ns.google.com/photos/1.0/container/"
    private const val NS_ITEM = "http://ns.google.com/photos/1.0/container/item/"

    /** U+FEFF, the byte order mark real camera packets put in the `xpacket begin` attribute. */
    private const val BOM = "\uFEFF"

    /**
     * Every attribute name that announces an embedded video, across all formats.
     *
     * Used to recognise a motion photo when splitting one, and to clear stale markers before
     * a new video is declared.
     */
    fun motionAttributes(): List<String> = listOf(
        MICRO_VIDEO_OFFSET,
        MICRO_VIDEO,
        MICRO_VIDEO_VERSION,
        MICRO_VIDEO_TIMESTAMP,
        MOTION_PHOTO,
        MOTION_PHOTO_VERSION,
        MOTION_PHOTO_TIMESTAMP,
        V_MOTION_PHOTO_VERSION,
        V_MEDIA_KIT_VERSION,
    )

    /**
     * The attributes [format] needs, with values. Empty when the format carries no metadata,
     * in which case the video is simply appended to the image bytes.
     */
    fun motionAttributes(format: MotionFormat, videoLength: Long): List<Pair<String, String>> =
        when (format) {
            MotionFormat.XIAOMI -> listOf(
                MICRO_VIDEO_VERSION to "1",
                MICRO_VIDEO to "1",
                MICRO_VIDEO_OFFSET to videoLength.toString(),
                MICRO_VIDEO_TIMESTAMP to "0",
            )

            MotionFormat.GOOGLE -> listOf(
                MOTION_PHOTO to "1",
                MOTION_PHOTO_VERSION to "1",
                MOTION_PHOTO_TIMESTAMP to "0",
            )

            // Google's attributes plus vivo's two markers, in the order `samples/VivoNew.jpg`
            // has them. Sharing the container is deliberate: the video still sits at the tail,
            // so reusing Google's reader on the result keeps read-back working without a second
            // implementation to keep in step.
            MotionFormat.VIVO -> listOf(
                MOTION_PHOTO to "1",
                MOTION_PHOTO_VERSION to "1",
                MOTION_PHOTO_TIMESTAMP to "0",
                V_MOTION_PHOTO_VERSION to "1",
                V_MEDIA_KIT_VERSION to V_MEDIA_KIT_VERSION_VALUE,
            )

            MotionFormat.HUAWEI, MotionFormat.MP4_SIGNATURE -> emptyList()
        }

    /** True when [format] declares its video as a `Container:Item` rather than an attribute. */
    fun usesDirectory(format: MotionFormat): Boolean = when (format) {
        MotionFormat.GOOGLE, MotionFormat.VIVO -> true
        MotionFormat.XIAOMI, MotionFormat.HUAWEI, MotionFormat.MP4_SIGNATURE -> false
    }

    /**
     * Namespaces [format]'s markup references, beyond `rdf` and `x`.
     *
     * `GCamera` is listed because every format's attributes live in it. A photo whose packet
     * omits the declaration — one written by a camera that only needed a gain map, say — needs
     * it adding, or the result is not well-formed and no reader can get at the attributes at
     * all.
     *
     * Declared before the attributes that use them, and only when a vendor needs them: an
     * unused namespace prefix is noise that every reader has to tolerate.
     */
    fun namespaces(format: MotionFormat): List<Pair<String, String>> {
        val vendor = when (format) {
            MotionFormat.VIVO -> listOf("VCamera" to NS_VCAMERA)
            else -> emptyList()
        }
        val container = if (usesDirectory(format)) {
            listOf("Container" to NS_CONTAINER, "Item" to NS_ITEM)
        } else {
            emptyList()
        }
        if (carriesNoMetadata(format)) return emptyList()
        return listOf("GCamera" to NS_GCAMERA) + vendor + container
    }

    /** True when [format] has no metadata to write and the image bytes pass through as-is. */
    fun carriesNoMetadata(format: MotionFormat): Boolean =
        motionAttributes(format, 0).isEmpty()

    /**
     * Returns the XMP payload to embed for [format], or null when the format carries
     * no metadata and the video is simply appended to the image bytes.
     */
    fun build(format: MotionFormat, videoLength: Long): ByteArray? {
        val attributes = motionAttributes(format, videoLength)
        if (attributes.isEmpty()) return null
        return packet(
            attributes = attributes,
            videoLength = videoLength,
            directory = usesDirectory(format),
            extraNamespaces = namespaces(format),
        )
    }

    private fun packet(
        attributes: List<Pair<String, String>>,
        videoLength: Long = 0,
        directory: Boolean,
        extraNamespaces: List<Pair<String, String>> = emptyList(),
    ): ByteArray = buildString {
        append("<?xpacket begin=\"").append(BOM).append("\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>")
        append("<x:xmpmeta xmlns:x=\"").append(NS_XMP).append("\">")
        append("<rdf:RDF xmlns:rdf=\"").append(NS_RDF).append("\">")
        append("<rdf:Description rdf:about=\"\"")

        // Every namespace declaration comes from one list. Writing `xmlns:GCamera` here *and*
        // letting extraNamespaces supply it produced the prefix twice on one element, which
        // makes the document invalid XML — a strict parser rejects the whole packet with
        // "attribute xmlns:GCamera was already specified for element rdf:Description", so
        // nothing downstream can read it. Deduplicating by prefix is what keeps a caller adding
        // a namespace from silently breaking the document.
        val declared = LinkedHashSet<String>()
        fun declare(prefix: String, uri: String) {
            if (declared.add(prefix)) append(" xmlns:").append(prefix).append("=\"").append(uri).append("\"")
        }

        declare("GCamera", NS_GCAMERA)
        extraNamespaces.forEach { (prefix, uri) -> declare(prefix, uri) }
        if (directory) {
            declare("Container", NS_CONTAINER)
            declare("Item", NS_ITEM)
        }

        attributes.forEach { (key, value) ->
            append(" ").append(key).append("=\"").append(escape(value)).append("\"")
        }
        append(">")

        if (directory) {
            append("<Container:Directory><rdf:Seq>")
            append(primaryItemXml())
            append(videoItemXml(videoLength))
            append("</rdf:Seq></Container:Directory>")
        }

        append("</rdf:Description>")
        append("</rdf:RDF></x:xmpmeta>")
        append("<?xpacket end=\"w\"?>")
    }.toByteArray(Charsets.UTF_8)

    private fun escape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}