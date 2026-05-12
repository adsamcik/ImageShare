package com.imageshare.core.io

import android.content.ContentResolver
import android.database.Cursor
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class SourceItem(
    val uri: Uri,
    val mimeType: String?,
    val displayName: String?,
    val sizeBytes: Long?,
    val width: Int?,
    val height: Int?,
)

/**
 * Failure while resolving picker input.
 *
 * [SecurityException]s are surfaced as the [cause] of [QueryFailed], wrapped as [GrantLost]:
 *
 * ```
 * try { coordinator.resolve(uri) } catch (e: IntakeError.QueryFailed) {
 *     when (val cause = e.cause) {
 *         is IntakeError.GrantLost -> /* re-pick or show toast */
 *         else -> /* generic error UI */
 *     }
 * }
 * ```
 */
sealed class IntakeError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    data class GrantLost(val uri: Uri) : IntakeError("Permission grant lost for $uri")
    data class QueryFailed(val uri: Uri, override val cause: Throwable) :
        IntakeError("Failed to resolve metadata for $uri", cause)
}

class InputCoordinator(private val resolver: ContentResolver) {
    suspend fun resolve(uri: Uri): SourceItem = withContext(Dispatchers.IO) {
        var firstFailure: Throwable? = null

        fun recordFailure(throwable: Throwable) {
            if (firstFailure == null) {
                firstFailure = if (throwable is SecurityException) {
                    IntakeError.GrantLost(uri)
                } else {
                    throwable
                }
            }
        }

        val metadata = runCatching { queryMetadata(uri) }
            .onFailure(::recordFailure)
            .getOrDefault(QueryMetadata())
        val mimeType = runCatching { resolver.getType(uri) }
            .onFailure(::recordFailure)
            .getOrNull()
        val dimensions = runCatching { readDimensions(uri) }
            .onFailure(::recordFailure)
            .getOrNull()

        val item = SourceItem(
            uri = uri,
            mimeType = mimeType,
            displayName = metadata.displayName,
            sizeBytes = metadata.sizeBytes,
            width = dimensions?.width,
            height = dimensions?.height,
        )

        if (!item.hasResolvedData()) {
            throw IntakeError.QueryFailed(
                uri = uri,
                cause = firstFailure ?: IllegalStateException("No metadata could be resolved for $uri"),
            )
        }

        item
    }

    suspend fun resolveAll(uris: List<Uri>): List<SourceItem> = withContext(Dispatchers.IO) {
        uris.map { uri -> resolve(uri) }
    }

    private fun queryMetadata(uri: Uri): QueryMetadata {
        resolver.query(uri, null, null, null, null).use { cursor ->
            if (cursor == null || !cursor.moveToFirst()) {
                return QueryMetadata()
            }

            return QueryMetadata(
                displayName = cursor.getNullableString(OpenableColumns.DISPLAY_NAME),
                sizeBytes = cursor.getNullableLong(OpenableColumns.SIZE),
            )
        }
    }

    private fun readDimensions(uri: Uri): Dimensions? {
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }

        resolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, options)
        } ?: return null

        return if (options.outWidth > 0 && options.outHeight > 0) {
            Dimensions(width = options.outWidth, height = options.outHeight)
        } else {
            null
        }
    }
}

private data class QueryMetadata(
    val displayName: String? = null,
    val sizeBytes: Long? = null,
)

private data class Dimensions(
    val width: Int,
    val height: Int,
)

private fun SourceItem.hasResolvedData(): Boolean =
    mimeType != null || displayName != null || sizeBytes != null || width != null || height != null

private fun Cursor.getNullableString(columnName: String): String? {
    val index = getColumnIndex(columnName)
    return if (index >= 0 && !isNull(index)) getString(index) else null
}

private fun Cursor.getNullableLong(columnName: String): Long? {
    val index = getColumnIndex(columnName)
    return if (index >= 0 && !isNull(index)) getLong(index) else null
}
