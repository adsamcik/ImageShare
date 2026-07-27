package com.imageshare.core.io

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Persists processed image files to MediaStore.Images on API 29+.
 * Files land in Pictures/ImageShare/. Returns the inserted content:// URI.
 *
 * No WRITE_EXTERNAL_STORAGE permission needed: scoped-storage MediaStore writes
 * via own-app inserts are permission-free from Android 10 onward.
 */
class MediaStoreSaver(private val resolver: ContentResolver) {

    data class SavedItem(
        val sourceFile: File,
        val mediaStoreUri: Uri,
        val displayName: String,
        val sizeBytes: Long,
    )

    sealed class SaveError : Exception() {
        data class InsertFailed(val displayName: String) : SaveError()
        data class CopyFailed(override val cause: Throwable) : SaveError()
    }

    /** Save a single file to MediaStore.Images. Returns the inserted URI. */
    suspend fun save(
        sourceFile: File,
        displayName: String,
        mimeType: String,
        subfolder: String = DEFAULT_SUBFOLDER,
    ): SavedItem =
        withContext(Dispatchers.IO) {
            val safeDisplayName = sanitizeOutputFilename(displayName)
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, safeDisplayName)
                put(MediaStore.Images.Media.MIME_TYPE, mimeType)
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$subfolder")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw SaveError.InsertFailed(safeDisplayName)
            var published = false
            try {
                resolver.openOutputStream(uri)?.use { output ->
                    sourceFile.inputStream().use { input ->
                        input.copyTo(output, BUFFER_SIZE_BYTES)
                    }
                } ?: throw IOException("Unable to open MediaStore output stream")
                val updatedRows = resolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                    null,
                    null,
                )
                if (updatedRows <= 0) {
                    throw IOException("Unable to publish MediaStore item")
                }
                published = true
                SavedItem(sourceFile, uri, safeDisplayName, sourceFile.length())
            } catch (error: IOException) {
                throw SaveError.CopyFailed(error)
            } finally {
                if (!published) {
                    // Any failure after insert leaves an IS_PENDING row behind unless it is removed.
                    runCatching { resolver.delete(uri, null, null) }
                }
            }
        }

    /** Save many files atomically-ish: each is inserted independently; partial failures collected. */
    suspend fun saveAll(
        items: List<Triple<File, String, String>>,
        subfolder: String = DEFAULT_SUBFOLDER,
    ): SaveAllResult = withContext(Dispatchers.IO) {
        val succeeded = mutableListOf<SavedItem>()
        val failed = mutableListOf<Pair<File, Throwable>>()
        items.forEach { (file, displayName, mimeType) ->
            runCatching { save(file, displayName, mimeType, subfolder) }
                .onSuccess { succeeded += it }
                .onFailure { failed += file to it }
        }
        SaveAllResult(succeeded, failed)
    }

    data class SaveAllResult(
        val succeeded: List<SavedItem>,
        val failed: List<Pair<File, Throwable>>,
    )

    private companion object {
        const val DEFAULT_SUBFOLDER = "ImageShare"
        const val BUFFER_SIZE_BYTES = 8 * 1_024
    }
}
