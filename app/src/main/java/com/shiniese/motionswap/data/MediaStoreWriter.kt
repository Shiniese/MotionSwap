package com.shiniese.motionswap.data

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import java.io.IOException

/**
 * Writes results into a user-chosen folder under `Pictures/`.
 *
 * Using `RELATIVE_PATH` means files land in the MediaStore collection the system
 * gallery watches, so results show up in the album without an extra scan. Name clashes
 * get a numeric suffix instead of overwriting anything. Entries are created as
 * `IS_PENDING` and only published once the bytes are complete, so an interrupted run
 * never leaves a truncated file in the gallery.
 */
class MediaStoreWriter(private val resolver: ContentResolver) {

    sealed interface Result {
        data class Success(val uri: Uri, val displayName: String, val bytesWritten: Long) : Result
        data class Failure(val reason: String) : Result
    }

    /**
     * Creates a pending entry and hands its open stream to [write].
     *
     * [write] returns the number of bytes written, or throws to abort. Anything thrown
     * removes the partial entry, so a failed run never leaves a truncated file behind.
     */
    fun writeImage(
        subdirectory: String,
        displayName: String,
        mimeType: String = JPEG_MIME,
        write: (java.io.OutputStream) -> Long,
    ): Result = writeTo(imageCollection(), subdirectory, displayName, mimeType, write)

    /** Writes a video track. */
    fun writeVideo(
        subdirectory: String,
        displayName: String,
        mimeType: String = MP4_MIME,
        write: (java.io.OutputStream) -> Long,
    ): Result = writeTo(videoCollection(), subdirectory, displayName, mimeType, write)

    private fun writeTo(
        collection: Uri,
        subdirectory: String,
        displayName: String,
        mimeType: String,
        write: (java.io.OutputStream) -> Long,
    ): Result {
        val relativePath = relativePathFor(subdirectory)
        return try {
            val uniqueName = uniqueDisplayName(collection, relativePath, displayName)
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, uniqueName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(collection, values)
                ?: return Result.Failure("无法在 $relativePath 创建文件")

            val written = try {
                resolver.openOutputStream(uri)?.use(write)
                    ?: throw IOException("无法写入文件")
            } catch (e: Exception) {
                discard(uri)
                return Result.Failure(e.message ?: "写入失败")
            }

            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null,
            )
            Result.Success(uri, uniqueName, written)
        } catch (e: Exception) {
            Result.Failure(e.message ?: "写入失败")
        }
    }

    private fun discard(uri: Uri) {
        runCatching { resolver.delete(uri, null, null) }
    }

    private fun relativePathFor(subdirectory: String): String {
        val cleaned = subdirectory.trim().trim('/')
        return if (cleaned.isEmpty()) DEFAULT_ROOT else "$DEFAULT_ROOT$cleaned/"
    }

    private fun uniqueDisplayName(
        collection: Uri,
        relativePath: String,
        displayName: String,
    ): String {
        if (!exists(collection, relativePath, displayName)) return displayName
        val dot = displayName.lastIndexOf('.')
        val base = if (dot > 0) displayName.substring(0, dot) else displayName
        val extension = if (dot > 0) displayName.substring(dot) else ""
        var counter = 1
        while (true) {
            val candidate = "${base}_$counter$extension"
            if (!exists(collection, relativePath, candidate)) return candidate
            counter++
        }
    }

    private fun exists(collection: Uri, relativePath: String, displayName: String): Boolean =
        resolver.query(
            collection,
            arrayOf(MediaStore.Images.Media._ID),
            "${MediaStore.Images.Media.RELATIVE_PATH} = ? AND " +
                "${MediaStore.Images.Media.DISPLAY_NAME} = ?",
            arrayOf(relativePath, displayName),
            null,
        )?.use { it.count > 0 } ?: false

    private fun imageCollection(): Uri =
        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)

    private fun videoCollection(): Uri =
        MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)

    companion object {
        const val DEFAULT_ROOT = "Pictures/"
        const val DEFAULT_SUBDIRECTORY = "MotionSwap"
        const val JPEG_MIME = "image/jpeg"
        const val MP4_MIME = "video/mp4"
    }
}