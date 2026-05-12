package com.imageshare.app

import android.content.Context
import com.imageshare.app.saving.PersistentSaver
import com.imageshare.core.io.MediaStoreSaver
import com.imageshare.core.io.OutputStore
import com.imageshare.core.io.ShareLauncher
import com.imageshare.core.io.SharedIntakeStager
import com.imageshare.feature.preset.DataStorePresetRepository
import com.imageshare.feature.preset.PresetRepository
import com.imageshare.feature.preset.presetDataStore

object AppContainer {
    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    val presetRepository: PresetRepository by lazy {
        DataStorePresetRepository(presetDataStore(appContext))
    }

    val outputStore: OutputStore by lazy { OutputStore(appContext.cacheDir) }

    val sharedIntakeStager: SharedIntakeStager by lazy {
        SharedIntakeStager(appContext.contentResolver, appContext.cacheDir.resolve(SHARED_INTAKE_DIR))
    }

    val shareLauncher: ShareLauncher by lazy { ShareLauncher() }

    val mediaStoreSaver: MediaStoreSaver by lazy { MediaStoreSaver(appContext.contentResolver) }

    val persistentSaver: PersistentSaver by lazy { PersistentSaver(mediaStoreSaver, appContext.contentResolver) }
}

const val SHARED_INTAKE_DIR = "shared-intake"
