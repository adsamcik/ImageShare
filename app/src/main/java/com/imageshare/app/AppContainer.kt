package com.imageshare.app

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import com.imageshare.app.data.BatchManifestDao
import com.imageshare.app.data.ImageShareDatabase
import com.imageshare.app.processing.BatchOrchestrator
import com.imageshare.app.processing.PresetPipeline
import com.imageshare.app.saving.PersistentSaver
import com.imageshare.core.io.MediaStoreSaver
import com.imageshare.core.io.OutputStore
import com.imageshare.core.io.PersistableUriRegistry
import com.imageshare.core.io.ShareLauncher
import com.imageshare.core.io.SharedIntakeStager
import com.imageshare.feature.preset.DataStorePresetRepository
import com.imageshare.feature.preset.PresetRepository
import com.imageshare.feature.preset.presetDataStore

object AppContainer {
    private lateinit var appContext: Context
    private var testBatchManifestDao: BatchManifestDao? = null
    private var testBatchOrchestrator: BatchOrchestrator? = null
    private var testPresetRepository: PresetRepository? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    val presetRepository: PresetRepository by lazy {
        DataStorePresetRepository(presetDataStore(appContext))
    }

    val activePresetRepository: PresetRepository
        get() = testPresetRepository ?: presetRepository

    val outputStore: OutputStore by lazy { OutputStore(appContext.cacheDir) }

    val presetPipeline: PresetPipeline by lazy { PresetPipeline(appContext.contentResolver, outputStore) }

    val batchOrchestrator: BatchOrchestrator by lazy { BatchOrchestrator(presetPipeline) }

    val activeBatchOrchestrator: BatchOrchestrator
        get() = testBatchOrchestrator ?: batchOrchestrator

    val database: ImageShareDatabase by lazy { ImageShareDatabase.create(appContext) }

    val batchManifestDao: BatchManifestDao
        get() = testBatchManifestDao ?: database.batchManifestDao()

    val sharedIntakeStager: SharedIntakeStager by lazy {
        SharedIntakeStager(appContext.contentResolver, appContext.cacheDir.resolve(SHARED_INTAKE_DIR))
    }

    val shareLauncher: ShareLauncher by lazy { ShareLauncher() }

    val mediaStoreSaver: MediaStoreSaver by lazy { MediaStoreSaver(appContext.contentResolver) }

    val persistentSaver: PersistentSaver by lazy { PersistentSaver(mediaStoreSaver, appContext.contentResolver) }

    val persistableUriRegistry: PersistableUriRegistry by lazy {
        PersistableUriRegistry(
            dataStore = uriRegistryDataStore(appContext),
            resolver = appContext.contentResolver,
        )
    }

    fun overrideForTests(
        batchManifestDao: BatchManifestDao? = null,
        batchOrchestrator: BatchOrchestrator? = null,
        presetRepository: PresetRepository? = null,
    ) {
        testBatchManifestDao = batchManifestDao
        testBatchOrchestrator = batchOrchestrator
        testPresetRepository = presetRepository
    }
}

const val SHARED_INTAKE_DIR = "shared-intake"

private val Context.uriRegistryDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "imageshare_uri_registry",
)

private fun uriRegistryDataStore(context: Context): DataStore<Preferences> = context.uriRegistryDataStore
