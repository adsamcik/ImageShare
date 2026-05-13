@file:Suppress("MaxLineLength")

package com.imageshare.app

import android.content.Intent
import android.net.Uri
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.imageshare.app.processing.BatchOrchestrator
import com.imageshare.app.saving.PersistentSaver
import com.imageshare.core.io.MediaStoreSaver
import com.imageshare.app.processing.PresetPipeline
import com.imageshare.app.processing.PresetPipelineRunner
import com.imageshare.core.io.OutputStore
import com.imageshare.core.io.PersistableUriRegistry
import com.imageshare.core.io.ShareLauncher
import com.imageshare.core.io.ShareUriResolver
import com.imageshare.core.io.SourceItem
import com.imageshare.core.processing.EncodeError
import com.imageshare.core.processing.EncodeFormat
import com.imageshare.feature.preset.AlphaFallback
import com.imageshare.feature.preset.DefaultPresets
import com.imageshare.feature.preset.Preset
import com.imageshare.feature.preset.PresetRepository
import com.imageshare.feature.preset.ResizeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class MainViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val source = SourceItem(
        uri = Uri.parse("content://images/one"),
        mimeType = "image/jpeg",
        displayName = "one.jpg",
        sizeBytes = 10L,
        width = 100,
        height = 80,
    )

    @Test
    fun processAndShareTransitionsFromIdleToRunningToDone() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val states = mutableListOf<ProcessingState>()
        val events = mutableListOf<Intent>()
        val second = source.copy(uri = Uri.parse("content://images/two"), displayName = "two.jpg")
        val viewModel = viewModel(context) { before, preset, _ -> success(context, preset, before) }
        stage(viewModel, source, second)
        advanceUntilIdle()

        val stateJob = launch { viewModel.processingState.collect { states.add(it) } }
        val eventJob = launch { viewModel.shareEvents.collect { events.add(it) } }
        advanceUntilIdle()
        viewModel.onProcessAndShare()
        advanceUntilIdle()

        assertTrue(states.any { it is ProcessingState.Done })
        val done = viewModel.processingState.value as ProcessingState.Done
        assertEquals(2, done.results.filterIsInstance<PresetPipeline.Result.Success>().size)
        assertEquals(Intent.ACTION_SEND_MULTIPLE, events.single().action)
        stateJob.cancel()
        eventJob.cancel()
    }

    @Test
    fun cancelBatchCancelsInFlightJobAndKeepsPartialSuccesses() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val second = source.copy(uri = Uri.parse("content://images/two"), displayName = "two.jpg")
        val viewModel = viewModel(context) { before, preset, _ ->
            if (before == second) awaitCancellation()
            success(context, preset, before)
        }
        stage(viewModel, source, second)
        advanceUntilIdle()

        viewModel.onProcessAndShare()
        advanceUntilIdle()
        viewModel.onCancelBatch()
        advanceUntilIdle()

        val cancelled = viewModel.processingState.value as ProcessingState.Cancelled
        assertEquals(1, cancelled.partial.size)
    }

    @Test
    fun alphaConflictWaitsForUserChoiceThenRerunsOnlyFailures() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val events = mutableListOf<Intent>()
        val seenPresets = mutableListOf<Preset>()
        var calls = 0
        val viewModel = viewModel(context) { before, preset, _ ->
            calls += 1
            seenPresets += preset
            if (preset.alphaFallback == AlphaFallback.Error) {
                PresetPipeline.Result.Failure(before, EncodeError.AlphaConflict(EncodeFormat.JPEG), PresetPipeline.Step.Encoding)
            } else {
                success(context, preset)
            }
        }
        viewModel.onPresetSelected(DefaultPresets.BestQuality.id)
        val override = MainViewModel.CustomOverride(ResizeMode.Exact(200, 160), allowUpscale = false)
        viewModel.onCustomOverride(override)
        advanceUntilIdle()
        stage(viewModel, source)
        advanceUntilIdle()
        val eventJob = launch { viewModel.shareEvents.collect { events.add(it) } }

        viewModel.onProcessAndShare()
        advanceUntilIdle()

        val conflictDone = viewModel.processingState.value as ProcessingState.Done
        assertEquals(1, conflictDone.alphaConflictCount)
        assertTrue(events.isEmpty())

        viewModel.resolveAlphaConflicts(AlphaConflictStrategy.UseWhiteBackground)
        advanceUntilIdle()

        val resolvedDone = viewModel.processingState.value as ProcessingState.Done
        assertEquals(0, resolvedDone.alphaConflictCount)
        assertEquals(2, calls)
        assertEquals(override.resize, seenPresets[0].resize)
        assertEquals(override.resize, seenPresets[1].resize)
        assertEquals(1, events.size)
        eventJob.cancel()
    }

    @Test
    fun customOverrideUpdatesEffectivePreset() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val viewModel = viewModel(context) { before, preset, _ -> success(context, preset, before) }
        advanceUntilIdle()

        viewModel.onCustomOverride(MainViewModel.CustomOverride(ResizeMode.Percentage(50), allowUpscale = false))
        advanceUntilIdle()

        assertEquals(ResizeMode.Percentage(50), viewModel.effectivePreset.value?.resize)
    }

    @Test
    fun clearingCustomOverrideRevertsToBasePreset() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val viewModel = viewModel(context) { before, preset, _ -> success(context, preset, before) }
        advanceUntilIdle()
        viewModel.onCustomOverride(MainViewModel.CustomOverride(ResizeMode.Exact(320, 240), allowUpscale = false))
        advanceUntilIdle()

        viewModel.onCustomOverride(null)
        advanceUntilIdle()

        assertEquals(DefaultPresets.SmallFile.resize, viewModel.effectivePreset.value?.resize)
    }

    @Test
    fun saveCopyForSingleResultEmitsCreateDocumentEvent() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val events = mutableListOf<Intent>()
        val viewModel = viewModel(context) { before, preset, _ -> success(context, preset, before) }
        stage(viewModel, source)
        advanceUntilIdle()
        viewModel.onProcessAndShare()
        advanceUntilIdle()
        val eventJob = launch { viewModel.saveDocumentEvents.collect { events.add(it) } }

        viewModel.onSaveCopy()
        advanceUntilIdle()

        assertEquals(Intent.ACTION_CREATE_DOCUMENT, events.single().action)
        assertEquals("image/jpeg", events.single().type)
        eventJob.cancel()
    }

    @Test
    fun saveDocumentResultUpdatesStatus() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val viewModel = viewModel(context) { before, preset, _ -> success(context, preset, before) }
        stage(viewModel, source)
        advanceUntilIdle()
        viewModel.onProcessAndShare()
        advanceUntilIdle()
        val eventJob = launch { viewModel.saveDocumentEvents.collect {} }
        viewModel.onSaveCopy()
        advanceUntilIdle()

        viewModel.onSaveDocumentResult(null)
        advanceUntilIdle()

        assertEquals(SaveStatus.Cancelled, viewModel.saveStatus.value)
        eventJob.cancel()
    }

    @Test
    fun comparisonStateOpensAndClosesForResult() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val viewModel = viewModel(context) { before, preset, _ -> success(context, preset, before) }
        val result = success(context, DefaultPresets.SmallFile, source)

        viewModel.onExpandResult(result)

        assertEquals(source.uri, viewModel.shownComparison.value?.before)
        assertEquals(Uri.fromFile(result.stored.file), viewModel.shownComparison.value?.after)

        viewModel.onCloseComparison()

        assertEquals(null, viewModel.shownComparison.value)
    }

    private fun viewModel(
        context: android.content.Context,
        result: suspend (SourceItem, Preset, String) -> PresetPipeline.Result,
    ): MainViewModel = MainViewModel(
        appContext = context,
        presetRepository = FakePresetRepository(),
        shareLauncher = ShareLauncher(ShareUriResolver { _, file -> Uri.parse("content://share/${file.name}") }),
        saver = PersistentSaver(MediaStoreSaver(context.contentResolver), context.contentResolver),
        sharedIntakeRepositoryFactory = { _, _ -> FakeSharedIntakeRepository(listOf(source)) },
        batchOrchestrator = BatchOrchestrator(FakePipelineRunner(result), mainDispatcherRule.dispatcher),
        persistableUriRegistry = persistableUriRegistry(context),
    )

    private fun persistableUriRegistry(context: android.content.Context): PersistableUriRegistry {
        val file = File(context.cacheDir, "main-view-model-${System.nanoTime()}.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(
            scope = kotlinx.coroutines.CoroutineScope(Job() + Dispatchers.IO),
            produceFile = { file },
        )
        return PersistableUriRegistry(dataStore, context.contentResolver)
    }

    private suspend fun stage(viewModel: MainViewModel, vararg sourceItems: SourceItem) {
        viewModel.stageSharedUris("job", sourceItems.map { it.uri }, FakeSharedIntakeRepository(sourceItems.toList()))
    }

    private fun success(
        context: android.content.Context,
        preset: Preset,
        before: SourceItem = source,
    ): PresetPipeline.Result.Success {
        val file = File(context.cacheDir, "${preset.id}-${System.nanoTime()}.jpg").apply { writeBytes(byteArrayOf(1, 2)) }
        val stored = OutputStore.StoredItem("job", file, file.name, file.length(), "image/jpeg")
        return PresetPipeline.Result.Success(stored, before, 100, 80, EncodeFormat.JPEG)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule : TestWatcher() {
    val dispatcher = StandardTestDispatcher()

    override fun starting(description: Description) {
        Dispatchers.setMain(dispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}

private class FakePipelineRunner(
    private val result: suspend (SourceItem, Preset, String) -> PresetPipeline.Result,
) : PresetPipelineRunner {
    override suspend fun run(
        source: SourceItem,
        preset: Preset,
        jobId: String,
        onProgress: (PresetPipeline.Step) -> Unit,
    ): PresetPipeline.Result {
        onProgress(PresetPipeline.Step.Decoding)
        yield()
        return result(source, preset, jobId)
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
