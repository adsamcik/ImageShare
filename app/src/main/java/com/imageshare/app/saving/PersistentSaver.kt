package com.imageshare.app.saving

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import com.imageshare.core.io.MediaStoreSaver
import com.imageshare.core.io.OutputStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Coordinates the save flow:
 * - SINGLE result: builds an ACTION_CREATE_DOCUMENT intent; the Activity launches it, then
 *   [copyToUri] writes the bytes.
 * - BATCH (>1 results): inserts all via [MediaStoreSaver] and reports results.
 */
class PersistentSaver(
    private val mediaStoreSaver: MediaStoreSaver,
    private val resolver: ContentResolver,
) {
    sealed class Outcome {
        data class SingleStarted(val intent: Intent, val pendingFile: File) : Outcome()
        data class BatchCompleted(val result: MediaStoreSaver.SaveAllResult) : Outcome()
    }

    /** Decide the save strategy based on count. */
    suspend fun planSave(results: List<OutputStore.StoredItem>): Outcome {
        require(results.isNotEmpty())
        return if (results.size == 1) {
            val item = results.single()
            Outcome.SingleStarted(buildCreateDocumentIntent(item.filename, item.mimeType), item.file)
        } else {
            Outcome.BatchCompleted(
                mediaStoreSaver.saveAll(results.map { Triple(it.file, it.filename, it.mimeType) }),
            )
        }
    }

    /** Once the user picks a destination via ACTION_CREATE_DOCUMENT, copy bytes there. */
    suspend fun copyToUri(destUri: Uri, sourceFile: File): Long = withContext(Dispatchers.IO) {
        resolver.openOutputStream(destUri, WRITE_MODE)?.use { output ->
            sourceFile.inputStream().use { input ->
                input.copyTo(output, BUFFER_SIZE_BYTES)
            }
        } ?: throw IOException("Unable to open document output stream")
        sourceFile.length()
    }

    fun buildCreateDocumentIntent(displayName: String, mimeType: String): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mimeType
            putExtra(Intent.EXTRA_TITLE, displayName)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

    private companion object {
        const val BUFFER_SIZE_BYTES = 8 * 1_024
        const val WRITE_MODE = "w"
    }
}
