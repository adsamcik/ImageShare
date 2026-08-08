package com.imageshare.app.processing

import com.imageshare.core.io.SourceItem
import com.imageshare.feature.preset.Preset
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

class BatchOrchestrator(
    private val pipeline: PresetPipelineRunner,
    private val processingDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    sealed class ItemState {
        data object Pending : ItemState()
        data class Running(val step: PresetPipeline.Step) : ItemState()
        data class Done(val result: PresetPipeline.Result) : ItemState()
        data object Cancelled : ItemState()
    }

    data class BatchProgress(
        val jobId: String,
        val items: List<Item>,
        val cancelled: Boolean = false,
    ) {
        data class Item(val source: SourceItem, val state: ItemState)
        val completed: Int get() = items.count { it.state is ItemState.Done || it.state is ItemState.Cancelled }
        val total: Int get() = items.size
        val finished: Boolean get() = completed == total
    }

    fun run(
        jobId: String,
        sources: List<SourceItem>,
        preset: Preset,
        onProgressSnapshot: ((BatchProgress) -> Unit)? = null,
    ): Flow<BatchProgress> = channelFlow {
        var progress = BatchProgress(
            jobId = jobId,
            items = sources.map { BatchProgress.Item(it, ItemState.Pending) },
        )

        fun publish(updatedProgress: BatchProgress) {
            // The worker needs a synchronous snapshot so cancellation cleanup never loses a completed
            // item behind a coalesced Flow buffer.
            onProgressSnapshot?.invoke(updatedProgress)
            trySend(updatedProgress)
        }

        fun withState(index: Int, state: ItemState, cancelled: Boolean = progress.cancelled): BatchProgress {
            progress = progress.copy(
                items = progress.items.mapIndexed { itemIndex, item ->
                    if (itemIndex == index) item.copy(state = state) else item
                },
                cancelled = cancelled,
            )
            return progress
        }

        fun cancelledProgress(): BatchProgress {
            progress = progress.copy(
                items = progress.items.map { item ->
                    when (item.state) {
                        is ItemState.Done, ItemState.Cancelled -> item
                        ItemState.Pending, is ItemState.Running -> item.copy(state = ItemState.Cancelled)
                    }
                },
                cancelled = true,
            )
            return progress
        }

        publish(progress)
        try {
            sources.forEachIndexed { index, source ->
                coroutineContext.ensureActive()
                publish(withState(index, ItemState.Running(PresetPipeline.Step.Decoding)))
                val result = pipeline.run(source, preset, jobId) { step ->
                    publish(withState(index, ItemState.Running(step)))
                }
                publish(withState(index, ItemState.Done(result)))
            }
        } finally {
            if (!progress.finished) {
                withContext(NonCancellable) {
                    publish(cancelledProgress())
                }
            }
        }
    }.flowOn(processingDispatcher)
}
