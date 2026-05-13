@file:Suppress("LongParameterList", "ReturnCount", "MaxLineLength", "TooManyFunctions")

package com.imageshare.app

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.Observer
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.imageshare.app.data.BatchManifestDao
import com.imageshare.app.data.BatchManifestEntity
import com.imageshare.app.processing.BatchOrchestrator
import com.imageshare.app.saving.PersistentSaver
import com.imageshare.core.io.MediaStoreSaver
import com.imageshare.app.processing.PresetPipeline
import com.imageshare.core.io.InputCoordinator
import com.imageshare.core.io.PersistableUriRegistry
import com.imageshare.core.io.ShareLauncher
import com.imageshare.core.io.SharedIntakeStager
import com.imageshare.core.io.SourceItem
import com.imageshare.core.io.sourceItemFromPersistedUriString
import com.imageshare.core.io.toPersistedUriString
import com.imageshare.core.processing.EncodeError
import com.imageshare.core.processing.EncodeFormat
import com.imageshare.app.work.BatchProcessWorker
import com.imageshare.app.work.toWorkerJson
import com.imageshare.feature.preset.AlphaFallback
import com.imageshare.feature.preset.DefaultPresets
import com.imageshare.feature.preset.OutputFormat
import com.imageshare.feature.preset.Preset
import com.imageshare.feature.preset.PresetRepository
import com.imageshare.feature.preset.ResizeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class MainViewModel(
    private val appContext: Context,
    private val presetRepository: PresetRepository = AppContainer.presetRepository,
    private val shareLauncher: ShareLauncher = AppContainer.shareLauncher,
    private val saver: PersistentSaver = AppContainer.persistentSaver,
    private val inputCoordinator: InputCoordinator = InputCoordinator(appContext.contentResolver),
    private val sharedIntakeRepositoryFactory: (ContentResolver, File) -> SharedIntakeRepository = ::AndroidSharedIntakeRepository,
    private val batchOrchestrator: BatchOrchestrator = AppContainer.batchOrchestrator,
    private val persistableUriRegistry: PersistableUriRegistry = AppContainer.persistableUriRegistry,
    private val batchManifestDao: BatchManifestDao = AppContainer.batchManifestDao,
    private val batchWorkScheduler: BatchWorkScheduler = WorkManagerBatchWorkScheduler(appContext),
) : ViewModel() {
    private val sharedSources = MutableStateFlow<List<SourceItem>>(emptyList())
    private val pickedSources = MutableStateFlow<List<SourceItem>>(emptyList())
    private val mutableProcessingState = MutableStateFlow<ProcessingState>(ProcessingState.Idle)
    private val mutableShareEvents = MutableSharedFlow<Intent>(extraBufferCapacity = 1)
    private val mutableSaveDocumentEvents = MutableSharedFlow<Intent>(extraBufferCapacity = 1)
    private val mutablePendingSingleSourceFile = MutableStateFlow<File?>(null)
    private val mutableSaveStatus = MutableStateFlow<SaveStatus>(SaveStatus.Idle)
    private val _shownComparison = MutableStateFlow<ComparisonState?>(null)
    private val _customOverride = MutableStateFlow<CustomOverride?>(null)
    private val _runInBackground = MutableStateFlow(false)
    private var currentBatchJob: Job? = null
    private var activeBatch: ActiveBatch? = null
    private var activeWorkJobId: String? = null

    val presets: StateFlow<List<Preset>> = presetRepository.observePresets()
        .stateIn(viewModelScope, SharingStarted.Eagerly, DefaultPresets.ALL)

    val selectedPresetId: StateFlow<String> = presetRepository.observeDefaultPresetId()
        .stateIn(viewModelScope, SharingStarted.Eagerly, DefaultPresets.DEFAULT_PRESET_ID)

    val selectedPreset: StateFlow<Preset?> = combine(presets, selectedPresetId) { availablePresets, selectedId ->
        availablePresets.firstOrNull { it.id == selectedId } ?: availablePresets.firstOrNull()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val customOverride: StateFlow<CustomOverride?> = _customOverride.asStateFlow()
    val runInBackground: StateFlow<Boolean> = _runInBackground.asStateFlow()

    val effectivePreset: StateFlow<Preset?> = combine(selectedPreset, customOverride) { base, override ->
        if (base == null) {
            null
        } else if (override == null) {
            base
        } else {
            base.copy(resize = override.resize)
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val sources: StateFlow<List<SourceItem>> = combine(sharedSources, pickedSources) { shared, picked ->
        shared + picked
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val recentsUris: StateFlow<List<Uri>> = persistableUriRegistry.observe()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val processingState: StateFlow<ProcessingState> = mutableProcessingState.asStateFlow()
    val shareEvents: SharedFlow<Intent> = mutableShareEvents.asSharedFlow()
    val saveDocumentEvents: SharedFlow<Intent> = mutableSaveDocumentEvents.asSharedFlow()
    val saveStatus: StateFlow<SaveStatus> = mutableSaveStatus.asStateFlow()
    val shownComparison: StateFlow<ComparisonState?> = _shownComparison.asStateFlow()

    init {
        reattachToBackgroundBatch()
    }

    fun onPresetSelected(presetId: String) {
        viewModelScope.launch {
            presetRepository.setDefaultPresetId(presetId)
        }
    }

    fun onCustomOverride(override: CustomOverride?) {
        _customOverride.value = override
    }

    fun onRunInBackgroundChanged(enabled: Boolean) {
        _runInBackground.value = enabled
    }

    fun onPickFromGallery() = Unit

    fun onPickerResult(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            addPickedSources(uris)
            mutableProcessingState.value = ProcessingState.Idle
        }
    }

    fun onOpenDocumentResult(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                uris.forEach { uri -> persistableUriRegistry.add(uri) }
            }
            addPickedSources(uris)
            mutableProcessingState.value = ProcessingState.Idle
        }
    }

    fun onRecentSelected(uri: Uri) {
        viewModelScope.launch {
            addPickedSources(listOf(uri))
            mutableProcessingState.value = ProcessingState.Idle
        }
    }

    fun onRecentRemoved(uri: Uri) {
        viewModelScope.launch {
            persistableUriRegistry.remove(uri)
        }
    }

    fun onProcessAndShare() {
        val currentSources = sources.value
        if (currentSources.isEmpty() || mutableProcessingState.value is ProcessingState.Running) return
        val preset = effectivePreset.value ?: return
        val jobId = newJobId()
        if (shouldRunWithWorkManager(currentSources.size, _runInBackground.value)) {
            startWorkManagerBatch(jobId, currentSources, preset)
        } else {
            startBatch(currentSources, preset, jobId = jobId)
        }
    }

    fun onCancelBatch() {
        val partial = currentSuccessfulResults()
        activeWorkJobId?.let { batchWorkScheduler.cancel(it) }
        activeWorkJobId = null
        currentBatchJob?.cancel()
        currentBatchJob = null
        activeBatch = null
        mutableProcessingState.value = ProcessingState.Cancelled(partial)
    }

    fun onAppBackgrounded() {
        val batch = activeBatch ?: return
        if (activeWorkJobId != null) return
        val running = mutableProcessingState.value as? ProcessingState.Running ?: return
        startWorkManagerBatch(batch.jobId, batch.sources, batch.preset, running.progress)
    }

    fun shouldRunWithWorkManager(sourceCount: Int, runInBackground: Boolean): Boolean =
        sourceCount >= LARGE_BATCH_THRESHOLD || runInBackground

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

    fun onExpandResult(result: PresetPipeline.Result.Success) {
        _shownComparison.value = ComparisonState(result.before.uri, Uri.fromFile(result.stored.file))
    }

    fun onCloseComparison() {
        _shownComparison.value = null
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
            AlphaConflictStrategy.UseWhiteBackground -> effectivePreset.value?.copy(alphaFallback = AlphaFallback.FillWhite)
            AlphaConflictStrategy.SwitchToPng -> effectivePreset.value?.copy(
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

    private suspend fun addPickedSources(uris: List<Uri>) {
        val resolved = withContext(Dispatchers.IO) {
            uris.mapNotNull { uri -> runCatching { inputCoordinator.resolve(uri) }.getOrNull() }
        }
        pickedSources.value = pickedSources.value + resolved
    }

    private fun startBatch(
        pendingSources: List<SourceItem>,
        preset: Preset,
        retainedResults: List<PresetPipeline.Result> = emptyList(),
        jobId: String = newJobId(),
    ) {
        currentBatchJob?.cancel()
        activeBatch = ActiveBatch(jobId, pendingSources, preset)
        currentBatchJob = viewModelScope.launch {
            try {
                val results = processSources(pendingSources, preset, jobId)
                finishProcessing(retainedResults + results)
            } finally {
                if (currentBatchJob === coroutineContext[Job]) {
                    currentBatchJob = null
                    activeBatch = null
                }
            }
        }
    }

    private fun startWorkManagerBatch(
        jobId: String,
        pendingSources: List<SourceItem>,
        preset: Preset,
        currentProgress: BatchOrchestrator.BatchProgress? = null,
    ) {
        currentBatchJob?.cancel()
        currentBatchJob = null
        activeBatch = ActiveBatch(jobId, pendingSources, preset)
        activeWorkJobId = jobId
        currentBatchJob = viewModelScope.launch {
            seedManifest(jobId, pendingSources, currentProgress)
            batchWorkScheduler.enqueue(jobId, preset.id, customOverride.value?.toWorkerJson())
                .collect { status -> updateFromWorkStatus(jobId, status) }
        }
    }

    private suspend fun seedManifest(
        jobId: String,
        pendingSources: List<SourceItem>,
        currentProgress: BatchOrchestrator.BatchProgress?,
    ) = withContext(Dispatchers.IO) {
        val progressByUri = currentProgress?.items?.associateBy { it.source.uri }
        batchManifestDao.upsert(
            pendingSources.mapIndexed { idx, src ->
                val state = progressByUri?.get(src.uri)?.state
                BatchManifestEntity(
                    jobId = jobId,
                    sourceIndex = idx,
                    sourceUriString = src.toPersistedUriString(),
                    state = state.manifestState(),
                    storedFilePath = (state as? BatchOrchestrator.ItemState.Done)
                        ?.result
                        ?.let { it as? PresetPipeline.Result.Success }
                        ?.stored
                        ?.file
                        ?.absolutePath,
                    outputMimeType = (state as? BatchOrchestrator.ItemState.Done)
                        ?.result
                        ?.let { it as? PresetPipeline.Result.Success }
                        ?.stored
                        ?.mimeType,
                    errorMessage = (state as? BatchOrchestrator.ItemState.Done)
                        ?.result
                        ?.let { it as? PresetPipeline.Result.Failure }
                        ?.cause
                        ?.message,
                    updatedAt = System.currentTimeMillis(),
                )
            },
        )
    }

    private suspend fun updateFromWorkStatus(jobId: String, status: BatchWorkStatus) {
        val manifest = withContext(Dispatchers.IO) { batchManifestDao.forJob(jobId) }
        mutableProcessingState.value = ProcessingState.Running(manifest.toProgress(jobId))
        when (status.state) {
            BatchWorkState.Running,
            BatchWorkState.Enqueued,
            -> Unit
            BatchWorkState.Succeeded -> {
                activeWorkJobId = null
                currentBatchJob = null
                activeBatch = null
                finishProcessing(manifest.toResults())
            }
            BatchWorkState.Failed,
            BatchWorkState.Cancelled,
            -> {
                activeWorkJobId = null
                currentBatchJob = null
                activeBatch = null
                mutableProcessingState.value = ProcessingState.Cancelled(
                    manifest.toResults().filterIsInstance<PresetPipeline.Result.Success>(),
                )
            }
        }
    }

    private fun reattachToBackgroundBatch() {
        viewModelScope.launch {
            val pendingJobId = withContext(Dispatchers.IO) { batchManifestDao.pendingJobIds().firstOrNull() }
            if (pendingJobId != null) {
                activeWorkJobId = pendingJobId
                currentBatchJob = launch {
                    batchWorkScheduler.observe(pendingJobId).collect { status -> updateFromWorkStatus(pendingJobId, status) }
                }
                return@launch
            }

            val completedJobId = withContext(Dispatchers.IO) {
                batchManifestDao.jobIds().firstOrNull { id ->
                    batchManifestDao.forJob(id).all { it.state != BatchProcessWorker.STATE_PENDING }
                }
            } ?: return@launch
            val results = withContext(Dispatchers.IO) { batchManifestDao.forJob(completedJobId).toResults() }
            if (results.isNotEmpty()) finishProcessing(results)
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

    data class CustomOverride(
        val resize: ResizeMode,
        val allowUpscale: Boolean,
    )
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

data class ComparisonState(val before: Uri, val after: Uri)

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

private fun BatchOrchestrator.ItemState?.manifestState(): String = when (this) {
    is BatchOrchestrator.ItemState.Done -> when (result) {
        is PresetPipeline.Result.Success -> BatchProcessWorker.STATE_DONE
        is PresetPipeline.Result.Failure -> BatchProcessWorker.STATE_FAILED
    }
    BatchOrchestrator.ItemState.Cancelled -> BatchProcessWorker.STATE_CANCELLED
    BatchOrchestrator.ItemState.Pending,
    is BatchOrchestrator.ItemState.Running,
    null,
    -> BatchProcessWorker.STATE_PENDING
}

private fun List<BatchManifestEntity>.toProgress(jobId: String): BatchOrchestrator.BatchProgress =
    BatchOrchestrator.BatchProgress(
        jobId = jobId,
        items = sortedBy { it.sourceIndex }.map { entity ->
            BatchOrchestrator.BatchProgress.Item(
                source = sourceItemFromPersistedUriString(entity.sourceUriString),
                state = entity.toItemState(),
            )
        },
    )

private fun BatchManifestEntity.toItemState(): BatchOrchestrator.ItemState = when (state) {
    BatchProcessWorker.STATE_DONE,
    BatchProcessWorker.STATE_FAILED,
    -> BatchOrchestrator.ItemState.Done(toResult())
    BatchProcessWorker.STATE_CANCELLED -> BatchOrchestrator.ItemState.Cancelled
    else -> BatchOrchestrator.ItemState.Pending
}

private fun List<BatchManifestEntity>.toResults(): List<PresetPipeline.Result> =
    sortedBy { it.sourceIndex }
        .filter { it.state == BatchProcessWorker.STATE_DONE || it.state == BatchProcessWorker.STATE_FAILED }
        .map { it.toResult() }

private fun BatchManifestEntity.toResult(): PresetPipeline.Result {
    val source = sourceItemFromPersistedUriString(sourceUriString)
    if (state == BatchProcessWorker.STATE_DONE && storedFilePath != null) {
        val file = File(storedFilePath)
        val mimeType = outputMimeType ?: "image/jpeg"
        return PresetPipeline.Result.Success(
            stored = com.imageshare.core.io.OutputStore.StoredItem(
                jobId = jobId,
                file = file,
                filename = file.name,
                sizeBytes = file.length(),
                mimeType = mimeType,
            ),
            before = source,
            finalWidth = source.width ?: 0,
            finalHeight = source.height ?: 0,
            format = mimeType.toEncodeFormat(),
        )
    }
    return PresetPipeline.Result.Failure(
        before = source,
        cause = EncodeError.Invalid(errorMessage ?: "Background processing failed"),
        step = PresetPipeline.Step.Storing,
    )
}

private fun String.toEncodeFormat(): EncodeFormat = when {
    contains("png", ignoreCase = true) -> EncodeFormat.PNG
    contains("webp", ignoreCase = true) -> EncodeFormat.WEBP_LOSSY
    else -> EncodeFormat.JPEG
}

private data class ActiveBatch(
    val jobId: String,
    val sources: List<SourceItem>,
    val preset: Preset,
)

interface BatchWorkScheduler {
    fun enqueue(jobId: String, presetId: String, customOverrideJson: String?): Flow<BatchWorkStatus>
    fun observe(jobId: String): Flow<BatchWorkStatus>
    fun cancel(jobId: String)
}

data class BatchWorkStatus(
    val state: BatchWorkState,
    val completed: Int = 0,
    val total: Int = 0,
)

enum class BatchWorkState { Enqueued, Running, Succeeded, Failed, Cancelled }

private class WorkManagerBatchWorkScheduler(
    private val context: Context,
) : BatchWorkScheduler {
    private val workManager: WorkManager = WorkManager.getInstance(context)

    override fun enqueue(
        jobId: String,
        presetId: String,
        customOverrideJson: String?,
    ): Flow<BatchWorkStatus> {
        val request = OneTimeWorkRequestBuilder<BatchProcessWorker>()
            .setInputData(
                workDataOf(
                    BatchProcessWorker.KEY_JOB_ID to jobId,
                    BatchProcessWorker.KEY_PRESET_ID to presetId,
                    BatchProcessWorker.KEY_CUSTOM_OVERRIDE_JSON to customOverrideJson.orEmpty(),
                ),
            )
            .addTag(BatchProcessWorker.uniqueWorkName(jobId))
            .build()
        workManager.enqueueUniqueWork(
            BatchProcessWorker.uniqueWorkName(jobId),
            ExistingWorkPolicy.REPLACE,
            request,
        )
        return workInfoFlow(request.id)
    }

    override fun observe(jobId: String): Flow<BatchWorkStatus> = callbackFlow {
        val liveData = workManager.getWorkInfosForUniqueWorkLiveData(BatchProcessWorker.uniqueWorkName(jobId))
        val observer = Observer<List<WorkInfo>> { infos ->
            val latest = infos.firstOrNull()
            if (latest != null) {
                trySend(latest.toBatchWorkStatus())
                if (latest.state.isFinished) close()
            }
        }
        liveData.observeForever(observer)
        awaitClose { liveData.removeObserver(observer) }
    }

    override fun cancel(jobId: String) {
        workManager.cancelUniqueWork(BatchProcessWorker.uniqueWorkName(jobId))
    }

    private fun workInfoFlow(id: UUID): Flow<BatchWorkStatus> = callbackFlow {
        val liveData = workManager.getWorkInfoByIdLiveData(id)
        val observer = Observer<WorkInfo?> { workInfo ->
            if (workInfo != null) {
                trySend(workInfo.toBatchWorkStatus())
                if (workInfo.state.isFinished) close()
            }
        }
        liveData.observeForever(observer)
        awaitClose { liveData.removeObserver(observer) }
    }
}

private fun WorkInfo.toBatchWorkStatus(): BatchWorkStatus = BatchWorkStatus(
    state = when (state) {
        WorkInfo.State.ENQUEUED,
        WorkInfo.State.BLOCKED,
        -> BatchWorkState.Enqueued
        WorkInfo.State.RUNNING -> BatchWorkState.Running
        WorkInfo.State.SUCCEEDED -> BatchWorkState.Succeeded
        WorkInfo.State.FAILED -> BatchWorkState.Failed
        WorkInfo.State.CANCELLED -> BatchWorkState.Cancelled
    },
    completed = progress.getInt(BatchProcessWorker.KEY_PROGRESS_COMPLETED, 0),
    total = progress.getInt(BatchProcessWorker.KEY_PROGRESS_TOTAL, 0),
)

private fun newJobId(): String = "share-${System.currentTimeMillis()}-${(0 until SHARE_RANDOM_BOUND).random()}"

const val LARGE_BATCH_THRESHOLD = 10
private const val SHARE_RANDOM_BOUND = 10_000
