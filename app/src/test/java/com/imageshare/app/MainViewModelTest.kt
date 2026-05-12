@file:Suppress("MaxLineLength")

package com.imageshare.app

import android.content.Intent
import android.net.Uri
import com.imageshare.app.processing.PresetPipeline
import com.imageshare.app.processing.PresetPipelineRunner
import com.imageshare.core.io.OutputStore
import com.imageshare.core.io.ShareLauncher
import com.imageshare.core.io.ShareUriResolver
import com.imageshare.core.io.SourceItem
import com.imageshare.core.processing.EncodeError
import com.imageshare.core.processing.EncodeFormat
import com.imageshare.feature.preset.AlphaFallback
import com.imageshare.feature.preset.DefaultPresets
import com.imageshare.feature.preset.Preset
import com.imageshare.feature.preset.PresetRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
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
        val viewModel = viewModel(context) { _, preset, _ -> success(context, preset) }
        stage(viewModel, source)
        advanceUntilIdle()

        val stateJob = launch { viewModel.processingState.collect { states.add(it) } }
        val eventJob = launch { viewModel.shareEvents.collect { events.add(it) } }
        viewModel.onProcessAndShare()
        advanceUntilIdle()

        assertTrue(states.any { it is ProcessingState.Running })
        val done = viewModel.processingState.value as ProcessingState.Done
        assertEquals(1, done.results.filterIsInstance<PresetPipeline.Result.Success>().size)
        assertEquals(Intent.ACTION_SEND, events.single().action)
        stateJob.cancel()
        eventJob.cancel()
    }

    @Test
    fun alphaConflictWaitsForUserChoiceThenRerunsOnlyFailures() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val events = mutableListOf<Intent>()
        var calls = 0
        val viewModel = viewModel(context) { before, preset, _ ->
            calls += 1
            if (preset.alphaFallback == AlphaFallback.Error) {
                PresetPipeline.Result.Failure(before, EncodeError.AlphaConflict(EncodeFormat.JPEG), PresetPipeline.Step.Encoding)
            } else {
                success(context, preset)
            }
        }
        viewModel.onPresetSelected(DefaultPresets.BestQuality.id)
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
        assertEquals(1, events.size)
        eventJob.cancel()
    }

    private fun viewModel(
        context: android.content.Context,
        result: suspend (SourceItem, Preset, String) -> PresetPipeline.Result,
    ): MainViewModel = MainViewModel(
        appContext = context,
        presetRepository = FakePresetRepository(),
        outputStore = OutputStore(context.cacheDir),
        shareLauncher = ShareLauncher(ShareUriResolver { _, file -> Uri.parse("content://share/${file.name}") }),
        sharedIntakeRepositoryFactory = { _, _ -> FakeSharedIntakeRepository(listOf(source)) },
        pipelineFactory = { FakePipelineRunner(result) },
        processingDispatcher = mainDispatcherRule.dispatcher,
    )

    private suspend fun stage(viewModel: MainViewModel, sourceItem: SourceItem) {
        viewModel.stageSharedUris("job", listOf(sourceItem.uri), FakeSharedIntakeRepository(listOf(sourceItem)))
    }

    private fun success(context: android.content.Context, preset: Preset): PresetPipeline.Result.Success {
        val file = File(context.cacheDir, "${preset.id}-${System.nanoTime()}.jpg").apply { writeBytes(byteArrayOf(1, 2)) }
        val stored = OutputStore.StoredItem("job", file, file.name, file.length(), "image/jpeg")
        return PresetPipeline.Result.Success(stored, source, 100, 80, EncodeFormat.JPEG)
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
