package com.imageshare.app.sharing

import android.content.ComponentName
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONObject

data class SharingTarget(
    val componentName: ComponentName,
    val count: Int,
    val lastUsedAt: Long,
)

interface SharingTargetsRepository {
    fun observeTopTargets(k: Int = 3): Flow<List<SharingTarget>>
    suspend fun recordSelection(componentName: ComponentName)
    suspend fun clearAll()
}

class DataStoreSharingTargetsRepository(
    private val dataStore: DataStore<Preferences>,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : SharingTargetsRepository {
    override fun observeTopTargets(k: Int): Flow<List<SharingTarget>> =
        dataStore.data.map { prefs ->
            prefs[USAGE_KEY].orEmpty()
                .decodeTargets()
                .sortedWith(targetOrdering)
                .take(k)
        }

    override suspend fun recordSelection(componentName: ComponentName) {
        dataStore.edit { prefs ->
            val current = prefs[USAGE_KEY].orEmpty().decodeTargets().associateBy { it.componentName }.toMutableMap()
            val existing = current[componentName]
            current[componentName] = SharingTarget(
                componentName = componentName,
                count = (existing?.count ?: 0) + 1,
                lastUsedAt = clock(),
            )
            prefs[USAGE_KEY] = current.values
                .sortedWith(targetOrdering)
                .take(MAX_TARGETS)
                .encodeTargets()
        }
    }

    override suspend fun clearAll() {
        dataStore.edit { it.remove(USAGE_KEY) }
    }

    private companion object {
        val USAGE_KEY = stringPreferencesKey("sharing_target_usage")
        const val MAX_TARGETS = 50
    }
}

class AutoProcessOnShareSettings(
    private val dataStore: DataStore<Preferences>,
) {
    val enabled: Flow<Boolean> = dataStore.data.map { prefs -> prefs[AUTO_PROCESS_KEY] ?: false }

    suspend fun isEnabled(): Boolean = enabled.first()

    suspend fun setEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[AUTO_PROCESS_KEY] = enabled }
    }

    private companion object {
        val AUTO_PROCESS_KEY = booleanPreferencesKey("auto_process_on_share")
    }
}

private val targetOrdering = compareByDescending<SharingTarget> { it.count }
    .thenByDescending { it.lastUsedAt }

private fun String.decodeTargets(): List<SharingTarget> = runCatching {
    if (isBlank()) return@runCatching emptyList()
    val root = JSONObject(this)
    root.keys().asSequence().mapNotNull { flattened ->
        val value = root.optJSONObject(flattened) ?: return@mapNotNull null
        val componentName = ComponentName.unflattenFromString(flattened) ?: return@mapNotNull null
        SharingTarget(
            componentName = componentName,
            count = value.optInt("count", 0),
            lastUsedAt = value.optLong("lastUsedAt", 0L),
        )
    }.filter { it.count > 0 }.toList()
}.getOrDefault(emptyList())

private fun Collection<SharingTarget>.encodeTargets(): String {
    val root = JSONObject()
    forEach { target ->
        root.put(
            target.componentName.flattenToString(),
            JSONObject()
                .put("count", target.count)
                .put("lastUsedAt", target.lastUsedAt),
        )
    }
    return root.toString()
}
