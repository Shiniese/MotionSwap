package com.shiniese.motionswap.core.model

/**
 * Byte layout of a motion photo after the embedded video has been located.
 *
 * Offsets are absolute positions in the original file; the image side is always
 * `[0, videoOffset)` and the video side is `[videoOffset, totalSize)`.
 */
data class MotionPhotoLayout(
    val videoOffset: Long,
    val videoLength: Long,
    val totalSize: Long,
) {
    val imageLength: Long get() = videoOffset

    fun isValid(): Boolean =
        videoOffset > 0 && videoLength > 0 && videoOffset + videoLength == totalSize

    companion object {
        fun of(videoOffset: Long, videoLength: Long): MotionPhotoLayout =
            MotionPhotoLayout(videoOffset, videoLength, videoOffset + videoLength)
    }
}

/** Where the video came from, used for user-facing progress detail. */
enum class MotionOrigin {
    XIAOMI,
    GOOGLE,
    HUAWEI,
    /**
     * vivo/iQOO, which uses Google's container plus its own `VCamera` markers.
     *
     * Kept separate from [GOOGLE] even though the bytes read the same way: the file's own
     * metadata says which gallery wrote it, and reporting a vivo photo as "Google / Pixel /
     * 三星" sends the user to the wrong merge target — writing a plain Google packet is
     * exactly what vivo's gallery refuses to play.
     */
    VIVO,
    MP4_SIGNATURE,
}

/** Result of identifying a file. */
sealed interface MotionProbe {
    data class Detected(
        val origin: MotionOrigin,
        val layout: MotionPhotoLayout,
        val presentationTimestampUs: Long?,
    ) : MotionProbe

    data object NotMotionPhoto : MotionProbe
}