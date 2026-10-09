package com.shiniese.motionswap.data

import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore
import com.shiniese.motionswap.core.model.MediaTime
import com.shiniese.motionswap.core.model.SiblingName

/** A single photo in MediaStore, carrying the fields both features need. */
data class MediaImage(
    val uri: Uri,
    val id: Long,
    val displayName: String,
    val relativePath: String,
    val bucketName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val dateTakenMillis: Long,
    /** `DATE_ADDED` in **seconds** since the epoch, kept to stand in for a missing `DATE_TAKEN`. */
    val dateAddedMillis: Long = 0L,
) {
    /** File name with its extension removed, used to find the sibling video. */
    val baseName: String
        get() = displayName.substringBeforeLast('.', displayName)

    val extension: String
        get() = displayName.substringAfterLast('.', "").lowercase()

    val isJpegLike: Boolean
        get() = extension in setOf("jpg", "jpeg")

    val isHeicLike: Boolean
        get() = extension in setOf("heic", "heif", "avif")
}

/** One camera roll grouping, shown in the album picker. */
data class Album(
    val bucketName: String,
    val relativePath: String,
    val count: Int,
)

/**
 * Reads photos and albums out of MediaStore.
 *
 * Two access paths, and they behave differently:
 *
 * - Photos chosen through the system photo picker arrive as granted URIs. Android 14+
 *   hands out `content://media/picker/...` URIs, whose authority is *not* the images
 *   collection, so looking the media id up in `MediaStore.Images` finds nothing even
 *   though the app may read the picked item. Those URIs are therefore queried directly.
 * - Album listings need to enumerate the whole images collection, which requires
 *   `READ_MEDIA_IMAGES` (API 33+) or `READ_EXTERNAL_STORAGE` (API 30-32). Without the
 *   grant the query returns no rows at all, which surfaces as an empty album list.
 */
class MediaStoreImageSource(private val resolver: android.content.ContentResolver) {

