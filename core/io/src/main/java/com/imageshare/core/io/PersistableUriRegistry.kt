package com.imageshare.core.io

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Tracks URIs that the user has explicitly chosen via ACTION_OPEN_DOCUMENT and for which we've
 * called takePersistableUriPermission(). These URIs can be re-opened across reboots until the
 * user revokes them (e.g., by reinstalling the source app or clearing storage permissions).
 *
 * The registry is bounded — we keep at most [maxEntries] (default 12) URIs. Older entries are
 * dropped on overflow.
 *
 * Note: this is separate from share-intent URIs (which are staged via SharedIntakeStager and
 * NOT persisted) and from picker URIs (PickVisualMedia, also not persistable per Android docs).
 */
class PersistableUriRegistry(
    private val dataStore: DataStore<Preferences>,
    private val resolver: ContentResolver,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {
    /** Emits the current ordered list of persisted URIs, newest first. */
    fun observe(): Flow<List<Uri>> = dataStore.data
        .map { prefs -> prefs.uriStrings().mapNotNull { it.toUriOrNull() } }
        .flowOn(Dispatchers.IO)

    /** Persist a URI grant. Idempotent: re-adding the same URI moves it to the front. */
    suspend fun add(uri: Uri, flags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION) {
        withContext(Dispatchers.IO) {
            val permissionFlags = flags.persistableFlags()
            val persisted = runCatching {
                resolver.takePersistableUriPermission(uri, permissionFlags)
            }.onFailure { error ->
                Log.w(TAG, "Failed to persist URI grant for $uri", error)
            }.isSuccess
            if (!persisted) return@withContext

            var dropped: List<Uri> = emptyList()
            dataStore.edit { prefs ->
                val updated = (listOf(uri.toString()) + prefs.uriStrings().filterNot { it == uri.toString() })
                    .take(maxEntries)
                dropped = prefs.uriStrings()
                    .filterNot { it in updated }
                    .mapNotNull { it.toUriOrNull() }
                prefs[URI_LIST_KEY] = updated.joinToString(LIST_DELIMITER)
            }
            dropped.forEach { droppedUri ->
                runCatching { resolver.releasePersistableUriPermission(droppedUri, permissionFlags) }
                    .onFailure { Log.w(TAG, "Failed to release dropped URI grant for $droppedUri", it) }
            }
        }
    }

    /** Remove a URI grant + release the persistable permission. */
    suspend fun remove(uri: Uri) {
        withContext(Dispatchers.IO) {
            runCatching {
                resolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }.onFailure {
                Log.w(TAG, "Failed to release URI grant for $uri", it)
            }
            dataStore.edit { prefs ->
                prefs[URI_LIST_KEY] = prefs.uriStrings()
                    .filterNot { it == uri.toString() }
                    .joinToString(LIST_DELIMITER)
            }
        }
    }

    /** Drop URIs whose grants are no longer alive. Returns the count removed. */
    suspend fun reconcile(): Int = withContext(Dispatchers.IO) {
        val alive = resolver.persistedUriPermissions.map { it.uri.toString() }.toSet()
        var removed = 0
        dataStore.edit { prefs ->
            val current = prefs.uriStrings()
            val kept = current.filter { it in alive }
            removed = current.size - kept.size
            prefs[URI_LIST_KEY] = kept.joinToString(LIST_DELIMITER)
        }
        removed
    }

    companion object {
        const val DEFAULT_MAX_ENTRIES = 12
        private const val TAG = "PersistableUriRegistry"
        private const val LIST_DELIMITER = "\n"
    }
}

private fun Preferences.uriStrings(): List<String> =
    this[URI_LIST_KEY]?.split("\n")?.filter { it.isNotBlank() }.orEmpty()

private fun String.toUriOrNull(): Uri? = runCatching { Uri.parse(this) }.getOrNull()

private fun Int.persistableFlags(): Int =
    this and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)

private val URI_LIST_KEY = stringPreferencesKey("persisted_uris")
