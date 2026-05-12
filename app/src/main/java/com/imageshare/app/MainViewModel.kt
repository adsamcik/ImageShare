@file:Suppress("LongParameterList", "ReturnCount", "MaxLineLength")

package com.imageshare.app

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.imageshare.app.processing.PresetPipeline
import com.imageshare.app.processing.PresetPipelineRunner
import com.imageshare.core.io.InputCoordinator
import com.imageshare.core.io.OutputStore
import com.imageshare.core.io.ShareLauncher
import com.imageshare.core.io.SharedIntakeStager
import com.imageshare.core.io.SourceItem
import com.imageshare.core.processing.EncodeError
import com.imageshare.feature.preset.AlphaFallback
import com.imageshare.feature.preset.DefaultPresets
import com.imageshare.feature.preset.OutputFormat
import com.imageshare.feature.preset.Preset
import com.imageshare.feature.preset.PresetRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
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
    private val outputStore: OutputStore = AppContainer.outputStore,
    private val shareLauncher: ShareLauncher = AppContainer.shareLauncher,
    private val inputCoordinator: InputCoordinator = InputCoordinator(appContext.contentResolver),
    private val sharedIntakeRepositoryFactory: (ContentResolver, File) -> SharedIntakeRepository = ::AndroidSharedIntakeRepository,
    private val pipelineFactory: () -> PresetPipelineRunner = {
        PresetPipeline(appContext.contentResolver, outputStore)
    },
    private val processingDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val sharedSources = MutableStateFlow<List<SourceItem>>(emptyList())
    private val pickedSources = MutableStateFlow<List<SourceItem>>(emptyList())
    private val mutableProcessingState = MutableStateFlow<ProcessingState>(ProcessingState.Idle)
    private val mutableShareEvents = MutableSharedFlow<Intent>(extraBufferCapacity = 1)

    val presets: StateFlow<List<Preset>> = presetRepository.observePresets()
        .stateIn(viewModelScope, SharingStarted.Eagerly, DefaultPresets.ALL)

    val selectedPresetId: StateFlow<String> = presetRepository.observeDefaultPresetId()
        .stateIn(viewModelScope, SharingStarted.Eagerly, DefaultPresets.DEFAULT_PRESET_ID)

    val sources: StateFlow<List<SourceItem>> = combine(sharedSources, pickedSources) { shared, picked ->
        shared + picked
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val processingState: StateFlow<ProcessingState> = mutableProcessingState.asStateFlow()
    val shareEvents: SharedFlow<Intent> = mutableShareEvents.asSharedFlow()

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
        viewModelScope.launch {
            val results = processSources(currentSources, preset, newJobId())
            finishProcessing(results)
        }
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

        viewModelScope.launch {
            val rerunResults = processSources(conflicts.map { it.before }, preset, newJobId())
            finishProcessing(retained + rerunResults)
        }
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

    private suspend fun processSources(
        pendingSources: List<SourceItem>,
        preset: Preset,
        jobId: String,
    ): List<PresetPipeline.Result> = withContext(processingDispatcher) {
        val runner = pipelineFactory()
        pendingSources.mapIndexed { index, source ->
            runner.run(source, preset, jobId) { step ->
                mutableProcessingState.value = ProcessingState.Running(index + 1, pendingSources.size, step)
            }
        }
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
    data class Running(
        val currentIndex: Int,
        val total: Int,
        val step: PresetPipeline.Step,
    ) : ProcessingState

    data class Done(
        val results: List<PresetPipeline.Result>,
        val alphaConflictCount: Int = 0,
    ) : ProcessingState
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
