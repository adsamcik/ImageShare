package com.imageshare.feature.preset

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore

private val Context.presetsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "imageshare_presets",
)

fun presetDataStore(context: Context): DataStore<Preferences> = context.presetsDataStore
