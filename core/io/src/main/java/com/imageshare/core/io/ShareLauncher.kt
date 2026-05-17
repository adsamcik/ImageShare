package com.imageshare.core.io

import android.content.Context
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

fun interface ShareUriResolver {
    fun toUri(context: Context, file: File): Uri
}

/**
 * Builds Sharesheet intents for OUTBOUND share. The caller passes [Context]
 * each call so this class does not hold a leakable reference.
 *
 * The provider authority is computed as "${packageName}.shareprovider" and
 * must match the FileProvider declared in the :app manifest.
 */
class ShareLauncher(
    private val uriResolver: ShareUriResolver = ShareUriResolver { context, file ->
        val authority = "${context.packageName}.shareprovider"
        FileProvider.getUriForFile(context, authority, file)
    },
) {
    fun toShareUri(context: Context, file: File): Uri {
        return uriResolver.toUri(context, file)
    }

    /**
     * Build a single-image share intent (does NOT start the activity — caller
     * starts it via `context.startActivity(Intent.createChooser(intent, null))`).
     */
    fun buildShareIntent(context: Context, item: OutputStore.StoredItem): Intent {
        val uri = toShareUri(context, item.file)
        return Intent(Intent.ACTION_SEND).apply {
            type = item.mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newUri(context.contentResolver, item.filename, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /** Build a multi-image share intent. All items SHOULD have the same MIME for cleanest receiver behavior. */
    fun buildShareIntent(context: Context, items: List<OutputStore.StoredItem>): Intent {
        require(items.isNotEmpty()) { "items must not be empty" }
        if (items.size == 1) return buildShareIntent(context, items.first())

        val uris = ArrayList(items.map { toShareUri(context, it.file) })
        val commonMime = items.map { it.mimeType }.distinct().singleOrNull() ?: "image/*"
        return Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = commonMime
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            clipData = ClipData.newUri(context.contentResolver, items.first().filename, uris.first()).apply {
                uris.drop(1).forEach { uri -> addItem(ClipData.Item(uri)) }
            }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
