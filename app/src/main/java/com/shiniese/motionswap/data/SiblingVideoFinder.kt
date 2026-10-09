package com.shiniese.motionswap.data

import android.content.ContentResolver
import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore
import com.shiniese.motionswap.core.model.MediaTime
import com.shiniese.motionswap.core.model.SiblingMatch
import com.shiniese.motionswap.core.model.StillRef
import com.shiniese.motionswap.core.model.VideoCandidate

/** A video file that pairs with a still image. */
data class SiblingVideo(
    val uri: Uri,
    val displayName: String,
    val relativePath: String,
    val sizeBytes: Long,
    val dateTakenMillis: Long,
)

/** Outcome of a sibling lookup: the video, plus whether the miss needs explaining. */
data class SiblingLookup(
    val video: SiblingVideo?,
    /** Same-named videos exist in more than one folder, so none can be trusted. */
    val ambiguous: Boolean,
)

/**
 * Finds the video that belongs to a still image.
 *
 * The pairing rule is "same folder, same base name", which is how camera apps that store
 * motion photos as two files name their outputs. When several videos share the folder and
 * the name, the one closest in capture time wins, which disambiguates re-saves.
 *
 * Querying [MediaStore.Video] needs `READ_MEDIA_VIDEO` (API 33+) or
 * `READ_EXTERNAL_STORAGE` (API 30-32). That permission is *separate* from
 * `READ_MEDIA_IMAGES`: images access does not open the video collection, so without it every
 * query returns zero rows and merge silently finds no sibling for any input.
 */
class SiblingVideoFinder(private val resolver: ContentResolver) {

    private val collection: Uri = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)

    private val acceptedExtensions = listOf("mp4", "mov", "m4v", "3gp", "mkv")

    /**
     * Locates the sibling video for [image].
     *
     * One query, then a pure decision: candidates are fetched by name alone and the folder is
     * filtered by [SiblingMatch] rather than in SQL. `RELATIVE_PATH = ?` is an exact string
     * comparison, so constraining the folder there would silently miss rows recorded as
     * `Camera/` when the image reports `Camera` — and `SiblingMatch` already normalises the
     * trailing slash. It also means a known-folder image with no video there simply gets no
     * candidates rather than reaching for a same-named video from another roll, which would be
     * a confident wrong answer.
     */
    fun resolve(image: MediaImage): SiblingLookup {
        if (image.baseName.isBlank()) return SiblingLookup(null, ambiguous = false)

        val candidates = query(image.baseName)
        val rows = candidates.map { VideoCandidate(it.relativePath, it.dateTakenMillis) }
        val still = StillRef(image.relativePath, image.dateTakenMillis)

        // The winner is carried back by index: two rows can share a folder and timestamp, and
        // a field-equality lookup would return whichever came first.
        val index = SiblingMatch.pickIndex(still, rows)

        // "Several folders hold this name" is the actionable reason for a miss: the fix is to
        // import by album, which always knows the folder. Without the folder it cannot be
        // determined by SQL, so it is not reported here.
        val ambiguous = index == null &&
            image.relativePath.isNotBlank() &&
            candidates.map { it.relativePath }.distinct().size > 1

        // With no folder the name no longer narrows anything, so a miss here means the capture
        // times did not separate the candidates either — which is a real tie, and reporting it
        // as such is what tells the user to import by album instead of seeing "not found".
        val tieWithoutFolder = index == null &&
            image.relativePath.isBlank() &&
            candidates.size > 1

        return SiblingLookup(index?.let { candidates[it] }, ambiguous || tieWithoutFolder)
    }

    fun find(image: MediaImage): SiblingVideo? = resolve(image).video

    /**
     * Queries videos whose base name matches.
     *
     * The name filter runs twice on purpose. SQL does a `LIKE` prefilter to avoid scanning
     * every row, but `LIKE` on a raw file name would treat `%` and `_` in the name as
     * wildcards, so those are escaped; and `LIKE 'IMG_1.%'` also matches `IMG_10.mp4`, so
     * every survivor is then confirmed with an exact base-name comparison. Both helpers live
     * in `SiblingMatch` so the rule is verifiable without a device.
     */
    private fun query(baseName: String): List<SiblingVideo> {
        val results = mutableListOf<SiblingVideo>()

        val selection =
            "${MediaStore.Video.Media.DISPLAY_NAME} LIKE ? ESCAPE '\\'"
        val args = arrayOf(SiblingMatch.likePatternFor(baseName))

        resolver.query(collection, PROJECTION, selection, args, null)
            ?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val pathIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.RELATIVE_PATH)
                val sizeIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val dateIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_TAKEN)
                val addedIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameIndex) ?: continue
                    if (!SiblingMatch.matchesBaseName(name, baseName)) continue
                    if (extensionOf(name) !in acceptedExtensions) continue
                    val id = cursor.getLong(idIndex)
                    results += SiblingVideo(
                        uri = ContentUris.withAppendedId(collection, id),
                        displayName = name,
                        relativePath = cursor.getString(pathIndex) ?: "",
                        sizeBytes = cursor.getLong(sizeIndex),
                        // A camera-app video often has no usable DATE_TAKEN at all, and a row
                        // full of zeroes makes every candidate equidistant from the image, so
                        // nothing pairs. DATE_ADDED is what the scanner actually wrote, so it
                        // stands in rather than the comparison being given up on.
                        dateTakenMillis = MediaTime.resolve(
                            dateTakenMillis = cursor.getLong(dateIndex),
                            dateAddedMillis = cursor.getLong(addedIndex),
                        ),
                    )
                }
            }
        return results
    }

    private fun extensionOf(displayName: String): String =
        displayName.substringAfterLast('.', "").lowercase()

    private companion object {
        val PROJECTION = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.RELATIVE_PATH,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_TAKEN,
            MediaStore.Video.Media.DATE_ADDED,
        )
    }
}
