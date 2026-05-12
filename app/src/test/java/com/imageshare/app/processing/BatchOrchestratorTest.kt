package com.imageshare.app.processing

import android.net.Uri
import com.imageshare.core.io.OutputStore
import com.imageshare.core.io.SourceItem
import com.imageshare.core.processing.EncodeError
import com.imageshare.core.processing.EncodeFormat
import com.imageshare.feature.preset.DefaultPresets
import com.imageshare.feature.preset.Preset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class BatchOrchestratorTest {
    private val dispatcher = StandardTestDispatcher()
    private val preset = DefaultPresets.SmallFile
    private val sources = listOf(
        source("one"),
        source("two"),
        source("three"),
    )

    @Test
    fun threeSourcesFinishWithAllItemsDone() = runTest(dispatcher) {
        val pipeline = FakePipeline()
        val emissions = mutableListOf<BatchOrchestrator.BatchProgress>()

        BatchOrchestrator(pipeline, dispatcher).run("job", sources, preset).collect { emissions.add(it) }

        val final = emissions.last()
        assertEquals(3, final.items.count { it.state is BatchOrchestrator.ItemState.Done })
        assertEquals(sources, pipeline.calls)
    }

    @Test
    fun cancellationMarksUnfinishedItemsCancelledInFinalEmission() = runTest(dispatcher) {
        val pipeline = FakePipeline { source, _, _ ->
            if (source.displayName == "two.jpg") throw CancellationException("stop")
            success(source)
        }
        val emissions = mutableListOf<BatchOrchestrator.BatchProgress>()

        BatchOrchestrator(pipeline, dispatcher).run("job", sources, preset)
            .catch { error -> assertTrue(error is CancellationException) }
            .collect { emissions.add(it) }

        val final = emissions.last()
        assertEquals(1, final.items.count { it.state is BatchOrchestrator.ItemState.Done })
        assertEquals(2, final.items.count { it.state is BatchOrchestrator.ItemState.Cancelled })
        assertTrue(final.cancelled)
    }

    @Test
    fun progressIncludesEveryStepTransition() = runTest(dispatcher) {
        val emissions = mutableListOf<BatchOrchestrator.BatchProgress>()

        BatchOrchestrator(FakePipeline(), dispatcher)
            .run("job", listOf(sources.first()), preset)
            .collect { emissions.add(it) }

        val runningSteps = emissions.mapNotNull { progress ->
            (progress.items.single().state as? BatchOrchestrator.ItemState.Running)?.step
        }
        assertEquals(
            listOf(
                PresetPipeline.Step.Decoding,
                PresetPipeline.Step.Resizing,
                PresetPipeline.Step.Encoding,
                PresetPipeline.Step.ApplyingMetadata,
                PresetPipeline.Step.Storing,
            ),
            runningSteps.distinct(),
        )
    }

    @Test
    fun failureResultIsDoneAndBatchContinues() = runTest(dispatcher) {
        val pipeline = FakePipeline { source, _, _ ->
            if (source.displayName == "two.jpg") {
                PresetPipeline.Result.Failure(
                    source,
                    EncodeError.AlphaConflict(EncodeFormat.JPEG),
                    PresetPipeline.Step.Encoding,
                )
            } else {
                success(source)
            }
        }
        val emissions = mutableListOf<BatchOrchestrator.BatchProgress>()

        BatchOrchestrator(pipeline, dispatcher).run("job", sources, preset).collect { emissions.add(it) }

        val doneResults = emissions.last().items.map { (it.state as BatchOrchestrator.ItemState.Done).result }
        assertEquals(2, doneResults.count { it is PresetPipeline.Result.Success })
        assertEquals(1, doneResults.count { it is PresetPipeline.Result.Failure })
        assertEquals(3, pipeline.calls.size)
    }

    @Test
    fun emptySourceListEmitsFinishedEmptyProgress() = runTest(dispatcher) {
        val emissions = mutableListOf<BatchOrchestrator.BatchProgress>()

        BatchOrchestrator(FakePipeline(), dispatcher).run("job", emptyList(), preset).collect { emissions.add(it) }

        assertEquals(1, emissions.size)
        assertTrue(emissions.single().finished)
        assertTrue(emissions.single().items.isEmpty())
    }

    @Test
    fun coldFlowRerunsPipelineForEachCollector() = runTest(dispatcher) {
        val pipeline = FakePipeline()
        val flow = BatchOrchestrator(pipeline, dispatcher).run("job", listOf(sources.first()), preset)

        flow.collect { }
        flow.collect { }

        assertEquals(2, pipeline.calls.size)
    }

    private fun source(name: String) = SourceItem(
        uri = Uri.parse("content://images/$name"),
        mimeType = "image/jpeg",
        displayName = "$name.jpg",
        sizeBytes = 10L,
        width = 100,
        height = 80,
    )

    private class FakePipeline(
        private val result: suspend (SourceItem, Preset, String) -> PresetPipeline.Result = { source, _, _ ->
            success(source)
        },
    ) : PresetPipelineRunner {
        val calls = mutableListOf<SourceItem>()

        override suspend fun run(
            source: SourceItem,
            preset: Preset,
            jobId: String,
            onProgress: (PresetPipeline.Step) -> Unit,
        ): PresetPipeline.Result {
            calls.add(source)
            listOf(
                PresetPipeline.Step.Decoding,
                PresetPipeline.Step.Resizing,
                PresetPipeline.Step.Encoding,
                PresetPipeline.Step.ApplyingMetadata,
                PresetPipeline.Step.Storing,
            ).forEach(onProgress)
            return result(source, preset, jobId)
        }
    }

    companion object {
        fun success(source: SourceItem): PresetPipeline.Result.Success {
            val file = File("build\\test-output-${source.displayName}")
            val stored = OutputStore.StoredItem("job", file, file.name, 10L, "image/jpeg")
            return PresetPipeline.Result.Success(stored, source, 100, 80, EncodeFormat.JPEG)
        }
    }
}
