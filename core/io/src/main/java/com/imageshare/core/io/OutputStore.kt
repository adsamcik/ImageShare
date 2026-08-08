package com.imageshare.core.io

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * App-private staging for OUTGOING (processed) images. Files are written to
 * cacheRoot/shared-output/<jobId>/<filename> and exposed to other apps only
 * through a FileProvider; callers obtain the share-safe content:// URI via
 * [ShareLauncher.toShareUri].
 *
 * Lifecycle: caller passes a jobId; OutputStore creates the dir. The same
 * OutputStore instance can be used to sweep stale jobs.
 */
class OutputStore(
    private val cacheRoot: File,
) {
    data class StoredItem(
        val jobId: String,
        val file: File,
        val filename: String,
        val sizeBytes: Long,
        val mimeType: String,
    )

    /** Write bytes to a fresh file under cacheRoot/shared-output/<jobId>/. */
    suspend fun store(
        jobId: String,
        filename: String,
        bytes: ByteArray,
        mimeType: String,
    ): StoredItem = withContext(Dispatchers.IO) {
        val jobDir = root().resolve(jobId).apply { mkdirs() }
        val safeFilename = sanitizeOutputFilename(filename)
        val outputFile = reserveOutputFile(jobDir, safeFilename)
        FileOutputStream(outputFile).use { output ->
            bytes.inputStream().use { input ->
                input.copyTo(output, BUFFER_SIZE_BYTES)
            }
        }
        StoredItem(
            jobId = jobId,
            file = outputFile,
            filename = outputFile.name,
            sizeBytes = outputFile.length(),
            mimeType = mimeType,
        )
    }

    /** Best-effort sweep of jobs older than [olderThanMillis]. */
    suspend fun sweep(
        olderThanMillis: Long = DEFAULT_SWEEP_AGE_MS,
        keepJobIds: Set<String> = emptySet(),
    ) {
        withContext(Dispatchers.IO) {
            val cutoff = System.currentTimeMillis() - olderThanMillis
            root().listFiles()
                ?.filter { it.isDirectory && it.name !in keepJobIds && it.lastModified() < cutoff }
                ?.forEach { dir ->
                    runCatching { dir.deleteRecursively() }
                        .onFailure { Log.w(TAG, "Failed to sweep output dir $dir", it) }
                }
        }
    }

    /** The on-disk root used for storing outputs (== cacheRoot/shared-output). */
    fun root(): File = cacheRoot.resolve(SUBDIR_NAME)

    /**
     * Atomically reserves a path so same-named sources cannot overwrite one another.
     * The first item keeps its preferred filename; later items receive a numeric suffix.
     */
    private fun reserveOutputFile(jobDir: File, filename: String): File {
        var collisionIndex = 0
        while (true) {
            val candidateFilename = if (collisionIndex == 0) {
                filename
            } else {
                filename.withCollisionSuffix(collisionIndex)
            }
            val candidate = jobDir.resolve(candidateFilename)
            if (candidate.createNewFile()) return candidate
            collisionIndex += 1
        }
    }

    companion object {
        const val DEFAULT_SWEEP_AGE_MS: Long = 24L * 60L * 60L * 1_000L
        const val SUBDIR_NAME: String = "shared-output"
        private const val TAG = "OutputStore"
        private const val BUFFER_SIZE_BYTES = 8 * 1_024
    }
}

internal fun sanitizeOutputFilename(filename: String): String {
    val sanitized = filename.asSequence()
        .mapNotNull { char ->
            when {
                char == '/' || char == '\\' -> '_'
                char.isISOControl() -> null
                else -> char
            }
        }
        .joinToString("")
        .trim()
        .take(MAX_OUTPUT_FILENAME_LENGTH)
        .trim('.', ' ')
    return sanitized.ifEmpty { "image.bin" }
}

private fun String.withCollisionSuffix(index: Int): String {
    val suffix = "-$index"
    val extensionStart = lastIndexOf('.').takeIf { it in 1 until lastIndex }
    val base = extensionStart?.let { substring(0, it) } ?: this
    val extension = extensionStart?.let { substring(it) }.orEmpty()
    val boundedExtension = extension.take(
        (MAX_OUTPUT_FILENAME_LENGTH - suffix.length - MIN_BASE_LENGTH).coerceAtLeast(0),
    )
    val boundedBase = base.take(
        (MAX_OUTPUT_FILENAME_LENGTH - suffix.length - boundedExtension.length).coerceAtLeast(MIN_BASE_LENGTH),
    )
    return "$boundedBase$suffix$boundedExtension"
}

private const val MAX_OUTPUT_FILENAME_LENGTH = 100
private const val MIN_BASE_LENGTH = 1
