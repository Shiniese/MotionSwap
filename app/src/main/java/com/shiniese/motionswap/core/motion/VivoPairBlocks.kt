package com.shiniese.motionswap.core.motion

import java.security.SecureRandom

/**
 * vivo's *pair* mode: a still and a separate MP4 that the gallery links together, as
 * opposed to the embedded mode in [VivoMediaTail] where the video rides inside the JPEG.
 *
 * The two modes share a box type — both trailers are a `uuid` box whose usertype is
 * `vivoMediaExtInfo` — but nothing else. The pair trailer is 359 bytes where the embedded
 * one is 293, it declares `version:2104` against `2200`, and it carries a per-photo id
 * that has to match the id written into the still. Neither one is a valid substitute for
 * the other, which is why this is a separate object rather than a second size on
 * [VivoMediaTail].
 *
 * Device-verified recipe, from `samples/vivo_pair_variants/` (see the design spec §5B):
 *
 * | variant | still DEGS | clip uuid box | result |
 * |---|---|---|---|
 * | A | no | no | refused |
 * | B | no | yes | refused |
 * | C | yes | yes | **accepted** |
 * | E | yes, clip `mdat` first | yes | **accepted** |
 * | F | yes | no | refused |
 * | D_hex | yes, id differs | yes | refused |
 * | G | yes, id matches, neutral strings | yes | **accepted** |
 *
 * So all three of these are required: the 587-byte block on the still, the 359-byte box on
 * the clip, and the two ids being equal. What is *not* required is anything else — E kept
 * the clip's original box order, and G replaced the camera's model strings, so neither
 * `moov` placement nor the identity strings are read.
 *
 * Both blocks are copied from the sample verbatim rather than generated. That is not
 * laziness: the JSON inside them is malformed — `com.android.camera.imageTime":46` has no
 * opening quote on its key — so it cannot be parsed and rebuilt, only patched. And G proved
 * vivo does not validate the strings in it. Only the id is substituted.
 */
object VivoPairBlocks {

    /** The trailing block a paired still carries, in place of its video. */
    const val DEGS_SIZE: Int = 587

    /** The trailing box a paired clip carries. */
    const val UUID_BOX_SIZE: Int = 359

    /** The id baked into [DEGS_TEMPLATE] and [UUID_TEMPLATE], standing in for a real one. */
    private val TEMPLATE_ID: ByteArray =
        "17909322347238ae839500000000".toByteArray(Charsets.US_ASCII)

    /** Both real samples are 28 lowercase hex characters, and only hex is proven to work. */
    const val ID_LENGTH: Int = 28

    private const val ID_RANDOM_HEX = 7

    private const val ID_TRAILING_ZEROS = 8

    private val random = SecureRandom()

    /**
     * A fresh id for one photo: epoch milliseconds, then random hex, then zero padding.
     *
     * The timestamp makes ids from a single batch distinct without coordination, and the
     * zero padding matches the shape both samples have. Neither end is checked by vivo —
     * an id starting `1800000000000` (a date in 2027) was accepted on device — so this
     * only has to be unique and hex.
     */
    fun newId(): ByteArray {
        val stamp = System.currentTimeMillis().toString()
        require(stamp.length <= ID_LENGTH - ID_RANDOM_HEX - ID_TRAILING_ZEROS) {
            "epoch millis no longer fits the id layout: $stamp"
        }
        val out = ByteArray(ID_LENGTH)
        var at = 0
        for (c in stamp) {
            out[at++] = c.code.toByte()
        }
        for (i in 0 until ID_RANDOM_HEX) {
            out[at++] = HEX[random.nextInt(HEX.size)].code.toByte()
        }
        while (at < ID_LENGTH) out[at++] = '0'.code.toByte()
        return out
    }

    /** [newId] shaped for the two block builders, which need it as a 28-char string. */
    fun newIdString(): String = String(newId(), Charsets.US_ASCII)

    /** [DEGS_TEMPLATE] with [liveId] written over its two id placeholders. */
    fun degs(liveId: ByteArray): ByteArray = patch(DEGS_TEMPLATE, liveId)

    /** [UUID_TEMPLATE] with [liveId] written over its two id placeholders. */
    fun uuidBox(liveId: ByteArray): ByteArray = patch(UUID_TEMPLATE, liveId)