    private val collection: Uri = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)

    fun loadAlbums(): List<Album> {
        val projection = arrayOf(
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Images.Media.RELATIVE_PATH,
        )
        val counts = LinkedHashMap<String, Pair<String, Int>>()
        resolver.query(
            collection,
            projection,
            null,
            null,
            "${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} ASC",
        )?.use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            val pathIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)
            while (cursor.moveToNext()) {
                val bucket = cursor.getString(nameIndex) ?: continue
                val path = cursor.getString(pathIndex) ?: continue
                val existing = counts[bucket]
                counts[bucket] = path to ((existing?.second ?: 0) + 1)
            }
        }
        return counts.map { (bucket, value) ->
            Album(bucket, value.first, value.second)
        }.sortedByDescending { it.count }
    }

    fun loadAlbum(album: Album): List<MediaImage> = query(
        selection = "${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} = ?",
        selectionArgs = arrayOf(album.bucketName),
    )

    fun loadByUris(uris: List<Uri>): List<MediaImage> = uris.mapNotNull { resolve(it) }

    /**
     * Turns a granted URI into a [MediaImage].
     *
     * Queries the URI itself first: the photo picker grants access to that exact document,
     * so its name and size are readable even when the same media id is invisible through the
     * images collection. Falls back to an id lookup for callers that hand us a plain
     * collection URI.
     *
     * A photo-picked image is then *enriched*: the picker projection does not reliably
     * include `RELATIVE_PATH` or `BUCKET_DISPLAY_NAME`, and merge needs the folder to locate
     * the sibling video. The picker URI does carry the numeric media id, so the real
     * MediaStore row is looked up by that id to recover the folder. This enrichment only
     * succeeds when the app may read that row — via album access, or via
     * `READ_MEDIA_VISUAL_USER_SELECTED` on Android 14+ — but when it does, picker-selected
     * images become pairable exactly like album-selected ones.
     */
    private fun resolve(uri: Uri): MediaImage? {
        val direct = queryUri(uri)
        if (direct == null) {
            // Plain collection URI, so the id lookup is all that is needed.
            val id = runCatching { ContentUris.parseId(uri) }.getOrNull() ?: return null
            return query("${MediaStore.Images.Media._ID} = $id", emptyArray(), limit = "1").firstOrNull()
        }

        val id = direct.id.takeIf { it > 0 } ?: mediaIdOf(uri)

        // The picker row's own DISPLAY_NAME is not necessarily the file's real name, and the
        // sibling search keys off exactly that string — so the collection row is treated as the
        // authority on the name, not just on the folder. Recovering the folder while keeping the
        // picker's name left the lookup querying `LIKE '<wrong name>.%'`, which matches no
        // candidate at all; that is why album import paired correctly and the picker never did.
        val row = if (id != null) {
            runCatching {
                query("${MediaStore.Images.Media._ID} = $id", emptyArray(), limit = "1").firstOrNull()
            }.getOrNull()
        } else {
            null
        }

        val name = SiblingName.authoritative(direct.displayName, row?.displayName)
        val folder = row?.relativePath?.ifBlank { direct.relativePath } ?: direct.relativePath
        val recovered = direct.copy(
            id = id ?: direct.id,
            displayName = name,
            relativePath = folder,
            bucketName = row?.bucketName?.ifBlank { direct.bucketName } ?: direct.bucketName,
            mimeType = row?.mimeType?.ifBlank { direct.mimeType } ?: direct.mimeType,
            // A collection row reporting zero bytes means it withheld the size, not that the
            // file is empty, so the picker's reading is kept rather than overwritten with 0.
            sizeBytes = if ((row?.sizeBytes ?: 0L) > 0) row!!.sizeBytes else direct.sizeBytes,
            // Both sides of the comparison go through MediaTime, so an image row missing its
            // DATE_TAKEN is not compared against a video row that has one — that mismatch would
            // make the nearest-candidate fallback meaningless.
            dateTakenMillis = MediaTime.resolve(
                dateTakenMillis = maxOf(direct.dateTakenMillis, row?.dateTakenMillis ?: 0L),
                dateAddedMillis = maxOf(direct.dateAddedMillis, row?.dateAddedMillis ?: 0L),
            ),
        )
        return recovered.withRealNameRecovered()
    }

    /**
     * Recovers the real name and folder for a picker row whose own name cannot be trusted.
     *
     * Evidence sources are tried in order of how much they actually pin down: media id, then
     * `DISPLAY_NAME`, then a size-plus-time fingerprint. None of them is assumed good — the
     * same distrust that makes the motion-photo reader verify the XMP against the real bytes
     * rather than believing the metadata outright.
     *
     * A recovered name is only accepted when the rows found agree on it, for the same reason
     * duplicate names across folders are not guessed between: a confidently-wrong file name
     * silently pairs the wrong video and still produces a file that opens fine.
     */
    private fun MediaImage.withRealNameRecovered(): MediaImage {
        if (SiblingName.isUsableFileName(displayName)) return this

        // The picker gave no usable name, so the name it was asked for cannot be the lookup key.
        // `SIZE` plus a capture time is what is left: a photo of a given size taken at a given
        // moment is the only fingerprint here that a placeholder name cannot fake.
        if (sizeBytes <= 0) return this
        val candidates = runCatching {
            query("${MediaStore.Images.Media.SIZE} = ?", arrayOf(sizeBytes.toString()))
        }.getOrNull().orEmpty()
        if (candidates.isEmpty()) return this

        val near = candidates.filter { row ->
            row.dateTakenMillis <= 0 || dateTakenMillis <= 0 ||
                kotlin.math.abs(row.dateTakenMillis - dateTakenMillis) <= SAME_SHOT_WINDOW_MILLIS
        }
        val narrowed = near.ifEmpty { candidates }
        val names = narrowed.map { it.displayName }.filter { SiblingName.isUsableFileName(it) }.toSet()
        if (names.size != 1) return this

        val match = narrowed.first { it.displayName == names.single() }
        return match.copy(
            // Keep the picker's granted URI: it is the only one the app is permitted to open,
            // and reading the bytes later goes through it rather than through the collection.
            uri = uri,
            relativePath = match.relativePath,
            bucketName = match.bucketName,
        )
    }

    /**
     * Whether a string could be the name of a real photo on disk.
     *
     * A picker row backed by cloud content, or one the provider chose not to identify, reports
     * a placeholder such as `image_42` or a bare id — no extension at all. This app only ever
     * merges JPEGs, so a name with no extension is evidence the name is synthetic rather than
     * the file being exotic, which is what makes it safe to go looking for the real one.
     */


    /**
     * Pulls the numeric media id out of a picker URI.
     *
     * Android 13+ returns URIs shaped like
     * `content://media/picker/0/com.android.providers.media.photopicker/media/12345`.
     * `ContentUris.parseId` expects the id to be the whole last segment, which it is here,
     * but older picker builds used a nested path — so fall back to the last numeric segment.
     */
    private fun mediaIdOf(uri: Uri): Long? {
        val segment = uri.lastPathSegment ?: return null
        return segment.toLongOrNull()
            ?: uri.pathSegments.lastOrNull { it.toLongOrNull() != null }?.toLongOrNull()
    }

    private fun queryUri(uri: Uri): MediaImage? = try {
        resolver.query(uri, PROJECTION, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            // Picker URIs omit _ID, so it is read defensively rather than by index.
            val id = cursor.getLongOrNull(MediaStore.Images.Media._ID) ?: 0L
            val name = cursor.getStringOrNull(MediaStore.Images.Media.DISPLAY_NAME)
                ?: return@use null
            MediaImage(
                uri = uri,
                id = id,
                displayName = name,
                relativePath = cursor.getStringOrNull(MediaStore.Images.Media.RELATIVE_PATH).orEmpty(),
                bucketName = cursor.getStringOrNull(MediaStore.Images.Media.BUCKET_DISPLAY_NAME).orEmpty(),
                mimeType = cursor.getStringOrNull(MediaStore.Images.Media.MIME_TYPE).orEmpty(),
                sizeBytes = cursor.getLongOrNull(MediaStore.Images.Media.SIZE) ?: 0L,
                dateAddedMillis = cursor.getLongOrNull(MediaStore.Images.Media.DATE_ADDED) ?: 0L,
                dateTakenMillis = MediaTime.resolve(
                    dateTakenMillis = cursor.getLongOrNull(MediaStore.Images.Media.DATE_TAKEN) ?: 0L,
                    dateAddedMillis = cursor.getLongOrNull(MediaStore.Images.Media.DATE_ADDED) ?: 0L,
                ),
            )
        }
    } catch (e: Exception) {
        null
    }

    private fun android.database.Cursor.getLongOrNull(column: String): Long? {
        val index = getColumnIndex(column)
        return if (index < 0 || isNull(index)) null else getLong(index)
    }

    private fun android.database.Cursor.getStringOrNull(column: String): String? {
        val index = getColumnIndex(column)
        return if (index < 0 || isNull(index)) null else getString(index)
    }

    private fun query(
        selection: String,
        selectionArgs: Array<String>,
        limit: String? = null,
    ): List<MediaImage> {
        val results = mutableListOf<MediaImage>()
        val cursor = if (limit == null) {
            resolver.query(collection, PROJECTION, selection, selectionArgs, null)
        } else {
            resolver.query(collection, PROJECTION, selection, selectionArgs, "$limit")
        }
        cursor?.use {
            val idIndex = it.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameIndex = it.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val pathIndex = it.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)
            val bucketIndex = it.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            val mimeIndex = it.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
            val sizeIndex = it.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            val dateIndex = it.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            val addedIndex = it.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            while (it.moveToNext()) {
                val id = it.getLong(idIndex)
                results += MediaImage(
                    uri = ContentUris.withAppendedId(collection, id),
                    id = id,
                    displayName = it.getString(nameIndex) ?: continue,
                    relativePath = it.getString(pathIndex) ?: "",
                    bucketName = it.getString(bucketIndex) ?: "",
                    mimeType = it.getString(mimeIndex) ?: "",
                    sizeBytes = it.getLong(sizeIndex),
                    dateAddedMillis = it.getLong(addedIndex),
                    dateTakenMillis = MediaTime.resolve(it.getLong(dateIndex), it.getLong(addedIndex)),
                )
            }
        }
        return results
    }

    private companion object {
        /** Photos and the video they pair with are indexed moments apart, not hours apart. */
        const val SAME_SHOT_WINDOW_MILLIS = 60_000L

        val PROJECTION = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.RELATIVE_PATH,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_ADDED,
        )
    }
}