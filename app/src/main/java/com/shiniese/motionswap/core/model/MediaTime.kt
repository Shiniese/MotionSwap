package com.shiniese.motionswap.core.model

/**
 * Decides whether a MediaStore capture timestamp can be believed.
 *
 * `DATE_TAKEN` is the column the sibling match relies on, and it is unreliable for exactly the
 * files this app cares about: a camera-app video is a MediaStore row whose `DATE_TAKEN` the
 * scanner frequently could not extract, so it reads back as `0`. Some devices instead store it in
 * seconds-since-1601, which surfaces as a value around `9e12`. Both are plausible-looking longs,
 * so a `> 0` test accepts them and the comparison silently becomes meaningless — every candidate
 * ends up equidistant from the image and no pairing can be decided.
 *
 * Rather than trusting the column, this treats it as one input among several and keeps the
 * better one: a plausible `DATE_TAKEN` wins, otherwise the scanner's own `DATE_ADDED` is used
 * (seconds since the epoch, always populated because the scanner had to write it to index the
 * file). `0` means "no usable time" and every candidate then ties, which the matcher reports as
 * undeterminable instead of guessing.
 *
 * Pure and Android-free so the boundary behaviour is verifiable without a device.
 */
object MediaTime {

    /** 2000-01-01T00:00:00Z. Anything earlier is not a photo or video anyone shot. */
    private const val EARLIEST_REAL_MILLIS = 946_684_800_000L

    /** 2100-01-01T00:00:00Z, comfortably past the seconds-since-1601 sentinel (`~9e12`). */
    private const val LATEST_REAL_MILLIS = 4_102_444_800_000L

    /**
     * Whether [millisSinceEpoch] is a capture time a real camera could have written.
     *
     * The window is deliberately wide and only excludes the two shapes of garbage seen in
     * practice — zero/absent and the 1601 epoch — rather than trying to be clever about
     * timezone or clock skew, neither of which moves a timestamp outside this range.
     */
    fun isPlausible(millisSinceEpoch: Long): Boolean =
        millisSinceEpoch in EARLIEST_REAL_MILLIS..LATEST_REAL_MILLIS

    /**
     * The capture time to use for pairing, given what MediaStore reported.
     *
     * @param dateTakenMillis `DATE_TAKEN` in milliseconds since the epoch, or `0`/garbage.
     * @param dateAddedMillis `DATE_ADDED`, which is in **seconds** since the epoch.
     * @return milliseconds since the epoch, or `0` when neither column is usable.
     */
    fun resolve(dateTakenMillis: Long, dateAddedMillis: Long): Long =
        when {
            isPlausible(dateTakenMillis) -> dateTakenMillis
            // DATE_ADDED counts in seconds, so it is scaled rather than compared as-is.
            dateAddedMillis > 0 -> dateAddedMillis * 1000L
            else -> 0L
        }
}