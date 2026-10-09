package com.shiniese.motionswap.core.xmp

/**
 * Pulls motion-photo-relevant XMP attributes out of a raw XMP payload.
 *
 * Motion photo XMP is written by many vendors with wildly different shapes:
 * some use fully-qualified attribute names (`GCamera:MotionPhoto="1"`), some use
 * bare element names inside a `Container:Directory` block, and some put the same
 * values in both places. This normalizes all of them into one flat string map so
 * the extractors can stay simple.
 *
 * Only keys relevant to motion photos are retained; everything else is discarded.
 */
object XmpFields {

    private val INTERESTING_SUBSTRINGS = listOf(
        "MotionPhoto",
        "MicroVideo",
        "ItemLength",
        "ItemPadding",
        "ItemMime",
        "ItemSemantic",
        "PresentationTimestampUs",
        "Container Item",
    )

    private const val CONTAINER_ITEM_PREFIX = "Container Item"
    private val CONTAINER_ATTRIBUTE_PREFIX = "Item:"

    /** Parses [payload] into a normalized key-value map. Returns an empty map when unparseable. */
    fun parse(payload: ByteArray): Map<String, String> {
        val text = String(XmpPacket.normalize(payload), Charsets.UTF_8)
        val result = LinkedHashMap<String, String>()
        val collected = LinkedHashMap<String, MutableList<String>>()

        scanAttributes(text, result)
        scanElements(text, result)
        collapseContainerAttributes(text, result, collected)

        return result
    }

    /** True when the payload declares itself a motion photo. */
    fun declaresMotionPhoto(fields: Map<String, String>): Boolean =
        fields["GCamera:MotionPhoto"] == "1" ||
            fields["GCamera:MicroVideo"] == "1" ||
            fields["Motion Photo"] == "1"

    private fun scanAttributes(text: String, out: MutableMap<String, String>) {
        for ((key, value) in ATTRIBUTE_PATTERN.findAll(text).mapNotNull(::toPair)) {
            if (!isInteresting(key)) continue
            out[key] = value
        }
    }

    private fun scanElements(text: String, out: MutableMap<String, String>) {
        for (match in ELEMENT_PATTERN.findAll(text)) {
            val (rawName, value) = match.destructured
            if (value.isBlank()) continue
            if (!isInteresting(rawName) && !isInteresting(rawName.substringAfterLast(':'))) continue
            // Keep both spellings: readers look up either the qualified or bare name,
            // and vendors differ on which form they emit.
            out[rawName] = value
            out[rawName.substringAfterLast(':')] = value
        }
    }

    /**
     * Google's writer emits one `Container:Directory` per item with repeated
     * `Item:*` attributes, while others emit a single whitespace-separated list.
     * Collapse both into `"a, b"` form under both key spellings.
     */
    private fun collapseContainerAttributes(
        text: String,
        out: MutableMap<String, String>,
        collected: MutableMap<String, MutableList<String>>,
    ) {
        for ((key, value) in ATTRIBUTE_PATTERN.findAll(text).mapNotNull(::toPair)) {
            if (!key.startsWith(CONTAINER_ATTRIBUTE_PREFIX)) continue
            val suffix = key.removePrefix(CONTAINER_ATTRIBUTE_PREFIX)
            collected.getOrPut("$CONTAINER_ITEM_PREFIX $suffix") { mutableListOf() } += value
        }
        for ((key, values) in collected) {
            if (values.isEmpty()) continue
            val joined = values.joinToString(", ")
            out[key] = joined
            if (key == "$CONTAINER_ITEM_PREFIX Length") {
                out["GContainer:ItemLength"] = joined
            }
        }
    }

    private fun isInteresting(key: String): Boolean =
        INTERESTING_SUBSTRINGS.any { key.contains(it, ignoreCase = true) }

    private fun toPair(match: MatchResult): Pair<String, String>? {
        val key = match.groupValues[1].trim()
        val value = match.groupValues[2].trim()
        if (key.isEmpty()) return null
        return key to unescape(value)
    }

    private fun unescape(value: String): String = value
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&amp;", "&")

    private val ATTRIBUTE_PATTERN =
        Regex("""([A-Za-z_][\w.\-]*(?::[A-Za-z_][\w.\-]*)+)\s*=\s*["']([^"']*)["']""")

    private val ELEMENT_PATTERN =
        Regex("""<([A-Za-z_][\w.\-]*(?::[A-Za-z_][\w.\-]*)?)[^>]*>([^<]*)</\1>""")
}