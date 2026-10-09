package com.shiniese.motionswap.core.motion

import com.shiniese.motionswap.core.xmp.ByteSearch

/**
 * vivo's private trailer box, without which its gallery will not treat a file as a
 * motion photo even when its metadata describes one perfectly.
 *
 * This is the *embedded* mode: the MP4 rides inside the JPEG, and it has to end with this
 * box. vivo's *pair* mode — a separate clip matched to a separate still by a shared id —
 * is a different mechanism with a different box, in [VivoPairBlocks]. The two share a box
 * type, which is why extraction into a paired clip has to drop this one before appending
 * the other; leaving both would put two same-typed boxes carrying different ids in the
 * file, and vivo could match the stale one.
 *
 * Two requirements have to hold together. The earlier version of this comment blamed the
 * APP1 `Primary` entry for one of them, which is wrong: vivo reads the XMP out of
 * **Exif tag `0x02BC`**, not out of the APP1 segment. Written with that correction, the
 * samples in `samples/vivo_problem_pictures_variants/` are a clean 2x2:
 *
 * | file | Exif tag 700 | this box | vivo |
 * |---|---|---|---|
 * | `V1-tag700-from-good.jpg` | correct | yes | plays |
 * | `V2-app1-xmp-from-good.jpg` | wrong (APP1 right) | yes | refused |
 * | `V3-tag700-ok-no-tail.jpg` | correct | no | refused |
 * | `Vivo_can_not_identify-FIXED.jpg` | correct | yes | plays |
 *
 * Each failure misses exactly one requirement, which is what pins both as necessary rather
 * than merely correlated. `V2` is the one that corrected the diagnosis: its APP1 packet was
 * byte-identical to `V1`'s and it still failed, so the packet a photo already carried was
 * never what vivo was reading.
 *
 * The box goes missing for a mechanical reason. Xiaomi writes `GCamera:MicroVideoOffset`
 * and no trailer, so the MP4 in `Vivo_can_not_identify.jpg` is 293 bytes shorter than the
 * one in `Vivo_can_identify.jpg` — strip the trailer from the latter and the two are
 * byte-for-byte identical. The merger appends the video verbatim, so it faithfully
 * reproduced the absence.
 *
 * Payload is `vivo` followed by
 * `{"com.vivo.gallery.livePhoto.otherPhone.MotionRotationOffset":0,
 *   "com.vivo.gallery.livePhoto.otherPhone.MotionRotationCheck":1,
 *   "com.android.camera.livephoto":"motionphoto0000000easy_share","version":2200}`
 * and a binary tail naming `cameralbum` and `motionphoto0000000easy_share`.
 *
 * Reproduced byte-for-byte rather than generated: the box is identical
 * (md5 `6bd7abda23b1f3076967330fc26d2a4c`) across every sample that carries one,
 * including `samples/VivoNew.jpg`, whose video shares no bytes with these. Nothing in
 * it varies with the video, so there is nothing to compute — and copying the camera's
 * own bytes cannot invent a wrong value. Whether vivo reads these fields at all, or
 * merely requires the box to be present, is still untested; `V3` only shows it cannot do
 * without one.
 */
object VivoMediaTail {

    /** The complete 293-byte `uuid` box, including its size and type headers. */
    val BOX: ByteArray =
        hexOf(
            "00000125757569647669766f4d65646961457874496e666f7669766f7b22636f" +
                "6d2e7669766f2e67616c6c6572792e6c69766550686f746f2e6f746865725068" +
                "6f6e652e4d6f74696f6e526f746174696f6e4f6666736574223a302c22636f6d" +
                "2e7669766f2e67616c6c6572792e6c69766550686f746f2e6f7468657250686f" +
                "6e652e4d6f74696f6e526f746174696f6e436865636b223a312c22636f6d2e61" +
                "6e64726f69642e63616d6572612e6c69766570686f746f223a226d6f74696f6e" +
                "70686f746f30303030303030656173795f7368617265222c2276657273696f6e" +
                "223a323230307d000000cb63616d6572616c62756d210000002f6d6f74696f6e" +
                "70686f746f30303030303030656173795f7368617265ffffffff1b2a39485766" +
                "758493a2b3",
        )

    const val SIZE: Int = 293

    /** True when [video] already ends with the box, so a merge must not append a second. */
    fun endsWithTail(video: ByteArray): Boolean =
        video.size >= SIZE &&
            ByteSearch.startsWith(video, BOX, video.size - SIZE)

    private fun hexOf(hex: String): ByteArray {
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(hex[i * 2], 16)
            val lo = Character.digit(hex[i * 2 + 1], 16)
            require(hi >= 0 && lo >= 0) { "tail literal is not valid hex at offset ${i * 2}" }
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }
}