@file:Suppress("LongParameterList", "ReturnCount", "MaxLineLength", "TooManyFunctions")

package com.imageshare.app

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.imageshare.app.processing.BatchOrchestrator
import com.imageshare.app.saving.PersistentSaver
import com.imageshare.core.io.MediaStoreSaver
import com.imageshare.app.processing.PresetPipeline
import com.imageshare.core.io.InputCoordinator
import com.imageshare.core.io.ShareLauncher
import com.imageshare.core.io.SharedIntakeStager
import com.imageshare.core.io.SourceItem
import com.imageshare.core.processing.EncodeError
import com.imageshare.feature.preset.AlphaFallback
import com.imageshare.feature.preset.DefaultPresets
import com.imageshare.feature.preset.OutputFormat
import com.imageshare.feature.preset.Preset
import com.imageshare.feature.preset.PresetRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainViewModel(
    private val appContext: Context,
    private val presetRepository: PresetRepository = AppContainer.presetRepository,
    private val shareLauncher: ShareLauncher = AppContainer.shareLauncher,
    private val saver: PersistentSaver = AppContainer.persistentSaver,
    private val inputCoordinator: InputCoordinator = InputCoordinator(appContext.contentResolver),
    private val sharedIntakeRepositoryFactory: (ContentResolver, File) -> SharedIntakeRepository = ::AndroidSharedIntakeRepository,
    private val batchOrchestrator: BatchOrchestrator = AppContainer.batchOrchestrator,
) : ViewModel() {
    private val sharedSources = MutableStateFlow<List<SourceItem>>(emptyList())
    private val pickedSources = MutableStateFlow<List<SourceItem>>(emptyList())
    private val mutableProcessingState = MutableStateFlow<ProcessingState>(ProcessingState.Idle)
    private val mutableShareEvents = MutableSharedFlow<Intent>(extraBufferCapacity = 1)
    private val mutableSaveDocumentEvents = MutableSharedFlow<Intent>(extraBufferCapacity = 1)
    private val mutablePendingSingleSourceFile = MutableStateFlow<File?>(null)
    private val mutableSaveStatus = MutableStateFlow<SaveStatus>(SaveStatus.Idle)
    private var currentBatchJob: Job? = null

    val presets: StateFlow<List<Preset>> = presetRepository.observePresets()
        .stateIn(viewModelScope, SharingStarted.Eagerly, DefaultPresets.ALL)

    val selectedPresetId: StateFlow<String> = presetRepository.observeDefaultPresetId()
        .stateIn(viewModelScope, SharingStarted.Eagerly, DefaultPresets.DEFAULT_PRESET_ID)

    val sources: StateFlow<List<SourceItem>> = combine(sharedSources, pickedSources) { shared, picked ->
        shared + picked
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val processingState: StateFlow<ProcessingState> = mutableProcessingState.asStateFlow()
    val shareEvents: SharedFlow<Intent> = mutableShareEvents.asSharedFlow()
    val saveDocumentEvents: SharedFlow<Intent> = mutableSaveDocumentEvents.asSharedFlow()
    val saveStatus: StateFlow<SaveStatus> = mutableSaveStatus.asStateFlow()

    fun onPresetSelected(presetId: String) {
        viewModelScope.launch {
            presetRepository.setDefaultPresetId(presetId)
        }
    }

    fun onPickFromGallery() = Unit

    fun onPickerResult(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val resolved = withContext(Dispatchers.IO) {
                uris.mapNotNull { uri -> runCatching { inputCoordinator.resolve(uri) }.getOrNull() }
            }
            pickedSources.value = pickedSources.value + resolved
            mutableProcessingState.value = ProcessingState.Idle
        }
    }

    fun onProcessAndShare() {
        val currentSources = sources.value
        if (currentSources.isEmpty() || mutableProcessingState.value is ProcessingState.Running) return
        val preset = selectedPreset() ?: return
        startBatch(currentSources, preset)
    }

    fun onCancelBatch() {
        val partial = currentSuccessfulResults()
        currentBatchJob?.cancel()
        currentBatchJob = null
        mutableProcessingState.value = ProcessingState.Cancelled(partial)
    }

    fun onSaveCopy() {
        val results = (processingState.value as? ProcessingState.Done)
            ?.results
            ?.filterIsInstance<PresetPipeline.Result.Success>()
            ?.map { it.stored }
            ?: return
        if (results.isEmpty()) return
        viewModelScope.launch {
            when (val outcome = saver.planSave(results)) {
                is PersistentSaver.Outcome.SingleStarted -> {
                    mutablePendingSingleSourceFile.value = outcome.pendingFile
                    mutableSaveDocumentEvents.tryEmit(outcome.intent)
                }
                is PersistentSaver.Outcome.BatchCompleted -> {
                    mutableSaveStatus.value = SaveStatus.BatchDone(outcome.result)
                }
            }
        }
    }

    fun onSaveDocumentResult(destUri: Uri?) {
        val pending = mutablePendingSingleSourceFile.value ?: return
        mutablePendingSingleSourceFile.value = null
        if (destUri == null) {
            mutableSaveStatus.value = SaveStatus.Cancelled
            return
        }
        viewModelScope.launch {
            val written = runCatching { saver.copyToUri(destUri, pending) }
            mutableSaveStatus.value = written.fold(
                onSuccess = { bytes -> SaveStatus.SingleDone(destUri, bytes) },
                onFailure = { error -> SaveStatus.Failed(error) },
            )
        }
    }

    fun onSaveStatusShown() {
        mutableSaveStatus.value = SaveStatus.Idle
    }

    fun resolveAlphaConflicts(strategy: AlphaConflictStrategy) {
        val done = mutableProcessingState.value as? ProcessingState.Done ?: return
        val conflicts = done.results.filterIsInstance<PresetPipeline.Result.Failure>()
            .filter { it.isAlphaConflict() }
        if (conflicts.isEmpty()) return

        val retained = done.results.filterNot { result ->
            result is PresetPipeline.Result.Failure && result.isAlphaConflict()
        }
        if (strategy == AlphaConflictStrategy.Skip) {
            finishProcessing(retained)
            return
        }

        val preset = when (strategy) {
            AlphaConflictStrategy.UseWhiteBackground -> selectedPreset()?.copy(alphaFallback = AlphaFallback.FillWhite)
            AlphaConflictStrategy.SwitchToPng -> selectedPreset()?.copy(
                format = OutputFormat.PNG,
                alphaFallback = AlphaFallback.SwitchToPng,
            )
            AlphaConflictStrategy.Skip -> null
        } ?: return

        startBatch(conflicts.map { it.before }, preset, retained)
    }

    suspend fun stageSharedUris(
        jobId: String,
        uris: List<Uri>,
        repository: SharedIntakeRepository = sharedIntakeRepositoryFactory(
            appContext.contentResolver,
            appContext.cacheDir.resolve(SHARED_INTAKE_DIR),
        ),
    ) {
        val staged = repository.stage(jobId, uris)
        sharedSources.value = staged
        repository.sweep()
        mutableProcessingState.value = ProcessingState.Idle
    }

    private fun startBatch(
        pendingSources: List<SourceItem>,
        preset: Preset,
        retainedResults: List<PresetPipeline.Result> = emptyList(),
    ) {
        currentBatchJob?.cancel()
        currentBatchJob = viewModelScope.launch {
            try {
                val results = processSources(pendingSources, preset, newJobId())
                finishProcessing(retainedResults + results)
            } finally {
                if (currentBatchJob === coroutineContext[Job]) {
                    currentBatchJob = null
                }
            }
        }
    }

    private suspend fun processSources(
        pendingSources: List<SourceItem>,
        preset: Preset,
        jobId: String,
    ): List<PresetPipeline.Result> {
        var latest = BatchOrchestrator.BatchProgress(
            jobId = jobId,
            items = pendingSources.map {
                BatchOrchestrator.BatchProgress.Item(it, BatchOrchestrator.ItemState.Pending)
            },
        )
        batchOrchestrator.run(jobId, pendingSources, preset).collect { progress ->
            latest = progress
            mutableProcessingState.value = ProcessingState.Running(progress)
        }
        return latest.items.mapNotNull { (it.state as? BatchOrchestrator.ItemState.Done)?.result }
    }

    private fun finishProcessing(results: List<PresetPipeline.Result>) {
        mutableProcessingState.value = ProcessingState.Done(
            results = results,
            alphaConflictCount = results.count { result ->
                result is PresetPipeline.Result.Failure && result.isAlphaConflict()
            },
        )
        if ((mutableProcessingState.value as ProcessingState.Done).alphaConflictCount == 0) {
            emitShareFor(results)
        }
    }

    private fun emitShareFor(results: List<PresetPipeline.Result>) {
        val stored = results.filterIsInstance<PresetPipeline.Result.Success>().map { it.stored }
        if (stored.isNotEmpty()) {
            mutableShareEvents.tryEmit(shareLauncher.buildShareIntent(appContext, stored))
        }
    }

    private fun selectedPreset(): Preset? = presets.value.firstOrNull { it.id == selectedPresetId.value }

    private fun currentSuccessfulResults(): List<PresetPipeline.Result.Success> {
        val running = mutableProcessingState.value as? ProcessingState.Running ?: return emptyList()
        return running.progress.items.mapNotNull { item ->
            (item.state as? BatchOrchestrator.ItemState.Done)?.result as? PresetPipeline.Result.Success
        }
    }

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(MainViewModel::class.java))
            return MainViewModel(context.applicationContext) as T
        }
    }
}

