package com.imageshare.app

import android.content.Context
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
}

const val SHARED_INTAKE_DIR = "shared-intake"
