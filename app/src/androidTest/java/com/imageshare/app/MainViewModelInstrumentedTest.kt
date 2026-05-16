package com.imageshare.app

import android.content.Intent
import android.net.Uri
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.imageshare.app.data.ImageShareDatabase
import com.imageshare.app.processing.BatchOrchestrator
import com.imageshare.app.processing.PresetPipeline
import com.imageshare.app.processing.PresetPipelineRunner
import com.imageshare.app.saving.PersistentSaver
import com.imageshare.core.io.MediaStoreSaver
import com.imageshare.core.io.OutputStore
import com.imageshare.core.io.PersistableUriRegistry
import com.imageshare.core.io.ShareLauncher
import com.imageshare.core.io.ShareUriResolver
import com.imageshare.core.io.SourceItem
import com.imageshare.core.processing.EncodeFormat
import com.imageshare.feature.preset.DefaultPresets
import com.imageshare.feature.preset.Preset
import com.imageshare.feature.preset.PresetRepository
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainViewModelInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val source = SourceItem(
        uri = Uri.parse("content://images/save-copy"),
        mimeType = "image/jpeg",
        displayName = "save-copy-source.jpg",
        sizeBytes = 10L,
        width = 100,
        height = 80,
    )

    @Test
    @Suppress("UNCHECKED_CAST")
    fun saveDocumentReceivesProcessedOutput() = runBlocking {
        lateinit var viewModel: MainViewModel
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            viewModel = viewModel()
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        val outputFile = File(context.cacheDir, "save-copy-event.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val stored = OutputStore.StoredItem("job", outputFile, outputFile.name, outputFile.length(), "image/jpeg")
        val result = PresetPipeline.Result.Success(stored, source, 100, 80, EncodeFormat.JPEG)
        val stateField = MainViewModel::class.java.getDeclaredField("mutableProcessingState").apply {
            isAccessible = true
        }
        (stateField.get(viewModel) as MutableStateFlow<ProcessingState>).value = ProcessingState.Done(listOf(result))
        val event = CompletableDeferred<Intent>()
        val collector = launch(Dispatchers.Main.immediate) {
            event.complete(viewModel.saveDocumentEvents.first())
        }
        yield()

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            viewModel.onSaveCopy()
        }

        val intent = withTimeout(5_000L) { event.await() }
        collector.cancel()
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, intent.action)
        assertEquals("image/jpeg", intent.type)
    }

    private fun viewModel(): MainViewModel = MainViewModel(
        appContext = context,
        presetRepository = FakePresetRepository(),
        shareLauncher = ShareLauncher(ShareUriResolver { _, file -> Uri.parse("content://share/${file.name}") }),
        saver = PersistentSaver(MediaStoreSaver(context.contentResolver), context.contentResolver),
        sharedIntakeRepositoryFactory = { _, _ -> FakeSharedIntakeRepository(listOf(source)) },
        batchOrchestrator = BatchOrchestrator(FakePipelineRunner(context, source)),
        persistableUriRegistry = persistableUriRegistry(),
        batchManifestDao = Room.inMemoryDatabaseBuilder(context, ImageShareDatabase::class.java)
            .allowMainThreadQueries()
            .build()
            .batchManifestDao(),
        batchWorkScheduler = FakeBatchWorkScheduler(),
    )

    private fun persistableUriRegistry(): PersistableUriRegistry {
        val file = File(context.cacheDir, "main-view-model-instrumented-${System.nanoTime()}.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Job() + Dispatchers.IO),
            produceFile = { file },
        )
        return PersistableUriRegistry(dataStore, context.contentResolver)
    }
}

private class FakePipelineRunner(
    private val context: android.content.Context,
    private val defaultSource: SourceItem,
) : PresetPipelineRunner {
    override suspend fun run(
        source: SourceItem,
        preset: Preset,
        jobId: String,
        onProgress: (PresetPipeline.Step) -> Unit,
    ): PresetPipeline.Result {
        onProgress(PresetPipeline.Step.Decoding)
        val file = File(context.cacheDir, "save-copy-${System.nanoTime()}.jpg").apply { writeBytes(byteArrayOf(1, 2)) }
        val stored = OutputStore.StoredItem(jobId, file, file.name, file.length(), "image/jpeg")
        return PresetPipeline.Result.Success(stored, source.takeIf { it.uri == source.uri } ?: defaultSource, 100, 80, EncodeFormat.JPEG)
    }
}

private class FakePresetRepository : PresetRepository {
    private val defaultId = MutableStateFlow(DefaultPresets.DEFAULT_PRESET_ID)

    override fun observePresets(): Flow<List<Preset>> = flowOf(DefaultPresets.ALL)
    override fun observeDefaultPresetId(): Flow<String> = defaultId
    override suspend fun setDefaultPresetId(id: String) { defaultId.value = id }
    override suspend fun getPreset(id: String): Preset? = DefaultPresets.ALL.firstOrNull { it.id == id }
}

private class FakeSharedIntakeRepository(private val sources: List<SourceItem>) : SharedIntakeRepository {
    override suspend fun stage(jobId: String, uris: List<Uri>): List<SourceItem> = sources
    override suspend fun sweep() = Unit
}

private class FakeBatchWorkScheduler : BatchWorkScheduler {
    override fun enqueue(jobId: String, presetId: String, customOverrideJson: String?): Flow<BatchWorkStatus> =
        flowOf(BatchWorkStatus(BatchWorkState.Succeeded))

    override fun observe(jobId: String): Flow<BatchWorkStatus> =
        flowOf(BatchWorkStatus(BatchWorkState.Succeeded))

    override fun cancel(jobId: String) = Unit
}
