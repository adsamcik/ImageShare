package com.imageshare.feature.preset

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

interface PresetRepository {
    fun observePresets(): Flow<List<Preset>>
    fun observeDefaultPresetId(): Flow<String>
    suspend fun setDefaultPresetId(id: String)
    suspend fun getPreset(id: String): Preset?
}

class DataStorePresetRepository(
    private val dataStore: DataStore<Preferences>,
) : PresetRepository {
    override fun observePresets(): Flow<List<Preset>> = flowOf(DefaultPresets.ALL)

    override fun observeDefaultPresetId(): Flow<String> = dataStore.data.map { preferences ->
        preferences[DEFAULT_PRESET_ID_KEY]
            ?.takeIf { it in defaultPresetIds }
            ?: DefaultPresets.DEFAULT_PRESET_ID
    }

    override suspend fun setDefaultPresetId(id: String) {
        require(id in defaultPresetIds) { "Unknown preset id: $id" }

        dataStore.edit { preferences ->
            preferences[DEFAULT_PRESET_ID_KEY] = id
        }
    }

    override suspend fun getPreset(id: String): Preset? = defaultPresetsById[id]

    private companion object {
        val DEFAULT_PRESET_ID_KEY = stringPreferencesKey("default_preset_id")
        val defaultPresetsById = DefaultPresets.ALL.associateBy(Preset::id)
        val defaultPresetIds = defaultPresetsById.keys
    }
}
