package com.imageshare.core.io

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class SharedIntakeStager(
    private val resolver: ContentResolver,
    private val cacheRoot: File,
) {
    data class StagedItem(
        val originalUri: Uri,
        val cachedUri: Uri,
        val cachedFile: File,
        val displayName: String?,
        val sizeBytes: Long?,
    )

    suspend fun stage(jobId: String, originals: List<Uri>): List<StagedItem> = withContext(Dispatchers.IO) {
        val jobDir = File(cacheRoot, jobId).apply { mkdirs() }
        originals.mapIndexedNotNull { index, uri ->
            runCatching { stageOne(jobDir, index, uri) }
                .onFailure { Log.w(TAG, "Failed to stage shared URI $uri", it) }
                .getOrNull()
        }
    }

    suspend fun sweep(olderThanMillis: Long = DEFAULT_SWEEP_AGE_MS) {
        withContext(Dispatchers.IO) {
            val cutoff = System.currentTimeMillis() - olderThanMillis
            cacheRoot.listFiles()
                ?.filter { it.isDirectory && it.lastModified() < cutoff }
                ?.forEach { dir ->
                    runCatching { dir.deleteRecursively() }
                        .onFailure { Log.w(TAG, "Failed to sweep staged dir $dir", it) }
                }
        }
    }

    private fun stageOne(jobDir: File, index: Int, uri: Uri): StagedItem {
        val metadata = queryMetadata(uri)
        val displayName = metadata.displayName
        val safeName = displayName.sanitizeDisplayName().ifEmpty { "image-$index.bin" }
        val cachedFile = File(jobDir, "$index-$safeName")

        resolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "ContentResolver returned null stream for $uri" }
            FileOutputStream(cachedFile).use { output ->
                input.copyTo(output, BUFFER_SIZE_BYTES)
            }
        }

        return StagedItem(
            originalUri = uri,
            cachedUri = Uri.fromFile(cachedFile),
            cachedFile = cachedFile,
            displayName = displayName,
            sizeBytes = metadata.sizeBytes,
        )
    }

    private fun queryMetadata(uri: Uri): StagedQueryMetadata {
        return runCatching {
            resolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null,
            ).use { cursor ->
                if (cursor == null || !cursor.moveToFirst()) {
                    StagedQueryMetadata()
                } else {
                    StagedQueryMetadata(
                        displayName = cursor.getNullableString(OpenableColumns.DISPLAY_NAME),
                        sizeBytes = cursor.getNullableLong(OpenableColumns.SIZE),
                    )
                }
            }
        }.onFailure { Log.w(TAG, "Failed to query metadata for $uri", it) }
            .getOrDefault(StagedQueryMetadata())
    }

    companion object {
        const val DEFAULT_SWEEP_AGE_MS: Long = 24L * 60L * 60L * 1_000L
        private const val TAG = "SharedIntakeStager"
        private const val BUFFER_SIZE_BYTES = 8 * 1_024
    }
}

private data class StagedQueryMetadata(
    val displayName: String? = null,
    val sizeBytes: Long? = null,
)

private fun String?.sanitizeDisplayName(): String {
    if (isNullOrBlank()) {
        return ""
    }

    var sanitized = asSequence()
        .filterNot { it == '/' || it == '\\' || it.isISOControl() }
        .joinToString("")
        .trim()
        .take(MAX_DISPLAY_NAME_LENGTH)
    while (sanitized.contains("..")) {
        sanitized = sanitized.replace("..", ".")
    }
    return sanitized
}

private fun Cursor.getNullableString(columnName: String): String? {
    val index = getColumnIndex(columnName)
    return if (index >= 0 && !isNull(index)) getString(index) else null
}

private fun Cursor.getNullableLong(columnName: String): Long? {
    val index = getColumnIndex(columnName)
    return if (index >= 0 && !isNull(index)) getLong(index) else null
}

private const val MAX_DISPLAY_NAME_LENGTH = 100
