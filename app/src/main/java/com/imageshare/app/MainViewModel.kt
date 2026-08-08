@file:Suppress(
    "LongParameterList",
    "ReturnCount",
    "MaxLineLength",
    "TooManyFunctions",
    "ComplexCondition",
    "MagicNumber",
)

package com.imageshare.app

import android.content.ComponentName
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.Observer
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.imageshare.app.data.BatchItemError
import com.imageshare.app.data.BatchManifestDao
import com.imageshare.app.data.BatchManifestEntity
import com.imageshare.app.data.BatchManifestFailureCode
import com.imageshare.app.data.toSourceItem
import com.imageshare.app.processing.BatchOrchestrator
import com.imageshare.app.saving.PersistentSaver
import com.imageshare.app.sharing.AutoProcessOnShareSettings
import com.imageshare.app.sharing.SharingTarget
import com.imageshare.app.sharing.SharingTargetsRepository
import com.imageshare.core.io.MediaStoreSaver
import com.imageshare.app.processing.PresetPipeline
import com.imageshare.core.io.InputCoordinator
import com.imageshare.core.io.PersistableUriRegistry
import com.imageshare.core.io.RecentUriEntry
import com.imageshare.core.io.ShareLauncher
import com.imageshare.core.io.SharedIntakeStager
import com.imageshare.core.io.SourceItem
import com.imageshare.core.io.toPersistedUriString
import com.imageshare.core.processing.EncodeError
import com.imageshare.core.processing.EncodeFormat
import com.imageshare.app.work.BatchProcessWorker
import com.imageshare.app.work.toWorkerJson
import com.imageshare.app.work.withWorkerOverride
import com.imageshare.feature.preset.AlphaFallback
import com.imageshare.feature.preset.DefaultPresets
import com.imageshare.feature.preset.OutputFormat
import com.imageshare.feature.preset.Preset
import com.imageshare.feature.preset.PresetRepository
import com.imageshare.feature.preset.ResizeMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
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
    private val sharingTargetsRepository: SharingTargetsRepository = AppContainer.sharingTargetsRepository,
    private val autoProcessOnShareSettings: AutoProcessOnShareSettings = AppContainer.autoProcessOnShareSettings,
    private val manifestDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val sharedSources = MutableStateFlow<List<SourceItem>>(emptyList())
    private val pickedSources = MutableStateFlow<List<SourceItem>>(emptyList())
    private val mutableProcessingState = MutableStateFlow<ProcessingState>(ProcessingState.Idle)
    private val mutablePendingShareIntent = MutableStateFlow<Intent?>(null)
    private val mutableSaveDocumentEvents = MutableSharedFlow<Intent>(extraBufferCapacity = 1)
    private val mutablePendingSingleSourceFile = MutableStateFlow<File?>(null)
    private val mutableSaveStatus = MutableStateFlow<SaveStatus>(SaveStatus.Idle)
    private val _shownComparison = MutableStateFlow<ComparisonState?>(null)
    private val _customOverride = MutableStateFlow<CustomOverride?>(null)
    private val _runInBackground = MutableStateFlow(false)
    private val _autoProcessOnShare = MutableStateFlow(false)
    private val mutableSelectedPresetId = MutableStateFlow(DefaultPresets.DEFAULT_PRESET_ID)
    private var userSelectedPresetThisSession = false
    private var currentBatchJob: Job? = null
    private var activeBatch: ActiveBatch? = null
    private var activeWorkJobId: String? = null
    private var handoffInProgressJobId: String? = null
    private var handoffForegroundJob: Job? = null
    private var discardingTerminalSharedJob = false
    private var hasAskedPostNotificationsPermission = false
    private var autoProcessChangedThisSession = false
    private var restoringBackgroundBatch = true
    private val backgroundRecoveryComplete = CompletableDeferred<Unit>()
    private var backgroundRecoveryStarted = false
    private var deferredIntakeFailure = false
    private var restoredShareReady = false
    private var activeSharedJobId: String? = null
    private var shareIntakeJob: Job? = null
    private val handedOffSharedJobIds = ConcurrentHashMap.newKeySet<String>()
    private val pendingShareRequests = ArrayDeque<IncomingShare>()
    private val stagedSharesWaitingForHandoff = ArrayDeque<StagedShare>()

    val presets: StateFlow<List<Preset>> = presetRepository.observePresets()
        .stateIn(viewModelScope, SharingStarted.Eagerly, DefaultPresets.ALL)

    val selectedPresetId: StateFlow<String> = mutableSelectedPresetId.asStateFlow()

    val selectedPreset: StateFlow<Preset?> = combine(presets, selectedPresetId) { availablePresets, selectedId ->
        availablePresets.firstOrNull { it.id == selectedId } ?: availablePresets.firstOrNull()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val customOverride: StateFlow<CustomOverride?> = _customOverride.asStateFlow()
    val runInBackground: StateFlow<Boolean> = _runInBackground.asStateFlow()
    val topSharingTargets: StateFlow<List<SharingTarget>> = sharingTargetsRepository.observeTopTargets()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val autoProcessOnShare: StateFlow<Boolean> = _autoProcessOnShare.asStateFlow()

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

    val recentsUris: StateFlow<List<RecentUriEntry>> = persistableUriRegistry.observe()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val processingState: StateFlow<ProcessingState> = mutableProcessingState.asStateFlow()
    /**
     * The next outbound share is state, rather than a one-shot event, so an automatic
     * conversion that completes while the UI is stopped can still be shared when it returns.
     */
    val pendingShareIntent: StateFlow<Intent?> = mutablePendingShareIntent.asStateFlow()
    val shareEvents: Flow<Intent> = pendingShareIntent.filterNotNull()
    val saveDocumentEvents: SharedFlow<Intent> = mutableSaveDocumentEvents.asSharedFlow()
    val saveStatus: StateFlow<SaveStatus> = mutableSaveStatus.asStateFlow()
    val shownComparison: StateFlow<ComparisonState?> = _shownComparison.asStateFlow()
    val postNotificationsPermissionAskedThisSession: Boolean
        get() = hasAskedPostNotificationsPermission

    init {
        viewModelScope.launch {
            presetRepository.observeDefaultPresetId()
                .catch { emit(DefaultPresets.DEFAULT_PRESET_ID) }
                .collect { persistedId ->
                    if (!userSelectedPresetThisSession) {
                        mutableSelectedPresetId.value = persistedId
                    }
                    if (!backgroundRecoveryStarted) {
                        backgroundRecoveryStarted = true
                        reattachToBackgroundBatch()
                    }
                }
        }
        viewModelScope.launch {
            val persisted = autoProcessOnShareSettings.isEnabled()
            if (!autoProcessChangedThisSession) {
                _autoProcessOnShare.value = persisted
            }
        }
    }

    fun onPresetSelected(presetId: String) {
        // A conversion owns an immutable preset snapshot. Defer setting changes until it finishes
        // so the visible controls cannot imply that a running batch has changed configuration.
        if (hasActiveBatch()) return
        userSelectedPresetThisSession = true
        mutableSelectedPresetId.value = presetId
        invalidateFinishedResult()
        viewModelScope.launch {
            presetRepository.setDefaultPresetId(presetId)
        }
    }

    fun onCustomOverride(override: CustomOverride?) {
        if (hasActiveBatch()) return
        invalidateFinishedResult()
        _customOverride.value = override
    }

    fun onRunInBackgroundChanged(enabled: Boolean) {
        _runInBackground.value = enabled
    }

    fun onAutoProcessOnShareChanged(enabled: Boolean) {
        autoProcessChangedThisSession = true
        _autoProcessOnShare.value = enabled
        viewModelScope.launch {
            autoProcessOnShareSettings.setEnabled(enabled)
        }
    }

    fun recordSharingTarget(componentName: ComponentName) {
        viewModelScope.launch {
            sharingTargetsRepository.recordSelection(componentName)
        }
    }

    fun onPickFromGallery() = Unit
    /**
     * Serializes external share intake in the ViewModel so a configuration change cannot cancel
     * a copy in progress and rapid shares retain their arrival order.
     */
    fun acceptSharedUris(jobId: String, uris: List<Uri>) {
        if (uris.isEmpty()) return
        pendingShareRequests.addLast(IncomingShare(jobId, uris))
        if (shareIntakeJob?.isActive != true) {
            stageNextIncomingShare()
        }
    }

    private fun stageNextIncomingShare() {
        // Recovery may finish while the first external copy is still running. Leave that copy to
        // schedule the next request in its finally block so incoming shares always retain order.
        if (shareIntakeJob?.isActive == true) return
        val request = pendingShareRequests.pollFirst() ?: return
        // Copy incoming content immediately, even while startup recovery is reading manifests. The
        // staged share is held in the durable handoff queue until recovery has restored the screen.
        val deferActivationUntilRecoveryCompletes = restoringBackgroundBatch
        shareIntakeJob = viewModelScope.launch {
            try {
                stageSharedUris(
                    jobId = request.jobId,
                    uris = request.uris,
                    deferActivationUntilRecoveryCompletes = deferActivationUntilRecoveryCompletes,
                )
            } finally {
                shareIntakeJob = null
                stageNextIncomingShare()
            }
        }
    }

    fun onPickerResult(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            backgroundRecoveryComplete.await()
            if (hasActiveBatch()) return@launch
            addPickedSources(uris)
            invalidateFinishedResult()
        }
    }

    fun onOpenDocumentResult(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            backgroundRecoveryComplete.await()
            if (hasActiveBatch()) return@launch
            withContext(Dispatchers.IO) {
                uris.forEach { uri ->
                    persistableUriRegistry.add(uri, displayName = queryDisplayName(uri))
                }
            }
            addPickedSources(uris)
            invalidateFinishedResult()
        }
    }

    fun onRecentSelected(uri: Uri) {
        viewModelScope.launch {
            backgroundRecoveryComplete.await()
            if (hasActiveBatch()) return@launch
            addPickedSources(listOf(uri))
            invalidateFinishedResult()
        }
    }

    fun onClearSources() {
        if (hasActiveBatch()) return
        discardActiveSharedJob()
        restoredShareReady = false
        sharedSources.value = emptyList()
        pickedSources.value = emptyList()
        _shownComparison.value = null
        _customOverride.value = null
        mutablePendingShareIntent.value = null
        mutableProcessingState.value = ProcessingState.Idle
        startNextStagedShareIfPossible()
    }

    private fun invalidateFinishedResult() {
        when (mutableProcessingState.value) {
            is ProcessingState.Done,
            is ProcessingState.Cancelled,
            is ProcessingState.Failed,
            ProcessingState.IntakeFailed,
            -> {
                restoredShareReady = false
                mutablePendingShareIntent.value = null
                mutableProcessingState.value = ProcessingState.Idle
            }
            ProcessingState.Idle,
            is ProcessingState.Running,
            -> Unit
        }
    }

    fun onRecentRemoved(uri: Uri) {
        viewModelScope.launch {
            persistableUriRegistry.remove(uri)
        }
    }

    fun onProcessAndShare() {
        startProcessingFor(sources.value)
    }

    private fun startProcessingFor(
        currentSources: List<SourceItem>,
        presetOverride: Preset? = null,
        jobIdOverride: String? = activeSharedJobId,
    ) {
        if (
            currentSources.isEmpty() ||
            mutablePendingShareIntent.value != null ||
            mutableProcessingState.value is ProcessingState.Running ||
            activeWorkJobId != null ||
            currentBatchJob?.isActive == true
        ) return
        val preset = presetOverride ?: latestEffectivePreset() ?: return
        restoredShareReady = false
        // Shared intake is stored under its incoming ID. Reusing that ID for processing keeps
        // startup cleanup from deleting the inputs a pending worker still needs.
        val jobId = jobIdOverride ?: newJobId()
        if (shouldRunWithWorkManager(currentSources.size, _runInBackground.value)) {
            startWorkManagerBatch(jobId, currentSources, preset)
        } else {
            startBatch(currentSources, preset, jobId = jobId)
        }
    }

    private fun latestEffectivePreset(): Preset? {
        val base = presets.value.firstOrNull { it.id == mutableSelectedPresetId.value }
            ?: presets.value.firstOrNull()
            ?: return null
        return _customOverride.value?.let { base.copy(resize = it.resize) } ?: base
    }
    fun onProcessAndShareRequestingNotificationsIfNeeded(
        postNotificationsGranted: Boolean,
        requestPostNotifications: () -> Unit,
    ) {
        if (shouldRequestPostNotificationsPermissionBeforeBackgroundBatch(postNotificationsGranted)) {
            hasAskedPostNotificationsPermission = true
            requestPostNotifications()
            return
        }
        onProcessAndShare()
    }

    fun onPostNotificationsPermissionResult(granted: Boolean) {
        if (!granted) {
            hasAskedPostNotificationsPermission = true
        }
        onProcessAndShare()
    }

    fun onCancelBatch() {
        val workJobId = activeWorkJobId
        if (workJobId != null) {
            // Keep observing until WorkManager finishes cancellation. The worker flushes its latest
            // manifest snapshot during cleanup, so deciding from the sampled UI state can lose a
            // just-completed output.
            batchWorkScheduler.cancel(workJobId)
            return
        }

        val batch = activeBatch
        val completedResults = batch?.retainedResults.orEmpty() + currentCompletedResults(batch)
        val partial = completedResults.map { it.result }.filterIsInstance<PresetPipeline.Result.Success>()
        val foregroundSharedBatch = batch?.takeIf { activeSharedJobId == it.jobId }
        currentBatchJob?.cancel()
        handoffForegroundJob?.cancel()
        activeBatch = null

        if (foregroundSharedBatch == null) {
            currentBatchJob = null
            finishCancellation(partial)
            return
        }

        lateinit var cancellationJob: Job
        cancellationJob = viewModelScope.launch(start = CoroutineStart.LAZY) {
            var persisted = false
            try {
                // Commit partial output before it becomes available to share, so a process death
                // restores the converted subset instead of reopening the original queued inputs.
                persisted = persistForegroundTerminalManifest(
                    jobId = foregroundSharedBatch.jobId,
                    sources = foregroundSharedBatch.manifestSources,
                    results = completedResults,
                )
            } finally {
                if (currentBatchJob === coroutineContext[Job]) {
                    currentBatchJob = null
                    if (persisted) finishCancellation(partial) else finishBackgroundFailure(partial)
                    startNextStagedShareIfPossible()
                }
            }
        }
        currentBatchJob = cancellationJob
        cancellationJob.start()
    }

    fun onAppBackgrounded() {
        val batch = activeBatch ?: return
        if (activeWorkJobId != null || handoffInProgressJobId != null) return
        val running = mutableProcessingState.value as? ProcessingState.Running ?: return
        // A retry only runs its conflicted inputs, but the manifest must preserve every original
        // row and source index before WorkManager resumes the pending subset.
        startWorkManagerBatch(
            jobId = batch.jobId,
            pendingSources = batch.manifestSources,
            preset = batch.preset,
            currentProgress = batch.toManifestProgress(running.progress),
        )
    }

    fun shouldRunWithWorkManager(sourceCount: Int, runInBackground: Boolean): Boolean =
        sourceCount >= LARGE_BATCH_THRESHOLD || runInBackground

    fun shouldRequestPostNotificationsPermissionBeforeBackgroundBatch(postNotificationsGranted: Boolean): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        if (postNotificationsGranted) return false
        if (hasAskedPostNotificationsPermission) return false
        return shouldRunWithWorkManager(sources.value.size, _runInBackground.value)
    }

    fun onShareReadyOutputs() {
        val unresolvedAlphaConflicts = (mutableProcessingState.value as? ProcessingState.Done)
            ?.alphaConflictCount
            ?.let { it > 0 }
            ?: false
        if (unresolvedAlphaConflicts || mutablePendingShareIntent.value != null) return
        emitShareFor(shareableResults())
    }

    /** The chooser was dismissed or could not open; keep the completed result available to retry. */
    fun onPendingShareDismissed() {
        mutablePendingShareIntent.value = null
    }

    /** A direct sharing target accepted the outbound intent, so the next inbound share may appear. */
    fun onShareTargetLaunched() {
        restoredShareReady = false
        mutablePendingShareIntent.value = null
        discardActiveSharedJob()
        // A successful direct hand-off finishes this session even when no next share is already
        // queued. Otherwise a later external share would remain behind these stale sources.
        sharedSources.value = emptyList()
        pickedSources.value = emptyList()
        _shownComparison.value = null
        mutableProcessingState.value = ProcessingState.Idle
        startNextStagedShareIfPossible()
    }

    fun onSaveCopy() {
        val results = shareableResults().map { it.stored }
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

    private fun shareableResults(): List<PresetPipeline.Result.Success> = when (val state = processingState.value) {
        is ProcessingState.Done -> state.results.filterIsInstance<PresetPipeline.Result.Success>()
        is ProcessingState.Cancelled -> state.partial
        is ProcessingState.Failed -> state.partial
        ProcessingState.Idle,
        ProcessingState.IntakeFailed,
        is ProcessingState.Running,
        -> emptyList()
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
        val indexedResults = done.results.zip(done.sourceIndices) { result, sourceIndex ->
            IndexedResult(sourceIndex, result)
        }
        val conflicts = indexedResults.filter { indexed ->
            (indexed.result as? PresetPipeline.Result.Failure)?.isAlphaConflict() == true
        }
        if (conflicts.isEmpty()) return

        val retained = indexedResults.filterNot { indexed ->
            (indexed.result as? PresetPipeline.Result.Failure)?.isAlphaConflict() == true
        }
        if (strategy == AlphaConflictStrategy.Skip) {
            finalizeAlphaDecision(retained)
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

        startBatch(
            pendingSources = conflicts.map { (it.result as PresetPipeline.Result.Failure).before },
            preset = preset,
            retainedResults = retained,
            pendingSourceIndices = conflicts.map { it.sourceIndex },
            jobId = activeSharedJobId ?: newJobId(),
            manifestSources = sources.value,
        )
    }

    suspend fun stageSharedUris(
        jobId: String,
        uris: List<Uri>,
        repository: SharedIntakeRepository = sharedIntakeRepositoryFactory(
            appContext.contentResolver,
            appContext.cacheDir.resolve(SHARED_INTAKE_DIR),
        ),
        deferActivationUntilRecoveryCompletes: Boolean = false,
    ) {
        val staged = repository.stage(jobId, uris)
        if (staged.isEmpty()) {
            if (canShowIntakeFailure()) {
                mutableProcessingState.value = ProcessingState.IntakeFailed
            } else if (restoringBackgroundBatch) {
                deferredIntakeFailure = true
            }
            return
        }

        val incoming = StagedShare(
            jobId = jobId,
            sources = staged,
            shouldAutoProcess = shouldAutoProcessOnShare(),
        )
        // Persist every inbound share before exposing it. If storage is temporarily unavailable,
        // retain the staged copy in this session instead of silently dropping the incoming share.
        try {
            persistQueuedShare(incoming)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "Unable to persist staged share ${incoming.jobId}; keeping it in memory", error)
        }
        if (shouldDeferStagedShare() || deferActivationUntilRecoveryCompletes) {
            enqueueStagedShare(incoming)
            if (deferActivationUntilRecoveryCompletes && !restoringBackgroundBatch && sources.value.isEmpty()) {
                startNextStagedShareIfPossible()
            }
            return
        }
        activateStagedShare(incoming)
    }

    private suspend fun shouldAutoProcessOnShare(): Boolean = if (autoProcessChangedThisSession) {
        _autoProcessOnShare.value
    } else {
        autoProcessOnShareSettings.isEnabled().also { _autoProcessOnShare.value = it }
    }

    private fun shouldDeferStagedShare(): Boolean =
        hasActiveBatch() ||
            mutablePendingShareIntent.value != null ||
            awaitingUserDecision() ||
            sources.value.isNotEmpty()

    private fun canShowIntakeFailure(): Boolean =
        !restoringBackgroundBatch &&
            !hasActiveBatch() &&
            mutablePendingShareIntent.value == null &&
            !awaitingUserDecision() &&
            sources.value.isEmpty()

    private suspend fun persistQueuedShare(incoming: StagedShare) = withContext(Dispatchers.IO) {
        handedOffSharedJobIds.remove(incoming.jobId)
        val queuedAt = System.currentTimeMillis()
        batchManifestDao.upsert(
            incoming.sources.mapIndexed { index, source ->
                BatchManifestEntity(
                    jobId = incoming.jobId,
                    sourceIndex = index,
                    sourceUriString = source.toPersistedUriString(),
                    state = BatchProcessWorker.STATE_QUEUED,
                    storedFilePath = null,
                    outputMimeType = null,
                    errorCode = null,
                    updatedAt = queuedAt,
                    sourceMimeType = source.mimeType,
                    sourceDisplayName = source.displayName,
                    sourceSizeBytes = source.sizeBytes,
                    sourceWidth = source.width,
                    sourceHeight = source.height,
                )
            },
        )
    }

    private fun activateStagedShare(incoming: StagedShare) {
        // An external share is a new session. Never silently include images that were selected
        // during a previous picker session.
        activeSharedJobId = incoming.jobId
        restoredShareReady = false
        sharedSources.value = incoming.sources
        pickedSources.value = emptyList()
        _shownComparison.value = null
        mutableProcessingState.value = ProcessingState.Idle
        // Keep the queued manifest until this share is explicitly discarded or handed off. It
        // protects the staged inputs during manual editing and foreground processing.
        if (incoming.shouldAutoProcess) {
            startAutoProcessingFor(incoming)
        }
    }

    private fun startAutoProcessingFor(incoming: StagedShare) {
        viewModelScope.launch {
            val preset = resolveAutoProcessPreset() ?: return@launch
            if (
                activeSharedJobId == incoming.jobId &&
                sharedSources.value == incoming.sources &&
                pickedSources.value.isEmpty() &&
                mutableProcessingState.value == ProcessingState.Idle
            ) {
                startProcessingFor(incoming.sources, preset, jobIdOverride = incoming.jobId)
            }
        }
    }

    private suspend fun resolveAutoProcessPreset(): Preset? {
        if (!userSelectedPresetThisSession) {
            val persistedId = presetRepository.observeDefaultPresetId()
                .catch { emit(DefaultPresets.DEFAULT_PRESET_ID) }
                .first()
            if (!userSelectedPresetThisSession) {
                mutableSelectedPresetId.value = persistedId
            }
        }
        return latestEffectivePreset()
    }

    private fun discardActiveSharedJob() {
        val jobId = activeSharedJobId ?: return
        activeSharedJobId = null
        handedOffSharedJobIds += jobId
        viewModelScope.launch(Dispatchers.IO) {
            batchManifestDao.deleteJob(jobId)
        }
    }

    /** Deletes an intentionally superseded all-failed share before the next queued share starts. */
    private fun discardTerminalSharedJobBeforeNextShare() {
        if (discardingTerminalSharedJob) return
        val jobId = activeSharedJobId ?: return
        discardingTerminalSharedJob = true
        activeSharedJobId = null
        handedOffSharedJobIds += jobId
        viewModelScope.launch(manifestDispatcher) {
            val deleted = runCatching { batchManifestDao.deleteJob(jobId) }
                .onFailure { Log.w(TAG, "Unable to discard superseded failed share $jobId", it) }
                .isSuccess
            withContext(Dispatchers.Main.immediate) {
                discardingTerminalSharedJob = false
                if (!deleted) {
                    activeSharedJobId = jobId
                    handedOffSharedJobIds.remove(jobId)
                    return@withContext
                }
                startNextStagedShareIfPossible()
            }
        }
    }
    private fun enqueueStagedShare(incoming: StagedShare) {
        if (stagedSharesWaitingForHandoff.none { it.jobId == incoming.jobId }) {
            stagedSharesWaitingForHandoff.addLast(incoming)
        }
    }

    private fun startNextStagedShareIfPossible() {
        if (
            restoringBackgroundBatch ||
            restoredShareReady ||
            hasActiveBatch() ||
            mutablePendingShareIntent.value != null ||
            awaitingUserDecision() ||
            discardingTerminalSharedJob
        ) return
        if (stagedSharesWaitingForHandoff.isEmpty()) return
        if (hasDiscardableTerminalSharedFailure()) {
            discardTerminalSharedJobBeforeNextShare()
            return
        }
        val next = stagedSharesWaitingForHandoff.pollFirst() ?: return
        activateStagedShare(next)
    }

    private fun hasDiscardableTerminalSharedFailure(): Boolean {
        val done = mutableProcessingState.value as? ProcessingState.Done ?: return false
        return activeSharedJobId != null &&
            done.alphaConflictCount == 0 &&
            done.results.none { it is PresetPipeline.Result.Success }
    }

    private fun hasActiveBatch(): Boolean =
        mutableProcessingState.value is ProcessingState.Running ||
            activeWorkJobId != null ||
            currentBatchJob?.isActive == true

    private fun awaitingUserDecision(): Boolean = when (val state = mutableProcessingState.value) {
        is ProcessingState.Done -> state.alphaConflictCount > 0
        is ProcessingState.Cancelled -> state.partial.isNotEmpty()
        is ProcessingState.Failed -> state.partial.isNotEmpty()
        ProcessingState.Idle,
        ProcessingState.IntakeFailed,
        is ProcessingState.Running,
        -> false
    }

    private suspend fun addPickedSources(uris: List<Uri>) {
        val resolved = withContext(Dispatchers.IO) {
            uris.mapNotNull { uri -> runCatching { inputCoordinator.resolve(uri) }.getOrNull() }
        }
        pickedSources.value = pickedSources.value + resolved
    }

    private fun finalizeAlphaDecision(results: List<IndexedResult>) {
        val orderedResults = results.sortedBy { it.sourceIndex }
        val jobId = activeSharedJobId
        if (jobId == null) {
            finishProcessing(
                orderedResults.map { it.result },
                orderedResults.map { it.sourceIndex },
            )
            return
        }

        lateinit var decisionJob: Job
        decisionJob = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                // Omitted alpha-conflict results become terminal Cancelled rows. That records the
                // user's Skip decision instead of reopening the same dialog after a restart.
                if (persistForegroundTerminalManifest(jobId, sources.value, orderedResults)) {
                    finishProcessing(
                        orderedResults.map { it.result },
                        orderedResults.map { it.sourceIndex },
                    )
                } else {
                    finishBackgroundFailure()
                }
            } finally {
                if (currentBatchJob === coroutineContext[Job]) {
                    currentBatchJob = null
                    startNextStagedShareIfPossible()
                }
            }
        }
        currentBatchJob = decisionJob
        decisionJob.start()
    }

    private fun startBatch(
        pendingSources: List<SourceItem>,
        preset: Preset,
        retainedResults: List<IndexedResult> = emptyList(),
        pendingSourceIndices: List<Int> = pendingSources.indices.toList(),
        jobId: String = newJobId(),
        manifestSources: List<SourceItem> = pendingSources,
    ) {
        require(pendingSources.size == pendingSourceIndices.size)
        currentBatchJob?.cancel()
        val initialProgress = BatchOrchestrator.BatchProgress(
            jobId = jobId,
            items = pendingSources.map { source ->
                BatchOrchestrator.BatchProgress.Item(source, BatchOrchestrator.ItemState.Pending)
            },
        )
        activeBatch = ActiveBatch(
            jobId = jobId,
            sources = pendingSources,
            preset = preset,
            retainedResults = retainedResults,
            pendingSourceIndices = pendingSourceIndices,
            manifestSources = manifestSources,
        )
        // Mark the foreground flow active synchronously. An immediate Activity stop can now move
        // it to WorkManager instead of leaving the conversion in a killable Idle window.
        mutableProcessingState.value = ProcessingState.Running(initialProgress)

        lateinit var batchJob: Job
        batchJob = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                val results = processSources(pendingSources, preset, jobId)
                if (currentBatchJob !== batchJob) return@launch
                val completeResults = (retainedResults + results.mapIndexed { localIndex, result ->
                    IndexedResult(pendingSourceIndices[localIndex], result)
                }).sortedBy { it.sourceIndex }
                if (activeSharedJobId == jobId) {
                    // The output is not offered to the user until this write commits. That makes
                    // every ready-to-share foreground result restart-safe.
                    if (!persistForegroundTerminalManifest(jobId, manifestSources, completeResults)) {
                        finishBackgroundFailure()
                        return@launch
                    }
                }
                finishProcessing(
                    completeResults.map { it.result },
                    completeResults.map { it.sourceIndex },
                )
            } finally {
                if (currentBatchJob === coroutineContext[Job]) {
                    currentBatchJob = null
                    activeBatch = null
                    startNextStagedShareIfPossible()
                }
            }
        }
        currentBatchJob = batchJob
        batchJob.start()
    }

    private fun startWorkManagerBatch(
        jobId: String,
        pendingSources: List<SourceItem>,
        preset: Preset,
        currentProgress: BatchOrchestrator.BatchProgress? = null,
    ) {
        if (handoffInProgressJobId != null) return
        val foregroundJob = currentBatchJob
        val foregroundBatch = activeBatch
        handoffInProgressJobId = jobId
        handoffForegroundJob = foregroundJob
        val initialProgress = currentProgress ?: BatchOrchestrator.BatchProgress(
            jobId = jobId,
            items = pendingSources.map { source ->
                BatchOrchestrator.BatchProgress.Item(source, BatchOrchestrator.ItemState.Pending)
            },
        )
        // Background work always uses manifest positions. A retry may only be actively processing
        // a subset, but the persisted progress and cancellation UI cover the full original share.
        activeBatch = ActiveBatch(
            jobId = jobId,
            sources = pendingSources,
            preset = preset,
            manifestSources = pendingSources,
        )
        mutableProcessingState.value = ProcessingState.Running(initialProgress)
        val workerPresetJson = preset.toWorkerJson()

        lateinit var workJob: Job
        workJob = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                // The manifest is the hand-off boundary. Persist it before interrupting the
                // foreground pipeline so a process death always leaves resumable work behind.
                seedManifest(
                    jobId = jobId,
                    pendingSources = pendingSources,
                    currentProgress = currentProgress,
                    workerPresetId = preset.id,
                    workerPresetJson = workerPresetJson,
                )
                if (currentBatchJob !== workJob) return@launch
                foregroundJob?.cancel()
                foregroundJob?.join()
                if (currentBatchJob !== workJob) return@launch
                // Once this marker is durable, a new ViewModel can safely re-enqueue the exact
                // snapshot if this process dies before WorkManager has accepted the request.
                activeWorkJobId = jobId
                batchWorkScheduler.enqueue(jobId, preset.id, workerPresetJson)
                    .collect { status -> updateFromWorkStatus(jobId, status) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "Unable to schedule background batch $jobId", error)
                if (currentBatchJob === coroutineContext[Job]) {
                    activeWorkJobId = null
                    if (foregroundJob?.isActive == true) {
                        // Scheduling failed before the foreground pipeline was interrupted. Let
                        // it retain ownership rather than losing an in-flight conversion.
                        activeBatch = foregroundBatch
                        currentBatchJob = foregroundJob
                    } else {
                        activeBatch = null
                        finishBackgroundFailure(currentCompletedResults(null).map { it.result }
                            .filterIsInstance<PresetPipeline.Result.Success>())
                    }
                }
            } finally {
                if (handoffInProgressJobId == jobId) {
                    handoffInProgressJobId = null
                    handoffForegroundJob = null
                }
                if (currentBatchJob === coroutineContext[Job]) {
                    currentBatchJob = null
                }
            }
        }
        currentBatchJob = workJob
        workJob.start()
    }
    private suspend fun seedManifest(
        jobId: String,
        pendingSources: List<SourceItem>,
        currentProgress: BatchOrchestrator.BatchProgress?,
        workerPresetId: String? = null,
        workerPresetJson: String? = null,
    ) = withContext(manifestDispatcher) {
        batchManifestDao.upsert(
            pendingSources.mapIndexed { idx, src ->
                // Source URI is not an identity: the same image may appear more than once in an
                // incoming share. Preserve progress by its stable manifest position instead.
                val state = currentProgress?.items?.getOrNull(idx)?.state
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
                    errorCode = (state as? BatchOrchestrator.ItemState.Done)
                        ?.result
                        ?.let { it as? PresetPipeline.Result.Failure }
                        ?.toManifestErrorCode(),
                    updatedAt = System.currentTimeMillis(),
                    sourceMimeType = src.mimeType,
                    sourceDisplayName = src.displayName,
                    sourceSizeBytes = src.sizeBytes,
                    sourceWidth = src.width,
                    sourceHeight = src.height,
                    workerPresetId = workerPresetId,
                    workerPresetJson = workerPresetJson,
                )
            },
        )
    }

    private suspend fun updateFromWorkStatus(jobId: String, status: BatchWorkStatus) {
        val manifest = if (status.state == BatchWorkState.Cancelled) {
            awaitCancellationManifest(jobId)
        } else {
            readManifest(jobId)
        }
        when (status.state) {
            BatchWorkState.Running,
            BatchWorkState.Enqueued,
            -> mutableProcessingState.value = ProcessingState.Running(manifest.toProgress(jobId))

            BatchWorkState.Succeeded -> {
                activeWorkJobId = null
                currentBatchJob = null
                activeBatch = null
                val completedResults = manifest.toIndexedResults()
                finishProcessing(
                    completedResults.map { it.result },
                    completedResults.map { it.sourceIndex },
                )
            }

            BatchWorkState.Failed -> {
                // A scheduling/worker failure is recoverable. Keep the staged input and manifest
                // intact so the user can retry and startup recovery can resume it.
                activeWorkJobId = null
                currentBatchJob = null
                activeBatch = null
                val partial = manifest.toResults().filterIsInstance<PresetPipeline.Result.Success>()
                finishBackgroundFailure(partial)
            }

            BatchWorkState.Cancelled -> {
                // The worker persists cancellation cleanup before it reaches this terminal state;
                // derive shareable partial output from the manifest, not stale sampled UI progress.
                activeWorkJobId = null
                currentBatchJob = null
                activeBatch = null
                val partial = manifest.toResults().filterIsInstance<PresetPipeline.Result.Success>()
                // A cancelled WorkInfo can arrive before CoroutineWorker's NonCancellable cleanup
                // has flushed its terminal rows. Only discard an empty shared batch once no rows
                // are still pending; otherwise preserve it for a retry/restart recovery.
                finishCancellation(
                    partial,
                    discardEmptySharedJob = manifest.none { it.state == BatchProcessWorker.STATE_PENDING },
                )
            }
        }
    }

    private suspend fun readManifest(jobId: String): List<BatchManifestEntity> =
        withContext(Dispatchers.IO) { batchManifestDao.forJob(jobId) }

    private suspend fun awaitCancellationManifest(jobId: String): List<BatchManifestEntity> {
        var manifest = readManifest(jobId)
        repeat(CANCELLATION_MANIFEST_POLL_ATTEMPTS) {
            if (manifest.none { it.state == BatchProcessWorker.STATE_PENDING }) return manifest
            delay(CANCELLATION_MANIFEST_POLL_DELAY_MILLIS)
            manifest = readManifest(jobId)
        }
        return manifest
    }
    private fun reattachToBackgroundBatch() {
        viewModelScope.launch {
            try {
                val pendingManifest = withContext(Dispatchers.IO) {
                    val pendingJobId = batchManifestDao.pendingJobIds().firstOrNull()
                    pendingJobId?.let { it to batchManifestDao.forJob(it) }
                }
                val queuedManifests = withContext(Dispatchers.IO) {
                    batchManifestDao.queuedJobIds().map { jobId -> jobId to batchManifestDao.forJob(jobId) }
                }
                val queuedAutoProcess = if (queuedManifests.isNotEmpty()) shouldAutoProcessOnShare() else false
                queuedManifests.forEach { (jobId, manifest) ->
                    val queuedSources = manifest.toProgress(jobId).items.map { it.source }
                    if (queuedSources.isNotEmpty()) {
                        enqueueStagedShare(StagedShare(jobId, queuedSources, queuedAutoProcess))
                    }
                }

                if (pendingManifest != null) {
                    // Restore sources and an ActiveBatch before observing work. That lets a user
                    // cancel a recovered batch without losing rows that completed before restart.
                    val (jobId, manifest) = pendingManifest
                    val progress = manifest.toProgress(jobId)
                    val workerPresetId = manifest.firstNotNullOfOrNull { it.workerPresetId }
                    val workerPresetJson = manifest.firstNotNullOfOrNull { it.workerPresetJson }
                    val restoredPreset = workerPresetId
                        ?.let { presetRepository.getPreset(it) }
                        ?.withWorkerOverride(workerPresetJson)
                        ?: latestEffectivePreset()
                        ?: DefaultPresets.SmallFile
                    sharedSources.value = progress.items.map { it.source }
                    pickedSources.value = emptyList()
                    activeSharedJobId = jobId
                    activeWorkJobId = jobId
                    activeBatch = ActiveBatch(
                        jobId = jobId,
                        sources = progress.items.map { it.source },
                        preset = restoredPreset,
                        manifestSources = progress.items.map { it.source },
                    )
                    currentBatchJob = launch {
                        val workStatus = if (workerPresetId != null) {
                            // A configured Pending manifest means the app died after durable
                            // hand-off preparation. Re-enqueue the same immutable work request.
                            batchWorkScheduler.resume(jobId, workerPresetId, workerPresetJson)
                        } else {
                            // Legacy manifests have no recoverable config; preserve their inputs
                            // and surface retry rather than converting them to cancellation.
                            batchWorkScheduler.observe(jobId)
                        }
                        workStatus.collect { status -> updateFromWorkStatus(jobId, status) }
                    }
                } else {
                    val completedManifest = withContext(Dispatchers.IO) {
                        var latestCompleted: Pair<String, List<BatchManifestEntity>>? = null
                        for (jobId in batchManifestDao.jobIds()) {
                            val manifest = batchManifestDao.forJob(jobId)
                            val isTerminal = manifest.isNotEmpty() && manifest.none {
                                it.state == BatchProcessWorker.STATE_PENDING ||
                                    it.state == BatchProcessWorker.STATE_QUEUED
                            }
                            if (isTerminal && manifest.toResults().isNotEmpty()) {
                                latestCompleted = jobId to manifest
                                break
                            }
                        }
                        latestCompleted
                    }
                    completedManifest?.let { (jobId, manifest) -> restoreCompletedBatch(jobId, manifest) }
                }
            } finally {
                restoringBackgroundBatch = false
                if (deferredIntakeFailure && canShowIntakeFailure()) {
                    mutableProcessingState.value = ProcessingState.IntakeFailed
                }
                deferredIntakeFailure = false
                backgroundRecoveryComplete.complete(Unit)
                stageNextIncomingShare()
                startNextStagedShareIfPossible()
            }
        }
    }

    private fun restoreCompletedBatch(jobId: String, manifest: List<BatchManifestEntity>) {
        val indexedResults = manifest.toIndexedResults()
        if (indexedResults.isEmpty()) return
        val results = indexedResults.map { it.result }
        activeSharedJobId = jobId
        sharedSources.value = manifest.toProgress(jobId).items.map { it.source }
        pickedSources.value = emptyList()
        _shownComparison.value = null
        val restored = ProcessingState.Done(
            results = results,
            alphaConflictCount = results.count { result ->
                result is PresetPipeline.Result.Failure && result.isAlphaConflict()
            },
            sourceIndices = indexedResults.map { it.sourceIndex },
        )
        mutableProcessingState.value = restored
        // Do not relaunch a chooser on every app start. Leave recovered outputs visibly ready for
        // the user to share, save, or clear; queued incoming shares wait for that hand-off.
        restoredShareReady = results.any { it is PresetPipeline.Result.Success }
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

    private suspend fun persistForegroundTerminalManifest(
        jobId: String,
        sources: List<SourceItem>,
        results: List<IndexedResult>,
    ): Boolean = try {
        seedManifest(jobId, sources, terminalProgress(jobId, sources, results))
        if (jobId in handedOffSharedJobIds) {
            batchManifestDao.deleteJob(jobId)
        }
        true
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        // Do not expose output as share-ready until its recovery manifest exists. The source
        // remains staged, and the UI shows a retryable failure instead of silently losing work.
        Log.w(TAG, "Unable to persist foreground result $jobId", error)
        false
    }

    private fun terminalProgress(
        jobId: String,
        sources: List<SourceItem>,
        results: List<IndexedResult>,
    ): BatchOrchestrator.BatchProgress {
        val resultsByIndex = results.associateBy { it.sourceIndex }
        return BatchOrchestrator.BatchProgress(
            jobId = jobId,
            items = sources.mapIndexed { sourceIndex, source ->
                val result = resultsByIndex[sourceIndex]?.result
                BatchOrchestrator.BatchProgress.Item(
                    source = source,
                    state = result?.let(BatchOrchestrator.ItemState::Done)
                        ?: BatchOrchestrator.ItemState.Cancelled,
                )
            },
        )
    }

    private fun finishProcessing(
        results: List<PresetPipeline.Result>,
        sourceIndices: List<Int> = results.indices.toList(),
    ) {
        restoredShareReady = false
        val done = ProcessingState.Done(
            results = results,
            alphaConflictCount = results.count { result ->
                result is PresetPipeline.Result.Failure && result.isAlphaConflict()
            },
            sourceIndices = sourceIndices,
        )
        mutableProcessingState.value = done
        if (done.alphaConflictCount == 0) {
            emitShareFor(results)
            if (mutablePendingShareIntent.value == null) {
                if (hasDiscardableTerminalSharedFailure() && stagedSharesWaitingForHandoff.isNotEmpty()) {
                    discardTerminalSharedJobBeforeNextShare()
                } else {
                    startNextStagedShareIfPossible()
                }
            }
        }
    }

    private fun emitShareFor(results: List<PresetPipeline.Result>) {
        val stored = results.filterIsInstance<PresetPipeline.Result.Success>().map { it.stored }
        if (stored.isNotEmpty()) {
            mutablePendingShareIntent.value = shareLauncher.buildShareIntent(appContext, stored)
        }
    }
    private fun finishCancellation(
        partial: List<PresetPipeline.Result.Success>,
        discardEmptySharedJob: Boolean = true,
    ) {
        mutableProcessingState.value = ProcessingState.Cancelled(partial)
        if (partial.isEmpty() && discardEmptySharedJob) {
            discardActiveSharedJob()
            startNextStagedShareIfPossible()
        }
    }
    private fun finishBackgroundFailure(partial: List<PresetPipeline.Result.Success> = emptyList()) {
        mutableProcessingState.value = ProcessingState.Failed(partial)
        // Keep partial output visible, but do not auto-launch sharing while unresolved input remains.
        // The user can explicitly share the converted subset or retry the full durable batch.
    }

    private fun currentCompletedResults(batch: ActiveBatch?): List<IndexedResult> {
        val running = mutableProcessingState.value as? ProcessingState.Running ?: return emptyList()
        return running.progress.items.mapIndexedNotNull { localIndex, item ->
            val result = (item.state as? BatchOrchestrator.ItemState.Done)?.result ?: return@mapIndexedNotNull null
            IndexedResult(batch?.pendingSourceIndices?.getOrNull(localIndex) ?: localIndex, result)
        }
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        appContext.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }.getOrNull()

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

private const val TAG = "MainViewModel"
private const val CANCELLATION_MANIFEST_POLL_ATTEMPTS = 5
private const val CANCELLATION_MANIFEST_POLL_DELAY_MILLIS = 50L

sealed interface ProcessingState {
    data object Idle : ProcessingState
    /** The received share could not be read. Keep the empty-state actions available for retry. */
    data object IntakeFailed : ProcessingState
    data class Running(val progress: BatchOrchestrator.BatchProgress) : ProcessingState

    data class Done(
        val results: List<PresetPipeline.Result>,
        val alphaConflictCount: Int = 0,
        /** Positions in the active source list, kept so retries preserve duplicate inputs exactly. */
        val sourceIndices: List<Int> = results.indices.toList(),
    ) : ProcessingState

    data class Cancelled(val partial: List<PresetPipeline.Result.Success>) : ProcessingState

    /** Background scheduling/worker failure. Input stays available and the batch can be retried. */
    data class Failed(val partial: List<PresetPipeline.Result.Success> = emptyList()) : ProcessingState
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

}

private fun PresetPipeline.Result.Failure.isAlphaConflict(): Boolean = cause is EncodeError.AlphaConflict

private fun PresetPipeline.Step.toBatchItemError(): BatchItemError = when (this) {
    PresetPipeline.Step.Decoding -> BatchItemError.Decode
    PresetPipeline.Step.Resizing -> BatchItemError.Resize
    PresetPipeline.Step.Encoding -> BatchItemError.Encode
    PresetPipeline.Step.ApplyingMetadata -> BatchItemError.MetadataApply
    PresetPipeline.Step.Storing -> BatchItemError.Store
}

private fun PresetPipeline.Result.Failure.toManifestErrorCode(): String = when (val error = cause) {
    is EncodeError.AlphaConflict -> BatchManifestFailureCode.alphaConflict(error.format)
    else -> step.toBatchItemError().name
}

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
                source = entity.toSourceItem(),
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

private fun List<BatchManifestEntity>.toIndexedResults(): List<IndexedResult> =
    sortedBy { it.sourceIndex }
        .filter { it.state == BatchProcessWorker.STATE_DONE || it.state == BatchProcessWorker.STATE_FAILED }
        .map { entity -> IndexedResult(entity.sourceIndex, entity.toResult()) }

private fun List<BatchManifestEntity>.toResults(): List<PresetPipeline.Result> =
    toIndexedResults().map { it.result }

private fun BatchManifestEntity.toResult(): PresetPipeline.Result {
    val source = toSourceItem()
    if (state == BatchProcessWorker.STATE_DONE && storedFilePath != null) {
        val file = File(storedFilePath)
        if (!file.isFile) {
            return PresetPipeline.Result.Failure(
                before = source,
                cause = EncodeError.Invalid("Processed output is no longer available"),
                step = PresetPipeline.Step.Storing,
            )
        }
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
    BatchManifestFailureCode.parseAlphaConflict(errorCode)?.let { alphaConflict ->
        return PresetPipeline.Result.Failure(
            before = source,
            cause = alphaConflict,
            step = PresetPipeline.Step.Encoding,
        )
    }
    return PresetPipeline.Result.Failure(
        before = source,
        cause = EncodeError.Invalid(errorCode ?: "Background processing failed"),
        step = PresetPipeline.Step.Storing,
    )
}

private fun String.toEncodeFormat(): EncodeFormat = when {
    contains("png", ignoreCase = true) -> EncodeFormat.PNG
    contains("webp", ignoreCase = true) -> EncodeFormat.WEBP_LOSSY
    else -> EncodeFormat.JPEG
}

private data class IncomingShare(
    val jobId: String,
    val uris: List<Uri>,
)

private data class StagedShare(
    val jobId: String,
    val sources: List<SourceItem>,
    val shouldAutoProcess: Boolean,
)

private data class IndexedResult(
    val sourceIndex: Int,
    val result: PresetPipeline.Result,
)

private data class ActiveBatch(
    val jobId: String,
    val sources: List<SourceItem>,
    val preset: Preset,
    val retainedResults: List<IndexedResult> = emptyList(),
    val pendingSourceIndices: List<Int> = sources.indices.toList(),
    val manifestSources: List<SourceItem> = sources,
)

private fun ActiveBatch.toManifestProgress(
    runningProgress: BatchOrchestrator.BatchProgress,
): BatchOrchestrator.BatchProgress {
    val statesBySourceIndex = mutableMapOf<Int, BatchOrchestrator.ItemState>()
    retainedResults.forEach { retained ->
        statesBySourceIndex[retained.sourceIndex] = BatchOrchestrator.ItemState.Done(retained.result)
    }
    runningProgress.items.forEachIndexed { localIndex, item ->
        pendingSourceIndices.getOrNull(localIndex)?.let { sourceIndex ->
            statesBySourceIndex[sourceIndex] = item.state
        }
    }
    return BatchOrchestrator.BatchProgress(
        jobId = jobId,
        items = manifestSources.mapIndexed { sourceIndex, source ->
            BatchOrchestrator.BatchProgress.Item(
                source = source,
                state = statesBySourceIndex[sourceIndex] ?: BatchOrchestrator.ItemState.Pending,
            )
        },
    )
}

interface BatchWorkScheduler {
    fun enqueue(jobId: String, presetId: String, customOverrideJson: String?): Flow<BatchWorkStatus>

    /** Recreates an interrupted durable hand-off, or observes the already accepted work. */
    fun resume(jobId: String, presetId: String, customOverrideJson: String?): Flow<BatchWorkStatus> =
        enqueue(jobId, presetId, customOverrideJson)

    fun observe(jobId: String): Flow<BatchWorkStatus>
    fun cancel(jobId: String)
}

data class BatchWorkStatus(
    val state: BatchWorkState,
    val completed: Int = 0,
    val total: Int = 0,
)

enum class BatchWorkState { Enqueued, Running, Succeeded, Failed, Cancelled }

internal class WorkManagerBatchWorkScheduler(
    private val context: Context,
) : BatchWorkScheduler {
    private val workManager: WorkManager = WorkManager.getInstance(context)

    override fun enqueue(
        jobId: String,
        presetId: String,
        customOverrideJson: String?,
    ): Flow<BatchWorkStatus> {
        val request = newRequest(jobId, presetId, customOverrideJson)
        workManager.enqueueUniqueWork(
            BatchProcessWorker.uniqueWorkName(jobId),
            ExistingWorkPolicy.REPLACE,
            request,
        )
        return workInfoFlow(request.id)
    }

    override fun resume(
        jobId: String,
        presetId: String,
        customOverrideJson: String?,
    ): Flow<BatchWorkStatus> = flow {
        val activeWorkExists = withContext(Dispatchers.IO) {
            runCatching {
                workManager.getWorkInfosForUniqueWork(BatchProcessWorker.uniqueWorkName(jobId)).get()
                    .any { !it.state.isFinished }
            }.getOrDefault(false)
        }
        if (activeWorkExists) {
            emitAll(observe(jobId))
        } else {
            emitAll(enqueue(jobId, presetId, customOverrideJson))
        }
    }

    override fun observe(jobId: String): Flow<BatchWorkStatus> = callbackFlow {
        val uniqueWorkName = BatchProcessWorker.uniqueWorkName(jobId)
        val liveData = workManager.getWorkInfosForUniqueWorkLiveData(uniqueWorkName)
        val observedWork = AtomicBoolean(false)
        val observer = Observer<List<WorkInfo>> { infos ->
            val latest = infos.firstOrNull()
            if (latest != null) {
                observedWork.set(true)
                trySend(latest.toBatchWorkStatus())
                if (latest.state.isFinished) close()
            }
        }
        liveData.observeForever(observer)
        launch(Dispatchers.IO) {
            val infos = runCatching { workManager.getWorkInfosForUniqueWork(uniqueWorkName).get() }
                .getOrDefault(emptyList())
            if (infos.isEmpty() && !observedWork.get()) {
                // No work is not a user cancellation. The ViewModel keeps the durable inputs and
                // exposes retry; configured manifests are re-enqueued through resume().
                trySend(BatchWorkStatus(BatchWorkState.Failed))
                close()
            }
        }
        awaitClose { liveData.removeObserver(observer) }
    }

    private fun newRequest(
        jobId: String,
        presetId: String,
        customOverrideJson: String?,
    ) = OneTimeWorkRequestBuilder<BatchProcessWorker>()
        .setInputData(
            workDataOf(
                BatchProcessWorker.KEY_JOB_ID to jobId,
                BatchProcessWorker.KEY_PRESET_ID to presetId,
                BatchProcessWorker.KEY_CUSTOM_OVERRIDE_JSON to customOverrideJson.orEmpty(),
            ),
        )
        .addTag(BatchProcessWorker.uniqueWorkName(jobId))
        .build()

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

private fun newJobId(): String = "share-${UUID.randomUUID()}"

const val LARGE_BATCH_THRESHOLD = 10