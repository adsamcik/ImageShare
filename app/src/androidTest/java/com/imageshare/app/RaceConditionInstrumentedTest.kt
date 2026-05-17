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
import com.imageshare.app.sharing.AutoProcessOnShareSettings
import com.imageshare.app.sharing.DataStoreSharingTargetsRepository
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RaceConditionInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val source = source("content://race/one", "one.jpg")

    @Test
    fun rapidPresetSwitchingDuringProcessingKeepsInFlightPresetStable() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val runner = BlockingPipelineRunner(context, gate)
        val viewModel = viewModel(runner)
        viewModel.stageSharedUris("job", listOf(source.uri), StaticSharedIntakeRepository(listOf(source)))

        InstrumentationRegistry.getInstrumentation().runOnMainSync { viewModel.onProcessAndShare() }
        withTimeout(5_000L) { runner.firstStarted.await() }
        InstrumentationRegistry.getInstrumentation().runOnMainSync { viewModel.onPresetSelected(DefaultPresets.BestQuality.id) }

        gate.complete(Unit)
        waitUntilDone(viewModel)

        assertEquals(listOf(DefaultPresets.SmallFile.id), runner.seenPresetIds)
        assertTrue(viewModel.processingState.value is ProcessingState.Done)
    }

    @Test
    fun doubleTapProcessAndShareLaunchesOnlyOneShareIntent() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val runner = BlockingPipelineRunner(context, gate)
        val viewModel = viewModel(runner)
        val events = mutableListOf<Intent>()
        val collector = launch(Dispatchers.Main.immediate) { viewModel.shareEvents.collect { events += it } }
        viewModel.stageSharedUris("job", listOf(source.uri), StaticSharedIntakeRepository(listOf(source)))

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            viewModel.onProcessAndShare()
            viewModel.onProcessAndShare()
        }
        withTimeout(5_000L) { runner.firstStarted.await() }
        gate.complete(Unit)
        waitUntilDone(viewModel)
        withTimeout(5_000L) {
            while (events.isEmpty()) delay(10L)
        }

        assertEquals(1, runner.calls)
        assertEquals(1, events.size)
        collector.cancel()
    }

    @Test
    fun concurrentSharedIntentWhileProcessingDoesNotCrashPreviousBatch() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val runner = BlockingPipelineRunner(context, gate)
        val viewModel = viewModel(runner)
        val replacement = source("content://race/two", "two.jpg")
        viewModel.stageSharedUris("job-1", listOf(source.uri), StaticSharedIntakeRepository(listOf(source)))

        InstrumentationRegistry.getInstrumentation().runOnMainSync { viewModel.onProcessAndShare() }
        withTimeout(5_000L) { runner.firstStarted.await() }
        viewModel.stageSharedUris("job-2", listOf(replacement.uri), StaticSharedIntakeRepository(listOf(replacement)))
        waitUntilSources(viewModel, listOf(replacement.uri))

        gate.complete(Unit)
        waitUntilDone(viewModel)

        val done = viewModel.processingState.value as ProcessingState.Done
        val successes = done.results.filterIsInstance<PresetPipeline.Result.Success>()
        assertEquals(1, successes.size)
        assertEquals(source.uri, successes.single().before.uri)
        assertEquals(listOf(replacement.uri), viewModel.sources.value.map { it.uri })
    }

    private suspend fun waitUntilSources(viewModel: MainViewModel, expectedUris: List<Uri>) {
        withTimeout(5_000L) {
            while (viewModel.sources.value.map { it.uri } != expectedUris) delay(10L)
        }
    }

    private suspend fun waitUntilDone(viewModel: MainViewModel) {
        withTimeout(10_000L) {
            while (viewModel.processingState.value !is ProcessingState.Done) delay(10L)
        }
    }

    private fun viewModel(runner: PresetPipelineRunner): MainViewModel {
        val database = Room.inMemoryDatabaseBuilder(context, ImageShareDatabase::class.java).allowMainThreadQueries().build()
        val registryFile = File(context.cacheDir, "race-${System.nanoTime()}.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Job() + Dispatchers.IO),
            produceFile = { registryFile },
        )
        val sharingDataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Job() + Dispatchers.IO),
            produceFile = { File(context.cacheDir, "race-sharing-${System.nanoTime()}.preferences_pb") },
        )
        return MainViewModel(
            appContext = context,
            presetRepository = RacePresetRepository(),
            shareLauncher = ShareLauncher(ShareUriResolver { _, file -> Uri.parse("content://share/${file.name}") }),
            saver = PersistentSaver(MediaStoreSaver(context.contentResolver), context.contentResolver),
            sharedIntakeRepositoryFactory = { _, _ -> StaticSharedIntakeRepository(listOf(source)) },
            batchOrchestrator = BatchOrchestrator(runner),
            persistableUriRegistry = PersistableUriRegistry(dataStore, context.contentResolver),
            batchManifestDao = database.batchManifestDao(),
            batchWorkScheduler = RaceBatchWorkScheduler(),
            sharingTargetsRepository = DataStoreSharingTargetsRepository(sharingDataStore),
            autoProcessOnShareSettings = AutoProcessOnShareSettings(sharingDataStore),
        )
    }

    private fun source(uri: String, name: String) = SourceItem(Uri.parse(uri), "image/jpeg", name, 10L, 100, 80)
}

private class BlockingPipelineRunner(
    private val context: android.content.Context,
    private val gate: CompletableDeferred<Unit>,
) : PresetPipelineRunner {
    val firstStarted = CompletableDeferred<Unit>()
    val seenPresetIds = mutableListOf<String>()
    var calls = 0
        private set

    override suspend fun run(
        source: SourceItem,
        preset: Preset,
        jobId: String,
        onProgress: (PresetPipeline.Step) -> Unit,
    ): PresetPipeline.Result {
        calls += 1
        seenPresetIds += preset.id
        firstStarted.complete(Unit)
        onProgress(PresetPipeline.Step.Decoding)
        gate.await()
        val file = File(context.cacheDir, "race-${System.nanoTime()}.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        return PresetPipeline.Result.Success(
            OutputStore.StoredItem(jobId, file, file.name, file.length(), "image/jpeg"),
            source,
            finalWidth = 100,
            finalHeight = 80,
            format = EncodeFormat.JPEG,
        )
    }
}

private class RacePresetRepository : PresetRepository {
    private val selected = MutableStateFlow(DefaultPresets.SmallFile.id)
    override fun observePresets(): Flow<List<Preset>> = flowOf(DefaultPresets.ALL)
    override fun observeDefaultPresetId(): Flow<String> = selected
    override suspend fun setDefaultPresetId(id: String) { selected.value = id }
    override suspend fun getPreset(id: String): Preset? = DefaultPresets.ALL.firstOrNull { it.id == id }
}

private class StaticSharedIntakeRepository(private val staged: List<SourceItem>) : SharedIntakeRepository {
    override suspend fun stage(jobId: String, uris: List<Uri>): List<SourceItem> = staged
    override suspend fun sweep() = Unit
}

private class RaceBatchWorkScheduler : BatchWorkScheduler {
    override fun enqueue(jobId: String, presetId: String, customOverrideJson: String?): Flow<BatchWorkStatus> =
        flowOf(BatchWorkStatus(BatchWorkState.Succeeded))
    override fun observe(jobId: String): Flow<BatchWorkStatus> = flowOf(BatchWorkStatus(BatchWorkState.Succeeded))
    override fun cancel(jobId: String) = Unit
}