sealed interface ProcessingState {
    data object Idle : ProcessingState
    data class Running(val progress: BatchOrchestrator.BatchProgress) : ProcessingState

    data class Done(
        val results: List<PresetPipeline.Result>,
        val alphaConflictCount: Int = 0,
    ) : ProcessingState

    data class Cancelled(val partial: List<PresetPipeline.Result.Success>) : ProcessingState
}

sealed interface SaveStatus {
    data object Idle : SaveStatus
    data class BatchDone(val result: MediaStoreSaver.SaveAllResult) : SaveStatus
    data class SingleDone(val uri: Uri, val bytes: Long) : SaveStatus
    data object Cancelled : SaveStatus
    data class Failed(val cause: Throwable) : SaveStatus
}

enum class AlphaConflictStrategy { UseWhiteBackground, SwitchToPng, Skip }

interface SharedIntakeRepository {
    suspend fun stage(jobId: String, uris: List<Uri>): List<SourceItem>
    suspend fun sweep()
}

private class AndroidSharedIntakeRepository(
    private val resolver: ContentResolver,
    private val cacheRoot: File,
) : SharedIntakeRepository {
    override suspend fun stage(jobId: String, uris: List<Uri>): List<SourceItem> {
        val stager = SharedIntakeStager(resolver, cacheRoot)
        val coordinator = InputCoordinator(resolver)
        return stager.stage(jobId, uris).mapNotNull { staged ->
            runCatching { coordinator.resolve(staged.cachedUri) }
                .map { resolved ->
                    resolved.copy(
                        displayName = resolved.displayName ?: staged.displayName,
                        sizeBytes = resolved.sizeBytes ?: staged.sizeBytes ?: staged.cachedFile.length(),
                    )
                }
                .getOrNull()
        }
    }

    override suspend fun sweep() {
        SharedIntakeStager(resolver, cacheRoot).sweep()
    }
}

private fun PresetPipeline.Result.Failure.isAlphaConflict(): Boolean = cause is EncodeError.AlphaConflict

private fun newJobId(): String = "share-${System.currentTimeMillis()}-${(0 until SHARE_RANDOM_BOUND).random()}"

private const val SHARE_RANDOM_BOUND = 10_000
