package com.shiniese.motionswap.core.motion

import com.shiniese.motionswap.core.model.MotionOrigin

/**
 * Vendor container formats MotionSwap can read and write.
 *
 * Extraction order matters: Xiaomi first because its `MicroVideoOffset` is
 * unambiguous, then Google's container directives, then Huawei which is
 * identified by Make, and finally a signature scan that catches everything else.
 *
 * [shortName] and [displayName] are split because the two are used in places with very
 * different width budgets: a segmented button row gets [shortName], which must fit one line
 * without wrapping, while captions and task results get [displayName].
 * Rendering the long form in the button row made the Google segment wrap onto two lines
 * and grow taller than its neighbours.
 */
enum class MotionFormat(
    val origin: MotionOrigin,
    val displayName: String,
    val shortName: String,
) {
    XIAOMI(MotionOrigin.XIAOMI, "小米", "小米"),
    GOOGLE(MotionOrigin.GOOGLE, "Google / Pixel / 三星", "Google"),
    HUAWEI(MotionOrigin.HUAWEI, "华为", "华为"),
    VIVO(MotionOrigin.VIVO, "vivo / iQOO", "vivo"),
    MP4_SIGNATURE(MotionOrigin.MP4_SIGNATURE, "未知 (MP4 特征)", "未知"),
    ;

    companion object {
        val mergeTargets: List<MotionFormat> = listOf(XIAOMI, GOOGLE, HUAWEI, VIVO)
    }
}