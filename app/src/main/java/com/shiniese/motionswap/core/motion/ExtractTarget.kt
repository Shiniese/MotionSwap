package com.shiniese.motionswap.core.motion

/**
 * Which shape an extraction writes.
 *
 * A motion photo is one file with the video appended. Splitting it into a still and a
 * separate clip is what most vendors want, but vivo's gallery will only pair a still with
 * a clip it can match — which needs two vendor-specific blocks carrying a shared id that
 * the default split has no reason to write.
 */
enum class ExtractTarget(val displayName: String) {
    /** The still and the clip, byte-for-byte as they sit in the source. */
    PLAIN("原样"),

    /** The same split, plus the blocks that make vivo pair the two files. */
    VIVO_PAIR("vivo 配对"),
}