    /**
     * Replaces both id occurrences without moving any byte.
     *
     * The substitution has to be length-preserving: the blocks carry big-endian length
     * fields for the run before each id, so a longer or shorter id would leave them
     * pointing at the wrong offset.
     */
    private fun patch(template: ByteArray, liveId: ByteArray): ByteArray {
        require(liveId.size == ID_LENGTH) { "id must be $ID_LENGTH bytes, was ${liveId.size}" }
        val out = template.copyOf()
        var replaced = 0
        var at = 0
        while (at <= out.size - ID_LENGTH) {
            if (matches(out, at, TEMPLATE_ID)) {
                liveId.copyInto(out, at)
                at += ID_LENGTH
                replaced++
            } else {
                at++
            }
        }
        check(replaced == 2) { "expected 2 id placeholders in the template, found $replaced" }
        return out
    }

    private fun matches(haystack: ByteArray, at: Int, needle: ByteArray): Boolean {
        for (i in needle.indices) if (haystack[at + i] != needle[i]) return false
        return true
    }

    private val HEX = "0123456789abcdef".toCharArray()

    private fun hexOf(hex: String): ByteArray {
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(hex[i * 2], 16)
            val lo = Character.digit(hex[i * 2 + 1], 16)
            require(hi >= 0 && lo >= 0) { "literal is not valid hex at offset ${i * 2}" }
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    // Taken byte-for-byte from `samples/VivoOld_novideo_contained.jpg` (587 bytes, starting
    // at `streamdataDEGS`) and `samples/VivoOld_novideo_contained.mp4` (its last 359
    // bytes). Both contain TEMPLATE_ID exactly twice; `patch` enforces that.
    private val DEGS_TEMPLATE = hexOf(
        "73747265616d64617461444547530002fe01fffffe02fffffe0300010000fe0400000000" +
            "fe0500000000fe0600000000fe0700001000fe0800000c00fe0a0000005efe0b0000005a" +
            "fe0c000000004445474573747265616d696e666f00060001060000004873747265616d63" +
            "6f756e7400017669766f7b22636f6d2e616e64726f69642e63616d6572612e6a6f696e74" +
            "2e66756c6c766965772e6f7269656e746174696f6e223a302c22636f6d2e616e64726f69" +
            "642e63616d6572612e66697368657965223a2d312c22636f6d2e616e64726f69642e6361" +
            "6d6572612e74616b656e6d6f64656c223a227669766f20583230302050726f222c22636f" +
            "6d2e616e64726f69642e63616d6572612e77617465726d61726b56657273696f6e223a6e" +
            "756c6c2c22636f6d2e616e64726f69642e63616d6572612e63616d657261666163696e67" +
            "223a2230222c22636f6d2e616e64726f69642e63616d6572612e6d6f64756c656964223a" +
            "2270686f746f222c22636f6d2e616e64726f69642e63616d6572612e6c69766570686f74" +
            "6f223a2231373930393332323334373233386165383339353030303030303030222c2276" +
            "657273696f6e223a323130342c22636f6d2e616e64726f69642e63616d6572612e70726f" +
            "6a656374223a22504432343035222c22636f6d2e616e64726f69642e63616d6572612e6a" +
            "6f696e742e66756c6c76696577223a66616c73657d0000019763616d6572616c62756d21" +
            "0000002f31373930393332323334373233386165383339353030303030303030ffffffff" +
            "1b2a39485766758493a2b3",
    )

    private val UUID_TEMPLATE = hexOf(
        "00000167757569647669766f4d65646961457874496e666f7669766f7b22636f6d2e616e" +
            "64726f69642e63616d6572612e74616b656e6d6f64656c223a227669766f205832303020" +
            "50726f222c22636f6d2e616e64726f69642e63616d6572612e63616d657261666163696e" +
            "67223a2230222c22636f6d2e616e64726f69642e63616d6572612e696d61676554696d65" +
            "223a34362c22636f6d2e616e64726f69642e63616d6572612e6d6f64756c656964223a22" +
            "70686f746f222c22636f6d2e616e64726f69642e63616d6572612e6c69766570686f746f" +
            "223a2231373930393332323334373233386165383339353030303030303030222c227665" +
            "7273696f6e223a323130342c22636f6d2e616e64726f69642e63616d6572612e66616365" +
            "496e666f223a7b7d7d0000010d63616d6572616c62756d210000002f3137393039333232" +
            "3334373233386165383339353030303030303030ffffffff1b2a39485766758493a2b3",
    )
}