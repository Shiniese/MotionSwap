package com.shiniese.motionswap.core.motion

import com.shiniese.motionswap.core.xmp.ByteSearch

/**
 * Locates an embedded MP4 track by scanning for an ISO-BMFF `ftyp` box.
 *
 * Vendor formats with no usable XMP (Huawei, and many unknown-brand files) still
 * embed a well-formed MP4 header, so the box itself is the signal. The box size is
 * read from the four bytes preceding the `ftyp` signature and sanity-checked: a real
 * `ftyp` for camera video is small and consistent, which rejects the many coincidental
 * `ftyp` byte sequences that appear inside compressed image data.
 *
 * The video sits at the *end* of the file, so the caller may hand over a window from
 * anywhere in the blob; [findVideoRegion] maps positions back to absolute file offsets
 * via [windowOffset]. Reading a head window is not enough on its own:
 * `samples/HUAWEI.jpeg` carries no XMP at all and its `ftyp` box starts at 7,843,538,
 * far beyond any fixed-size header probe.
 */
object Mp4BoxScanner {

    private val FTYP_SIGNATURE = byteArrayOf(0x66, 0x74, 0x79, 0x70)
    private val MP4_BRAND = "mp4".toByteArray(Charsets.US_ASCII)
    private const val MIN_BOX_SIZE = 16L
    private const val MAX_BOX_SIZE = 1024L

    data class VideoRegion(val offset: Long, val length: Long)

    /**
     * Returns the region running from the MP4 box to the end of the file, or null when
     * [data] holds no credible `ftyp` box.
     *
     * @param windowOffset absolute file offset that [data][0] corresponds to.
     */
    fun findVideoRegion(data: ByteArray, windowOffset: Long, totalSize: Long): VideoRegion? {
        var searchIndex = 0
        while (searchIndex < data.size - 8) {
            val ftypStart = ByteSearch.indexOf(data, FTYP_SIGNATURE, searchIndex)
            if (ftypStart < 0) return null
            searchIndex = ftypStart + FTYP_SIGNATURE.size

            val boxStart = ftypStart - 4
            if (boxStart < 0) continue

            val absolute = windowOffset + boxStart
            if (!isVideoBoxAt(data, boxStart)) continue

            val length = totalSize - absolute
            if (length <= 0) continue
            return VideoRegion(absolute, length)
        }
        return null
    }

    /**
     * True when a valid MP4 `ftyp` box begins at absolute file offset [absoluteOffset].
     *
     * Used to confirm a layout derived from XMP metadata, which is the only thing that
     * rejects a stale offset. A still image extracted out of a motion photo keeps the
     * original XMP packet, so `samples/WechatModified_extracted_image.jpg` still
     * advertises `MicroVideoOffset=2724081` while being only 5,756,045 bytes of JPEG;
     * trusting that number would carve 2.7MB of image data out as "video".
     *
     * @param windowOffset absolute file offset that [data][0] corresponds to.
     */
    fun isVideoStartAt(data: ByteArray, windowOffset: Long, absoluteOffset: Long): Boolean {
        val index = (absoluteOffset - windowOffset).toInt()
        if (index < 0 || index >= data.size) return false
        return isVideoBoxAt(data, index)
    }

    /** Validates the box header at [boxStart] inside [data]: plausible size plus `mp4` brand. */
    private fun isVideoBoxAt(data: ByteArray, boxStart: Int): Boolean {
        if (boxStart < 0 || boxStart + 4 > data.size) return false
        if (!ByteSearch.startsWith(data, FTYP_SIGNATURE, boxStart + 4)) return false

        val boxSize = ByteSearch.readUInt32BE(data, boxStart)
        if (boxSize < MIN_BOX_SIZE || boxSize > MAX_BOX_SIZE) return false

        val brandStart = boxStart + 8
        val brandEnd = minOf(brandStart + boxSize - 8, data.size.toLong()).toInt()
        if (brandEnd <= brandStart) return false
        return ByteSearch.contains(data.copyOfRange(brandStart, brandEnd), MP4_BRAND)
    }
}
