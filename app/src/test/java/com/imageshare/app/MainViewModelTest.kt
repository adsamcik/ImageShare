@file:Suppress("MaxLineLength")

package com.imageshare.app

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.imageshare.app.data.BatchManifestDao
import com.imageshare.app.data.BatchManifestEntity
import com.imageshare.app.data.BatchManifestFailureCode
import com.imageshare.app.processing.BatchOrchestrator
import com.imageshare.app.saving.PersistentSaver
import com.imageshare.core.io.MediaStoreSaver
import com.imageshare.app.processing.PresetPipeline
import com.imageshare.app.processing.PresetPipelineRunner
import com.imageshare.app.work.BatchProcessWorker
import com.imageshare.app.work.toWorkerJson
import com.imageshare.app.work.withWorkerOverride
import com.imageshare.core.io.OutputStore
import com.imageshare.core.io.PersistableUriRegistry
import com.imageshare.core.io.ShareLauncher
import com.imageshare.core.io.ShareUriResolver
import com.imageshare.core.io.SourceItem
import com.imageshare.app.sharing.AutoProcessOnShareSettings
import com.imageshare.app.sharing.DataStoreSharingTargetsRepository
import com.imageshare.core.processing.EncodeError
import com.imageshare.core.processing.EncodeFormat
import com.imageshare.feature.preset.AlphaFallback
import com.imageshare.feature.preset.DefaultPresets
import com.imageshare.feature.preset.Preset
import com.imageshare.feature.preset.PresetRepository
import com.imageshare.feature.preset.ResizeMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
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
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.io.FileNotFoundException

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
    fun processAndShareTransitionsFromIdleToRunningToDone() = runTest(mainDispatcherRule.dispatcher) {
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
    fun cancelBatchCancelsInFlightJobAndKeepsPartialSuccesses() = runTest(mainDispatcherRule.dispatcher) {
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
    fun alphaConflictWaitsForUserChoiceThenRerunsOnlyFailures() = runTest(mainDispatcherRule.dispatcher) {
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
    fun alphaRetryPersistsTheFullOriginalShareForRestartRecovery() = runTest(mainDispatcherRule.dispatcher) {
        val context = RuntimeEnvironment.getApplication()
        val alphaSource = source.copy(uri = Uri.parse("content://images/alpha"), displayName = "alpha.png")
        val backingManifestDao = batchManifestDao()
        val manifestDao = ObservingBatchManifestDao(backingManifestDao)
        val viewModel = viewModel(context, manifestDao = manifestDao) { before, preset, _ ->
            if (before == alphaSource && preset.alphaFallback == AlphaFallback.Error) {
                PresetPipeline.Result.Failure(
                    before = before,
                    cause = EncodeError.AlphaConflict(EncodeFormat.JPEG),
                    step = PresetPipeline.Step.Encoding,
                )
            } else {
                success(context, preset, before)
            }
        }
        awaitInitialEmptyRecovery(manifestDao)
        viewModel.onPresetSelected(DefaultPresets.BestQuality.id)
        stage(viewModel, "alpha-retry-job", source, alphaSource)

        viewModel.onProcessAndShare()
        advanceUntilIdle()
        assertEquals(1, (viewModel.processingState.value as ProcessingState.Done).alphaConflictCount)

        viewModel.resolveAlphaConflicts(AlphaConflictStrategy.UseWhiteBackground)
        advanceUntilIdle()

        val terminalRows = backingManifestDao.forJob("alpha-retry-job")
        assertEquals(listOf(0, 1), terminalRows.map { it.sourceIndex })
        assertTrue(terminalRows.all { it.state == BatchProcessWorker.STATE_DONE })

        val recoveredManifestDao = ObservingBatchManifestDao(backingManifestDao)
        val recreatedViewModel = viewModel(context, manifestDao = recoveredManifestDao) { before, preset, _ ->
            success(context, preset, before)
        }
        awaitInitialEmptyRecovery(recoveredManifestDao, expectedForJobReads = 1)

        val restored = recreatedViewModel.processingState.first { it is ProcessingState.Done } as ProcessingState.Done
        assertEquals(0, restored.alphaConflictCount)
        assertEquals(2, restored.results.filterIsInstance<PresetPipeline.Result.Success>().size)
    }
    @Test
    fun alphaRetryBackgroundHandoffPreservesAllManifestRowsAndIndices() = runTest(mainDispatcherRule.dispatcher) {
        val context = RuntimeEnvironment.getApplication()
        val alphaSource = source.copy(uri = Uri.parse("content://images/alpha"), displayName = "alpha.png")
        val retryStarted = CompletableDeferred<Unit>()
        val scheduler = FakeBatchWorkScheduler()
        val backingManifestDao = batchManifestDao()
        val manifestDao = ObservingBatchManifestDao(backingManifestDao)
        val viewModel = viewModel(context, scheduler, manifestDao = manifestDao) { before, preset, _ ->
            if (before == alphaSource && preset.alphaFallback == AlphaFallback.Error) {
                PresetPipeline.Result.Failure(
                    before = before,
                    cause = EncodeError.AlphaConflict(EncodeFormat.JPEG),
                    step = PresetPipeline.Step.Encoding,
                )
            } else if (before == alphaSource) {
                retryStarted.complete(Unit)
                awaitCancellation()
            } else {
                success(context, preset, before)
            }
        }
        awaitInitialEmptyRecovery(manifestDao)
        viewModel.onPresetSelected(DefaultPresets.BestQuality.id)
        stage(viewModel, "alpha-background-job", source, alphaSource)

        viewModel.onProcessAndShare()
        advanceUntilIdle()
        viewModel.resolveAlphaConflicts(AlphaConflictStrategy.UseWhiteBackground)
        retryStarted.await()

        viewModel.onAppBackgrounded()
        assertEnqueuedEventually(scheduler)

        val scheduledPreset = DefaultPresets.BestQuality.withWorkerOverride(scheduler.requests.single().presetJson)
        assertEquals(AlphaFallback.FillWhite, scheduledPreset.alphaFallback)

        val rows = backingManifestDao.forJob("alpha-background-job")
        assertEquals(listOf(0, 1), rows.map { it.sourceIndex })
        assertEquals(BatchProcessWorker.STATE_DONE, rows[0].state)
        assertEquals(BatchProcessWorker.STATE_PENDING, rows[1].state)
        assertEquals(source.uri.toString(), rows[0].sourceUriString)
        assertEquals(alphaSource.uri.toString(), rows[1].sourceUriString)
    }
    @Test
    fun backgroundHandoffSeedsManifestBeforeCancellingForegroundPipeline() = runTest(mainDispatcherRule.dispatcher) {
        val context = RuntimeEnvironment.getApplication()
        val handoffGate = CompletableDeferred<Unit>()
        val foregroundStarted = CompletableDeferred<Unit>()
        val foregroundCancelled = CompletableDeferred<Unit>()
        var foregroundCancellationCount = 0
        val scheduler = FakeBatchWorkScheduler()
        val backingManifestDao = batchManifestDao()
        val manifestDao = ObservingBatchManifestDao(
            delegate = backingManifestDao,
            terminalUpsertGate = handoffGate,
        )
        val viewModel = viewModel(context, scheduler, manifestDao = manifestDao) { _, _, _ ->
            foregroundStarted.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                foregroundCancellationCount += 1
                foregroundCancelled.complete(Unit)
            }
        }
        awaitInitialEmptyRecovery(manifestDao)
        stage(viewModel, "handoff-job", source)

        viewModel.onProcessAndShare()
        foregroundStarted.await()
        viewModel.onAppBackgrounded()
        manifestDao.awaitUpsertStarted(2)
        viewModel.onAppBackgrounded()

        assertTrue(!foregroundCancelled.isCompleted)
        handoffGate.complete(Unit)
        foregroundCancelled.await()
        assertEnqueuedEventually(scheduler)
        assertEquals(1, foregroundCancellationCount)
    }
    @Test
    fun skippingAlphaConflictsPersistsTheDecisionForRestartRecovery() = runTest(mainDispatcherRule.dispatcher) {
        val context = RuntimeEnvironment.getApplication()
        val alphaSource = source.copy(uri = Uri.parse("content://images/alpha"), displayName = "alpha.png")
        val backingManifestDao = batchManifestDao()
        val manifestDao = ObservingBatchManifestDao(backingManifestDao)
        val viewModel = viewModel(context, manifestDao = manifestDao) { before, preset, _ ->
            if (before == alphaSource && preset.alphaFallback == AlphaFallback.Error) {
                PresetPipeline.Result.Failure(
                    before = before,
                    cause = EncodeError.AlphaConflict(EncodeFormat.JPEG),
                    step = PresetPipeline.Step.Encoding,
                )
            } else {
                success(context, preset, before)
            }
        }
        awaitInitialEmptyRecovery(manifestDao)
        viewModel.onPresetSelected(DefaultPresets.BestQuality.id)
        stage(viewModel, "alpha-skip-job", source, alphaSource)

        viewModel.onProcessAndShare()
        advanceUntilIdle()
        viewModel.resolveAlphaConflicts(AlphaConflictStrategy.Skip)
        advanceUntilIdle()

        val terminalRows = backingManifestDao.forJob("alpha-skip-job")
        assertEquals(
            listOf(BatchProcessWorker.STATE_DONE, BatchProcessWorker.STATE_CANCELLED),
            terminalRows.map { it.state },
        )

        val recoveredManifestDao = ObservingBatchManifestDao(backingManifestDao)
        val recreatedViewModel = viewModel(context, manifestDao = recoveredManifestDao) { before, preset, _ ->
            success(context, preset, before)
        }
        awaitInitialEmptyRecovery(recoveredManifestDao, expectedForJobReads = 1)

        val restored = recreatedViewModel.processingState.first { it is ProcessingState.Done } as ProcessingState.Done
        assertEquals(0, restored.alphaConflictCount)
        assertEquals(1, restored.results.filterIsInstance<PresetPipeline.Result.Success>().size)
    }
    @Test
    fun customOverrideUpdatesEffectivePreset() = runTest(mainDispatcherRule.dispatcher) {
        val context = RuntimeEnvironment.getApplication()
        val viewModel = viewModel(context) { before, preset, _ -> success(context, preset, before) }
        advanceUntilIdle()

        viewModel.onCustomOverride(MainViewModel.CustomOverride(ResizeMode.Percentage(50), allowUpscale = false))
        advanceUntilIdle()

        assertEquals(ResizeMode.Percentage(50), viewModel.effectivePreset.value?.resize)
    }

    @Test
    fun clearingCustomOverrideRevertsToBasePreset() = runTest(mainDispatcherRule.dispatcher) {
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
    fun onRecentSelectedRestagesSourceInSourcesFlow() = runTest(mainDispatcherRule.dispatcher) {
        val context = RuntimeEnvironment.getApplication()
        val viewModel = viewModel(context) { before, preset, _ -> success(context, preset, before) }
        val authority = "recentvm${System.nanoTime()}"
        ShadowContentResolver.registerProviderInternal(
            authority,
            RecentContentProvider(displayName = "recent.jpg", sizeBytes = 123L, mimeType = "image/jpeg"),
        )
        val recentUri = Uri.parse("content://$authority/image")
        org.robolectric.Shadows.shadowOf(context.contentResolver).registerInputStreamSupplier(recentUri) {
            throw FileNotFoundException("No dimensions fixture needed for $recentUri")
        }

        viewModel.onRecentSelected(recentUri)
        var attempts = 0
        while (viewModel.sources.value.none { it.uri == recentUri } && attempts < 20) {
            advanceUntilIdle()
            Thread.sleep(50)
            attempts += 1
        }
        advanceUntilIdle()

        val staged = viewModel.sources.value.single { it.uri == recentUri }
        assertEquals("image/jpeg", staged.mimeType)
        assertEquals("recent.jpg", staged.displayName)
        assertEquals(123L, staged.sizeBytes)
    }

    @Test
    fun saveCopyForSingleResultEmitsCreateDocumentEvent() = runTest(mainDispatcherRule.dispatcher) {
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
    fun saveDocumentResultUpdatesStatus() = runTest(mainDispatcherRule.dispatcher) {
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
    fun comparisonStateOpensAndClosesForResult() = runTest(mainDispatcherRule.dispatcher) {
        val context = RuntimeEnvironment.getApplication()
        val viewModel = viewModel(context) { before, preset, _ -> success(context, preset, before) }
        val result = success(context, DefaultPresets.SmallFile, source)

        viewModel.onExpandResult(result)

        assertEquals(source.uri, viewModel.shownComparison.value?.before)
        assertEquals(Uri.fromFile(result.stored.file), viewModel.shownComparison.value?.after)

        viewModel.onCloseComparison()

        assertEquals(null, viewModel.shownComparison.value)
    }

    @Test
    fun heuristicUsesWorkManagerForLargeBatches() = runTest(mainDispatcherRule.dispatcher) {
        val context = RuntimeEnvironment.getApplication()
        val viewModel = viewModel(context) { before, preset, _ -> success(context, preset, before) }

        assertTrue(viewModel.shouldRunWithWorkManager(LARGE_BATCH_THRESHOLD, runInBackground = false))
    }

    @Test
    fun heuristicUsesInActivityForSmallBatchWhenToggleOff() = runTest(mainDispatcherRule.dispatcher) {
        val context = RuntimeEnvironment.getApplication()
        val viewModel = viewModel(context) { before, preset, _ -> success(context, preset, before) }

        assertEquals(false, viewModel.shouldRunWithWorkManager(LARGE_BATCH_THRESHOLD - 1, runInBackground = false))
    }

    @Test
    fun heuristicUsesWorkManagerWhenBackgroundToggleOn() = runTest(mainDispatcherRule.dispatcher) {
        val context = RuntimeEnvironment.getApplication()
        val viewModel = viewModel(context) { before, preset, _ -> success(context, preset, before) }

        assertTrue(viewModel.shouldRunWithWorkManager(1, runInBackground = true))
    }

    @Test
    @Config(sdk = [30])
    fun notificationPermissionPolicyNoOpsBeforeAndroid13AndEnqueuesBackgroundBatch() = runTest(mainDispatcherRule.dispatcher) {
        val context = RuntimeEnvironment.getApplication()
        val scheduler = FakeBatchWorkScheduler()
        val viewModel = viewModel(context, scheduler) { before, preset, _ -> success(context, preset, before) }
        var permissionRequests = 0
        stage(viewModel, source)
        viewModel.onRunInBackgroundChanged(true)
        advanceUntilIdle()
        assertEquals(listOf(source), viewModel.sources.value)

        viewModel.onProcessAndShareRequestingNotificationsIfNeeded(
            postNotificationsGranted = false,
            requestPostNotifications = { permissionRequests += 1 },
        )
        advanceUntilIdle()

        assertEquals(0, permissionRequests)
        assertEnqueuedEventually(scheduler)
        assertEquals(false, viewModel.postNotificationsPermissionAskedThisSession)
    }

    @Test
    @Config(sdk = [33])
    fun grantedNotificationPermissionDoesNotPromptOrFlipAskedFlagAndEnqueuesBackgroundBatch() = runTest(mainDispatcherRule.dispatcher) {
        val context = RuntimeEnvironment.getApplication()
        val scheduler = FakeBatchWorkScheduler()
        val viewModel = viewModel(context, scheduler) { before, preset, _ -> success(context, preset, before) }
        var permissionRequests = 0
        stage(viewModel, source)
        viewModel.onRunInBackgroundChanged(true)
        advanceUntilIdle()
        assertEquals(listOf(source), viewModel.sources.value)

        viewModel.onProcessAndShareRequestingNotificationsIfNeeded(
            postNotificationsGranted = true,
            requestPostNotifications = { permissionRequests += 1 },
        )
        advanceUntilIdle()

        assertEquals(0, permissionRequests)
        assertEnqueuedEventually(scheduler)
        assertEquals(false, viewModel.postNotificationsPermissionAskedThisSession)
    }

    @Test
    @Config(sdk = [33])
    fun ungrantedNotificationPermissionPromptsOnceThenDenialEnqueuesBackgroundBatch() = runTest(mainDispatcherRule.dispatcher) {
        val context = RuntimeEnvironment.getApplication()
        val scheduler = FakeBatchWorkScheduler()
        val viewModel = viewModel(context, scheduler) { before, preset, _ -> success(context, preset, before) }
        var permissionRequests = 0
        stage(viewModel, source)
        viewModel.onRunInBackgroundChanged(true)
        advanceUntilIdle()
        assertEquals(listOf(source), viewModel.sources.value)

        viewModel.onProcessAndShareRequestingNotificationsIfNeeded(
            postNotificationsGranted = false,
            requestPostNotifications = { permissionRequests += 1 },
        )
        advanceUntilIdle()

        assertEquals(1, permissionRequests)
        assertEquals(true, viewModel.postNotificationsPermissionAskedThisSession)
        assertEquals(0, scheduler.enqueued.size)

        viewModel.onPostNotificationsPermissionResult(granted = false)
        assertEnqueuedEventually(scheduler)

        assertEquals(1, permissionRequests)
        assertEquals(true, viewModel.postNotificationsPermissionAskedThisSession)
    }

    @Test
    fun autoProcessOnShareStartsProcessingAfterStagingSharedUris() = runTest(mainDispatcherRule.dispatcher) {
        val context = RuntimeEnvironment.getApplication()
        val events = mutableListOf<Intent>()
        val viewModel = viewModel(context) { before, preset, _ -> success(context, preset, before) }
        val eventJob = launch { viewModel.shareEvents.collect { events.add(it) } }
        viewModel.onAutoProcessOnShareChanged(true)
        advanceUntilIdle()

        stage(viewModel, source)
        advanceUntilIdle()

        assertTrue(viewModel.processingState.value is ProcessingState.Done)
        assertEquals(Intent.ACTION_SEND, events.single().action)
        eventJob.cancel()
    }

    @Test
    fun automaticShareRemainsAvailableWhenCollectorStartsAfterProcessing() = runTest(mainDispatcherRule.dispatcher) {
        val context = RuntimeEnvironment.getApplication()
        val viewModel = viewModel(context) { before, preset, _ -> success(context, preset, before) }
        viewModel.onAutoProcessOnShareChanged(true)
        advanceUntilIdle()

        stage(viewModel, source)
        advanceUntilIdle()

        assertTrue(viewModel.processingState.value is ProcessingState.Done)
        assertEquals(Intent.ACTION_SEND, viewModel.pendingShareIntent.value?.action)
        assertEquals(Intent.ACTION_SEND, viewModel.shareEvents.first().action)
    }

    @Test
    fun chooserDismissalKeepsCompletedSourcesUntilExplicitTargetHandoff() = runTest(mainDispatcherRule.dispatcher) {
        val context = RuntimeEnvironment.getApplication()
        val second = source.copy(uri = Uri.parse("content://images/two"), displayName = "two.jpg")
        val firstCompletion = CompletableDeferred<Unit>()
        val manifestDao = ObservingBatchManifestDao(batchManifestDao())
        val viewModel = viewModel(context, manifestDao = manifestDao) { before, preset, _ ->
            if (before == source) firstCompletion.await()
            success(context, preset, before)
        }
        awaitInitialEmptyRecovery(manifestDao)
        viewModel.onAutoProcessOnShareChanged(true)

        stage(viewModel, source)
        mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()
        stage(viewModel, second)
        assertEquals(listOf(source), viewModel.sources.value)

        firstCompletion.complete(Unit)
        mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf(source), viewModel.sources.value)
        assertEquals(Intent.ACTION_SEND, viewModel.pendingShareIntent.value?.action)

        viewModel.onPendingShareDismissed()
        assertEquals(listOf(source), viewModel.sources.value)
        assertEquals(null, viewModel.pendingShareIntent.value)

        viewModel.onShareReadyOutputs()
        assertEquals(Intent.ACTION_SEND, viewModel.pendingShareIntent.value?.action)
        viewModel.onShareTargetLaunched()
        mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf(second), viewModel.sources.value)
    }
    @Test
    fun directShareHandoffReleasesAnInboundShareThatArrivesLater() = runTest(mainDispatcherRule.dispatcher) {
        val context = RuntimeEnvironment.getApplication()
        val backingManifestDao = batchManifestDao()
        val manifestDao = ObservingBatchManifestDao(backingManifestDao)
        val later = source.copy(uri = Uri.parse("content://images/later"), displayName = "later.jpg")
        val viewModel = viewModel(context, manifestDao = manifestDao) { before, preset, _ ->
            success(context, preset, before)
        }
        awaitInitialEmptyRecovery(manifestDao)
        stage(viewModel, "first-share", source)

        viewModel.onProcessAndShare()
        manifestDao.awaitUpsertFinished(2)
        advanceUntilIdle()
        assertEquals(Intent.ACTION_SEND, viewModel.pendingShareIntent.value?.action)

        viewModel.onShareTargetLaunched()
        advanceUntilIdle()
        assertTrue(viewModel.sources.value.isEmpty())

        stage(viewModel, "later-share", later)
        advanceUntilIdle()

        assertEquals(listOf(later), viewModel.sources.value)
    }
    @Test
    fun directShareHandoffAfterPartialCancellationAdvancesQueuedInboundShare() = runTest(mainDispatcherRule.dispatcher) {
        val context = RuntimeEnvironment.getApplication()
        val inFlight = source.copy(uri = Uri.parse("content://images/in-flight"), displayName = "in-flight.jpg")
        val queued = source.copy(uri = Uri.parse("content://images/queued"), displayName = "queued.jpg")
        val inFlightStarted = CompletableDeferred<Unit>()
        val backingManifestDao = batchManifestDao()
        val manifestDao = ObservingBatchManifestDao(backingManifestDao)
        val viewModel = viewModel(context, manifestDao = manifestDao) { before, preset, _ ->
            if (before == inFlight) {
                inFlightStarted.complete(Unit)
                awaitCancellation()
            }
            success(context, preset, before)
        }
        awaitInitialEmptyRecovery(manifestDao)
        stage(viewModel, "active-job", source, inFlight)

        viewModel.onProcessAndShare()
        inFlightStarted.await()
        stage(viewModel, "queued-job", queued)
        assertEquals(listOf(source, inFlight), viewModel.sources.value)

        viewModel.onCancelBatch()
        advanceUntilIdle()
        assertEquals(1, (viewModel.processingState.value as ProcessingState.Cancelled).partial.size)
        assertEquals(
            listOf(BatchProcessWorker.STATE_DONE, BatchProcessWorker.STATE_CANCELLED),
            backingManifestDao.forJob("active-job").map { it.state },
        )

        viewModel.onShareReadyOutputs()
        assertEquals(Intent.ACTION_SEND, viewModel.pendingShareIntent.value?.action)
        viewModel.onShareTargetLaunched()
        advanceUntilIdle()

        assertEquals(listOf(queued), viewModel.sources.value)
        assertEquals(null, viewModel.pendingShareIntent.value)
    }
    @Test
    fun sharedForegroundCompletionPersistsTerminalManifestForRestartRecovery() = runTest(mainDispatcherRule.dispatcher) {
        val context = RuntimeEnvironment.getApplication()
        val backingManifestDao = batchManifestDao()
        val manifestDao = ObservingBatchManifestDao(backingManifestDao)
        val viewModel = viewModel(context, manifestDao = manifestDao) { before, preset, _ ->
            success(context, preset, before)
        }
        awaitInitialEmptyRecovery(manifestDao)
        stage(viewModel, "foreground-job", source)

        viewModel.onProcessAndShare()
        manifestDao.awaitUpsertFinished(2)
        advanceUntilIdle()

        val completed = viewModel.processingState.value as ProcessingState.Done
        val result = completed.results.single() as PresetPipeline.Result.Success
        val manifest = backingManifestDao.forJob("foreground-job").single()
        assertEquals(BatchProcessWorker.STATE_DONE, manifest.state)
        assertEquals(result.stored.file.absolutePath, manifest.storedFilePath)
        assertEquals(result.stored.mimeType, manifest.outputMimeType)
        assertEquals(Intent.ACTION_SEND, viewModel.pendingShareIntent.value?.action)
    }

    @Test
    fun sharedForegroundResultWaitsForTerminalManifestCommitBeforeBecomingShareReady() = runTest(mainDispatcherRule.dispatcher) {
        val context = RuntimeEnvironment.getApplication()
        val terminalGate = CompletableDeferred<Unit>()
        val backingManifestDao = batchManifestDao()
        val manifestDao = ObservingBatchManifestDao(
            delegate = backingManifestDao,
            terminalUpsertGate = terminalGate,
        )
        var calls = 0
        val viewModel = viewModel(context, manifestDao = manifestDao) { before, preset, _ ->
            calls += 1
            success(context, preset, before)
        }
        awaitInitialEmptyRecovery(manifestDao)
        stage(viewModel, "foreground-job", source)

        viewModel.onProcessAndShare()
        manifestDao.awaitUpsertStarted(2)

        assertTrue(viewModel.processingState.value is ProcessingState.Running)
        assertEquals(null, viewModel.pendingShareIntent.value)
        assertEquals(BatchProcessWorker.STATE_QUEUED, backingManifestDao.forJob("foreground-job").single().state)
        assertEquals(1, calls)

        // A second tap while the manifest commit is in flight cannot start a competing rewrite.
        viewModel.onProcessAndShare()
        assertEquals(1, calls)

        terminalGate.complete(Unit)
        advanceUntilIdle()
        assertTrue(viewModel.processingState.value is ProcessingState.Done)
        assertEquals(Intent.ACTION_SEND, viewModel.pendingShareIntent.value?.action)

        viewModel.onPendingShareDismissed()
        viewModel.onProcessAndShare()
        advanceUntilIdle()
        assertEquals(2, calls)
    }
    @Test
    fun incomingCopiesStaySerializedWhenRecoveryCompletesDuringTheFirstCopy() = runTest(mainDispatcherRule.dispatcher) {
        val context = RuntimeEnvironment.getApplication()
        val recoveryGate = CompletableDeferred<Unit>()
        val manifestDao = ObservingBatchManifestDao(batchManifestDao(), recoveryGate)
        val intakeRepository = GatedSharedIntakeRepository(
            firstJobId = "first-job",
            firstSources = listOf(source),
            secondJobId = "second-job",
            secondSources = listOf(source.copy(uri = Uri.parse("content://images/second"), displayName = "second.jpg")),
        )
        val viewModel = viewModel(
            context = context,
            manifestDao = manifestDao,
            intakeRepositoryFactory = { _, _ -> intakeRepository },
        ) { before, preset, _ -> success(context, preset, before) }
        mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()
        manifestDao.awaitPendingStarted(1)

        viewModel.acceptSharedUris("first-job", listOf(source.uri))
        viewModel.acceptSharedUris("second-job", listOf(Uri.parse("content://images/second")))
        intakeRepository.firstStageStarted.await()

        recoveryGate.complete(Unit)
        awaitInitialEmptyRecovery(manifestDao)
        assertTrue(!intakeRepository.secondStageStarted.isCompleted)

        intakeRepository.firstStageGate.complete(Unit)
        intakeRepository.secondStageStarted.await()
    }

    @Test
    fun idleExternalShareIsNotReplacedByLaterShare() = runTest(mainDispatcherRule.dispatcher) {
        val context = RuntimeEnvironment.getApplication()
        val backingManifestDao = batchManifestDao()
        val manifestDao = ObservingBatchManifestDao(backingManifestDao)
        val second = source.copy(uri = Uri.parse("content://images/two"), displayName = "two.jpg")
        val viewModel = viewModel(context, manifestDao = manifestDao) { before, preset, _ ->
            success(context, preset, before)
        }
        awaitInitialEmptyRecovery(manifestDao)

        stage(viewModel, "first-job", source)
        advanceUntilIdle()
        stage(viewModel, "queued-job", second)
        advanceUntilIdle()

        assertEquals(listOf(source), viewModel.sources.value)
        assertEquals(BatchProcessWorker.STATE_QUEUED, backingManifestDao.forJob("queued-job").single().state)

        viewModel.onClearSources()
        advanceUntilIdle()

        assertEquals(listOf(second), viewModel.sources.value)
    }

    @Test
    fun queuedShareDiscardsAnAllFailedTerminalShareBeforeActivatingNext() = runTest(mainDispatcherRule.dispatcher) {
        val context = RuntimeEnvironment.getApplication()
        val failed = source.copy(uri = Uri.parse("content://images/failed"), displayName = "failed.jpg")
        val queued = source.copy(uri = Uri.parse("content://images/queued-after-failure"), displayName = "queued.jpg")
        val backingManifestDao = batchManifestDao()
        val manifestDao = ObservingBatchManifestDao(backingManifestDao)
        val viewModel = viewModel(context, manifestDao = manifestDao) { before, preset, _ ->
            if (before == failed) {
                PresetPipeline.Result.Failure(
                    before = before,
                    cause = EncodeError.Invalid("Conversion failed"),
                    step = PresetPipeline.Step.Encoding,
                )
            } else {
                success(context, preset, before)
            }
        }
        awaitInitialEmptyRecovery(manifestDao)
        stage(viewModel, "failed-job", failed)
        stage(viewModel, "queued-job", queued)
        assertEquals(BatchProcessWorker.STATE_QUEUED, backingManifestDao.forJob("failed-job").single().state)
        assertEquals(BatchProcessWorker.STATE_QUEUED, backingManifestDao.forJob("queued-job").single().state)

        viewModel.onProcessAndShare()
        advanceUntilIdle()
        assertTrue(backingManifestDao.forJob("failed-job").isEmpty())
        assertEquals(listOf(queued), viewModel.sources.value)
    }
    @Test
    fun failedIncomingShareDoesNotHideAlphaConflictDecision() = runTest(mainDispatcherRule.dispatcher) {
        val context = RuntimeEnvironment.getApplication()
        val viewModel = viewModel(context) { before, preset, _ ->
            PresetPipeline.Result.Failure(
                before,
                EncodeError.AlphaConflict(EncodeFormat.JPEG),
                PresetPipeline.Step.Encoding,
            )
        }
        viewModel.onPresetSelected(DefaultPresets.BestQuality.id)
        advanceUntilIdle()
        stage(viewModel, source)
        advanceUntilIdle()

        viewModel.onProcessAndShare()
        advanceUntilIdle()
        assertEquals(1, (viewModel.processingState.value as ProcessingState.Done).alphaConflictCount)

        viewModel.stageSharedUris(
            jobId = "broken-share",
            uris = listOf(Uri.parse("content://images/broken")),
            repository = FakeSharedIntakeRepository(emptyList()),
        )

        assertEquals(1, (viewModel.processingState.value as ProcessingState.Done).alphaConflictCount)
        assertEquals(listOf(source), viewModel.sources.value)
    }

    @Test
    fun completedBackgroundBatchRestoresReadyOutputWithoutReopeningChooser() = runTest(mainDispatcherRule.dispatcher) {
        val context = RuntimeEnvironment.getApplication()
        val backingManifestDao = batchManifestDao()
        val manifestDao = ObservingBatchManifestDao(backingManifestDao)
        val output = File(context.cacheDir, "restored-${System.nanoTime()}.jpg").apply { writeBytes(byteArrayOf(1, 2)) }
        backingManifestDao.upsert(
            listOf(
                BatchManifestEntity(
                    jobId = "completed-job",
                    sourceIndex = 0,
                    sourceUriString = source.uri.toString(),
                    state = BatchProcessWorker.STATE_DONE,
                    storedFilePath = output.absolutePath,
                    outputMimeType = "image/jpeg",
                    errorCode = null,
                    updatedAt = System.currentTimeMillis(),
                    sourceMimeType = source.mimeType,
                    sourceDisplayName = source.displayName,
                    sourceSizeBytes = source.sizeBytes,
                    sourceWidth = source.width,
                    sourceHeight = source.height,
                ),
            ),
        )

        val viewModel = viewModel(context, manifestDao = manifestDao) { before, preset, _ ->
            success(context, preset, before)
        }
        awaitInitialEmptyRecovery(manifestDao, expectedForJobReads = 1)

        val restored = viewModel.processingState.first { it is ProcessingState.Done } as ProcessingState.Done
        assertEquals(1, restored.results.filterIsInstance<PresetPipeline.Result.Success>().size)
        assertEquals(null, viewModel.pendingShareIntent.value)

        viewModel.onShareReadyOutputs()
        assertEquals(Intent.ACTION_SEND, viewModel.pendingShareIntent.value?.action)
    }

    @Test
    fun restoredAlphaConflictRemainsResolvableAfterRestart() = runTest(mainDispatcherRule.dispatcher) {
        val context = RuntimeEnvironment.getApplication()
        val backingManifestDao = batchManifestDao()
        backingManifestDao.upsert(
            listOf(
                BatchManifestEntity(
                    jobId = "alpha-recovery-job",
                    sourceIndex = 0,
                    sourceUriString = source.uri.toString(),
                    state = BatchProcessWorker.STATE_FAILED,
                    storedFilePath = null,
                    outputMimeType = null,
                    errorCode = BatchManifestFailureCode.alphaConflict(EncodeFormat.JPEG),
                    updatedAt = System.currentTimeMillis(),
                    sourceMimeType = source.mimeType,
                    sourceDisplayName = source.displayName,
                    sourceSizeBytes = source.sizeBytes,
                    sourceWidth = source.width,
                    sourceHeight = source.height,
                ),
            ),
        )
        val manifestDao = ObservingBatchManifestDao(backingManifestDao)
        val viewModel = viewModel(context, manifestDao = manifestDao) { before, preset, _ ->
            success(context, preset, before)
        }
        awaitInitialEmptyRecovery(manifestDao, expectedForJobReads = 1)

        val restored = viewModel.processingState.first { it is ProcessingState.Done } as ProcessingState.Done
        assertEquals(1, restored.alphaConflictCount)
        assertTrue(restored.results.single() is PresetPipeline.Result.Failure)

        viewModel.resolveAlphaConflicts(AlphaConflictStrategy.UseWhiteBackground)
        advanceUntilIdle()

        val resolved = viewModel.processingState.value as ProcessingState.Done
        assertEquals(0, resolved.alphaConflictCount)
        assertEquals(1, resolved.results.filterIsInstance<PresetPipeline.Result.Success>().size)
    }
    @Test
    fun activeAndQueuedInboundSharesRestoreInArrivalOrder() = runTest(mainDispatcherRule.dispatcher) {
        val context = RuntimeEnvironment.getApplication()
        val backingManifestDao = batchManifestDao()
        val manifestDao = ObservingBatchManifestDao(backingManifestDao)
        val second = source.copy(uri = Uri.parse("content://images/two"), displayName = "two.jpg")
        val firstViewModel = viewModel(context, manifestDao = manifestDao) { before, preset, _ ->
            success(context, preset, before)
        }
        awaitInitialEmptyRecovery(manifestDao)
        stage(firstViewModel, "first-job", source)
        stage(firstViewModel, "queued-job", second)
        assertEquals(BatchProcessWorker.STATE_QUEUED, backingManifestDao.forJob("first-job").single().state)
        assertEquals(BatchProcessWorker.STATE_QUEUED, backingManifestDao.forJob("queued-job").single().state)

        val recreatedViewModel = viewModel(context, manifestDao = manifestDao) { before, preset, _ ->
            success(context, preset, before)
        }
        awaitInitialEmptyRecovery(manifestDao, queryNumber = 2, expectedForJobReads = 4)

        assertEquals(listOf(source), recreatedViewModel.sources.value)
        recreatedViewModel.onClearSources()
        mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf(second), recreatedViewModel.sources.value)
    }

    @Test
    fun incomingShareStagesWhileStartupRecoveryIsBlockedButWaitsToReplaceTheScreen() = runTest(mainDispatcherRule.dispatcher) {
        val context = RuntimeEnvironment.getApplication()
        val restoreGate = CompletableDeferred<Unit>()
        val manifestDao = ObservingBatchManifestDao(batchManifestDao(), restoreGate)
        val intakeRepository = GatedSharedIntakeRepository(
            firstJobId = "incoming-job",
            firstSources = listOf(source),
            secondJobId = "unused-job",
            secondSources = emptyList(),
        )
        val viewModel = viewModel(
            context = context,
            manifestDao = manifestDao,
            intakeRepositoryFactory = { _, _ -> intakeRepository },
        ) { before, preset, _ ->
            success(context, preset, before)
        }
        mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()
        manifestDao.awaitPendingStarted(1)

        viewModel.acceptSharedUris("incoming-job", listOf(source.uri))
        intakeRepository.firstStageStarted.await()
        assertEquals(emptyList<SourceItem>(), viewModel.sources.value)

        // The copy has begun, but its queue row cannot race recovery's empty manifest snapshot.
        restoreGate.complete(Unit)
        awaitInitialEmptyRecovery(manifestDao)
        intakeRepository.firstStageGate.complete(Unit)
        manifestDao.awaitUpsertFinished(1)
        mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf(source), viewModel.sources.value)
    }
    @Test
    fun autoProcessOnShareUsesPersistedEnabledBeforeInitialLoadCompletes() = runTest(mainDispatcherRule.dispatcher) {
        val context = RuntimeEnvironment.getApplication()
        val events = mutableListOf<Intent>()
        val sharingDataStore = FakePreferencesDataStore()
        AutoProcessOnShareSettings(sharingDataStore).setEnabled(true)
        val viewModel = viewModel(
            context = context,
            sharingDataStore = sharingDataStore,
        ) { before, preset, _ -> success(context, preset, before) }
        val eventJob = launch { viewModel.shareEvents.collect { events.add(it) } }

        stage(viewModel, source)
        advanceUntilIdle()

        assertTrue(viewModel.processingState.value is ProcessingState.Done)
        assertEquals(Intent.ACTION_SEND, events.single().action)
        eventJob.cancel()
    }

    @Test
    fun coldLaunchAutoProcessWaitsForPersistedDefaultPreset() = runTest(mainDispatcherRule.dispatcher) {
        val context = RuntimeEnvironment.getApplication()
        val persistedDefault = CompletableDeferred<String>()
        val processedPreset = CompletableDeferred<Preset>()
        val manifestDao = ObservingBatchManifestDao(batchManifestDao())
        val sharingDataStore = FakePreferencesDataStore()
        AutoProcessOnShareSettings(sharingDataStore).setEnabled(true)
        val viewModel = viewModel(
            context = context,
            sharingDataStore = sharingDataStore,
            manifestDao = manifestDao,
            presetRepository = DelayedDefaultPresetRepository(persistedDefault),
        ) { before, preset, _ ->
            processedPreset.complete(preset)
            success(context, preset, before)
        }

        viewModel.acceptSharedUris("cold-start-share", listOf(source.uri))
        mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()
        manifestDao.awaitUpsertFinished(1)
        assertTrue(!processedPreset.isCompleted)

        persistedDefault.complete(DefaultPresets.BestQuality.id)
        awaitInitialEmptyRecovery(manifestDao, expectedForJobReads = 2)

        assertEquals(DefaultPresets.BestQuality.id, processedPreset.await().id)
    }

    @Test
    fun recoveryReenqueuesInterruptedBackgroundHandoffWithItsPersistedPresetSnapshot() = runTest(mainDispatcherRule.dispatcher) {
        val context = RuntimeEnvironment.getApplication()
        val manifestDao = batchManifestDao()
        val snapshot = DefaultPresets.BestQuality.copy(
            resize = ResizeMode.Exact(320, 240),
            alphaFallback = AlphaFallback.FillWhite,
        )
        manifestDao.upsert(
            listOf(
                BatchManifestEntity(
                    jobId = "interrupted-handoff",
                    sourceIndex = 0,
                    sourceUriString = source.uri.toString(),
                    state = BatchProcessWorker.STATE_PENDING,
                    storedFilePath = null,
                    outputMimeType = null,
                    errorCode = null,
                    updatedAt = 1L,
                    sourceMimeType = source.mimeType,
                    sourceDisplayName = source.displayName,
                    sourceSizeBytes = source.sizeBytes,
                    sourceWidth = source.width,
                    sourceHeight = source.height,
                    workerPresetId = snapshot.id,
                    workerPresetJson = snapshot.toWorkerJson(),
                ),
            ),
        )
        val scheduler = FakeBatchWorkScheduler()
        viewModel(context, scheduler, manifestDao = manifestDao) { before, preset, _ ->
            success(context, preset, before)
        }

        assertEnqueuedEventually(scheduler)

        val request = scheduler.requests.single()
        assertTrue(request.resumed)
        assertEquals(snapshot.id, request.presetId)
        val resumedPreset = DefaultPresets.BestQuality.withWorkerOverride(request.presetJson)
        assertEquals(snapshot.resize, resumedPreset.resize)
        assertEquals(snapshot.alphaFallback, resumedPreset.alphaFallback)
    }
    @Test
    fun cancellingRecoveredBackgroundBatchKeepsAlreadyConvertedOutputs() = runTest(mainDispatcherRule.dispatcher) {
        val context = RuntimeEnvironment.getApplication()
        val manifestDao = batchManifestDao()
        val snapshot = DefaultPresets.BestQuality
        val completed = success(context, snapshot, source)
        val pending = source.copy(uri = Uri.parse("content://images/pending"), displayName = "pending.jpg")
        manifestDao.upsert(
            listOf(
                BatchManifestEntity(
                    jobId = "recovered-cancel",
                    sourceIndex = 0,
                    sourceUriString = source.uri.toString(),
                    state = BatchProcessWorker.STATE_DONE,
                    storedFilePath = completed.stored.file.absolutePath,
                    outputMimeType = completed.stored.mimeType,
                    errorCode = null,
                    updatedAt = 1L,
                    workerPresetId = snapshot.id,
                    workerPresetJson = snapshot.toWorkerJson(),
                ),
                BatchManifestEntity(
                    jobId = "recovered-cancel",
                    sourceIndex = 1,
                    sourceUriString = pending.uri.toString(),
                    state = BatchProcessWorker.STATE_PENDING,
                    storedFilePath = null,
                    outputMimeType = null,
                    errorCode = null,
                    updatedAt = 1L,
                    workerPresetId = snapshot.id,
                    workerPresetJson = snapshot.toWorkerJson(),
                ),
            ),
        )
        val scheduler = ManualBatchWorkScheduler()
        val viewModel = viewModel(context, scheduler, manifestDao = manifestDao) { before, preset, _ ->
            success(context, preset, before)
        }
        scheduler.awaitResumedCount(1)
        advanceUntilIdle()
        assertEquals(listOf("recovered-cancel"), scheduler.resumed)

        viewModel.onCancelBatch()
        assertEquals(listOf("recovered-cancel"), scheduler.cancelled)
        manifestDao.update((manifestDao.forJob("recovered-cancel")[1]).copy(state = BatchProcessWorker.STATE_CANCELLED))
        scheduler.statuses.emit(BatchWorkStatus(BatchWorkState.Cancelled))
        advanceUntilIdle()

        val cancelled = viewModel.processingState.first { it is ProcessingState.Cancelled } as ProcessingState.Cancelled
        assertEquals(listOf(completed.stored.file), cancelled.partial.map { it.stored.file })
        assertEquals(BatchProcessWorker.STATE_DONE, manifestDao.forJob("recovered-cancel")[0].state)
    }
    @Test
    fun cancellationBeforeWorkerCleanupPreservesPendingSharedBatch() = runTest(mainDispatcherRule.dispatcher) {
        val context = RuntimeEnvironment.getApplication()
        val manifestDao = batchManifestDao()
        val snapshot = DefaultPresets.BestQuality
        manifestDao.upsert(
            listOf(
                BatchManifestEntity(
                    jobId = "unsettled-cancel",
                    sourceIndex = 0,
                    sourceUriString = source.uri.toString(),
                    state = BatchProcessWorker.STATE_PENDING,
                    storedFilePath = null,
                    outputMimeType = null,
                    errorCode = null,
                    updatedAt = 1L,
                    workerPresetId = snapshot.id,
                    workerPresetJson = snapshot.toWorkerJson(),
                ),
            ),
        )
        val scheduler = ManualBatchWorkScheduler()
        val viewModel = viewModel(context, scheduler, manifestDao = manifestDao) { before, preset, _ ->
            success(context, preset, before)
        }
        scheduler.awaitResumedCount(1)
        advanceUntilIdle()
        assertEquals(listOf("unsettled-cancel"), scheduler.resumed)

        viewModel.onCancelBatch()
        scheduler.statuses.emit(BatchWorkStatus(BatchWorkState.Cancelled))

        val cancelled = viewModel.processingState.first { it is ProcessingState.Cancelled } as ProcessingState.Cancelled
        assertTrue(cancelled.partial.isEmpty())
        assertEquals(BatchProcessWorker.STATE_PENDING, manifestDao.forJob("unsettled-cancel").single().state)
    }
    @Test
    fun backgroundBatchPersistsAllSourceMetadataInManifest() = runTest(mainDispatcherRule.dispatcher) {
        val context = RuntimeEnvironment.getApplication()
        val scheduler = FakeBatchWorkScheduler()
        val manifestDao = batchManifestDao()
        val viewModel = viewModel(context, scheduler, manifestDao = manifestDao) { before, preset, _ ->
            success(context, preset, before)
        }
        stage(viewModel, source)
        viewModel.onRunInBackgroundChanged(true)
        advanceUntilIdle()

        viewModel.onProcessAndShare()
        assertEnqueuedEventually(scheduler)

        val manifest = manifestDao.forJob(scheduler.enqueued.single()).single()
        assertEquals(source.mimeType, manifest.sourceMimeType)
        assertEquals(source.displayName, manifest.sourceDisplayName)
        assertEquals(source.sizeBytes, manifest.sourceSizeBytes)
        assertEquals(source.width, manifest.sourceWidth)
        assertEquals(source.height, manifest.sourceHeight)
    }

    private suspend fun awaitInitialEmptyRecovery(
        manifestDao: ObservingBatchManifestDao,
        queryNumber: Int = 1,
        expectedForJobReads: Int = 0,
    ) {
        mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()
        manifestDao.awaitPendingFinished(queryNumber)
        mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()
        manifestDao.awaitQueuedFinished(queryNumber)
        mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()
        manifestDao.awaitJobIdsFinished(queryNumber)
        if (expectedForJobReads > 0) {
            manifestDao.awaitForJobFinished(expectedForJobReads)
        }
        mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()
    }

    private suspend fun assertEnqueuedEventually(scheduler: FakeBatchWorkScheduler, expectedCount: Int = 1) {
        mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()
        if (scheduler.enqueued.size < expectedCount) {
            scheduler.awaitEnqueuedCount(expectedCount)
        }
        mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()
        assertEquals(expectedCount, scheduler.enqueued.size)
    }

    private fun viewModel(
        context: android.content.Context,
        scheduler: BatchWorkScheduler = FakeBatchWorkScheduler(),
        sharingDataStore: DataStore<Preferences> = FakePreferencesDataStore(),
        manifestDao: BatchManifestDao = batchManifestDao(),
        presetRepository: PresetRepository = FakePresetRepository(),
        intakeRepositoryFactory: (android.content.ContentResolver, File) -> SharedIntakeRepository =
            { _, _ -> FakeSharedIntakeRepository(listOf(source)) },
        result: suspend (SourceItem, Preset, String) -> PresetPipeline.Result,
    ): MainViewModel {
        return MainViewModel(
            appContext = context,
            presetRepository = presetRepository,
            shareLauncher = ShareLauncher(ShareUriResolver { _, file -> Uri.parse("content://share/${file.name}") }),
            saver = PersistentSaver(MediaStoreSaver(context.contentResolver), context.contentResolver),
            sharedIntakeRepositoryFactory = intakeRepositoryFactory,
            batchOrchestrator = BatchOrchestrator(FakePipelineRunner(result), mainDispatcherRule.dispatcher),
            persistableUriRegistry = persistableUriRegistry(context),
            batchManifestDao = manifestDao,
            batchWorkScheduler = scheduler,
            sharingTargetsRepository = DataStoreSharingTargetsRepository(sharingDataStore),
            autoProcessOnShareSettings = AutoProcessOnShareSettings(sharingDataStore),
            manifestDispatcher = mainDispatcherRule.dispatcher,
        )
    }

    private fun batchManifestDao(): BatchManifestDao = InMemoryBatchManifestDao()

    private fun persistableUriRegistry(context: android.content.Context): PersistableUriRegistry {
        return PersistableUriRegistry(testDataStore(context, "main-view-model"), context.contentResolver)
    }

    private fun testDataStore(context: android.content.Context, prefix: String) =
        PreferenceDataStoreFactory.create(
            scope = kotlinx.coroutines.CoroutineScope(Job() + Dispatchers.IO),
            produceFile = { File(context.cacheDir, "$prefix-${System.nanoTime()}.preferences_pb") },
        )

    private suspend fun stage(viewModel: MainViewModel, vararg sourceItems: SourceItem) {
        stage(viewModel, "job", *sourceItems)
    }

    private suspend fun stage(viewModel: MainViewModel, jobId: String, vararg sourceItems: SourceItem) {
        viewModel.stageSharedUris(jobId, sourceItems.map { it.uri }, FakeSharedIntakeRepository(sourceItems.toList()))
        mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()
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

private class DelayedDefaultPresetRepository(
    private val defaultId: CompletableDeferred<String>,
) : PresetRepository {
    override fun observePresets(): Flow<List<Preset>> = flowOf(DefaultPresets.ALL)

    override fun observeDefaultPresetId(): Flow<String> = flow {
        emit(defaultId.await())
    }

    override suspend fun setDefaultPresetId(id: String) = Unit

    override suspend fun getPreset(id: String): Preset? = DefaultPresets.ALL.firstOrNull { it.id == id }
}

private class FakeSharedIntakeRepository(private val sources: List<SourceItem>) : SharedIntakeRepository {
    override suspend fun stage(jobId: String, uris: List<Uri>): List<SourceItem> = sources
}

private class GatedSharedIntakeRepository(
    private val firstJobId: String,
    private val firstSources: List<SourceItem>,
    private val secondJobId: String,
    private val secondSources: List<SourceItem>,
) : SharedIntakeRepository {
    val firstStageStarted = CompletableDeferred<Unit>()
    val secondStageStarted = CompletableDeferred<Unit>()
    val firstStageGate = CompletableDeferred<Unit>()

    override suspend fun stage(jobId: String, uris: List<Uri>): List<SourceItem> = when (jobId) {
        firstJobId -> {
            firstStageStarted.complete(Unit)
            firstStageGate.await()
            firstSources
        }
        secondJobId -> {
            secondStageStarted.complete(Unit)
            secondSources
        }
        else -> error("Unexpected staged job: $jobId")
    }
}

private class FakeBatchWorkScheduler : BatchWorkScheduler {
    data class Request(
        val jobId: String,
        val presetId: String,
        val presetJson: String?,
        val resumed: Boolean,
    )

    val enqueued = mutableListOf<String>()
    val requests = mutableListOf<Request>()
    private val enqueuedCount = MutableStateFlow(0)

    override fun enqueue(jobId: String, presetId: String, customOverrideJson: String?): Flow<BatchWorkStatus> =
        record(jobId, presetId, customOverrideJson, resumed = false)

    override fun resume(jobId: String, presetId: String, customOverrideJson: String?): Flow<BatchWorkStatus> =
        record(jobId, presetId, customOverrideJson, resumed = true)

    private fun record(
        jobId: String,
        presetId: String,
        presetJson: String?,
        resumed: Boolean,
    ): Flow<BatchWorkStatus> {
        enqueued += jobId
        requests += Request(jobId, presetId, presetJson, resumed)
        enqueuedCount.value = enqueued.size
        return flowOf(BatchWorkStatus(BatchWorkState.Succeeded))
    }

    suspend fun awaitEnqueuedCount(expectedCount: Int) {
        enqueuedCount.first { it >= expectedCount }
    }

    override fun observe(jobId: String): Flow<BatchWorkStatus> =
        flowOf(BatchWorkStatus(BatchWorkState.Succeeded))

    override fun cancel(jobId: String) = Unit
}


private class ManualBatchWorkScheduler : BatchWorkScheduler {
    val statuses = MutableSharedFlow<BatchWorkStatus>()
    val resumed = mutableListOf<String>()
    val cancelled = mutableListOf<String>()
    private val resumedCount = MutableStateFlow(0)

    override fun enqueue(jobId: String, presetId: String, customOverrideJson: String?): Flow<BatchWorkStatus> = statuses

    override fun resume(jobId: String, presetId: String, customOverrideJson: String?): Flow<BatchWorkStatus> {
        resumed += jobId
        resumedCount.value = resumed.size
        return statuses
    }

    suspend fun awaitResumedCount(expectedCount: Int) {
        resumedCount.first { it >= expectedCount }
    }

    override fun observe(jobId: String): Flow<BatchWorkStatus> = statuses

    override fun cancel(jobId: String) {
        cancelled += jobId
    }
}
private class InMemoryBatchManifestDao : BatchManifestDao {
    private val lock = Any()
    private val entries = mutableMapOf<Pair<String, Int>, BatchManifestEntity>()

    override suspend fun forJob(jobId: String): List<BatchManifestEntity> = synchronized(lock) {
        entries.values.filter { it.jobId == jobId }.sortedBy { it.sourceIndex }
    }

    override suspend fun jobIds(): List<String> = synchronized(lock) {
        entries.values.groupBy { it.jobId }.entries
            .sortedByDescending { (_, rows) -> rows.maxOf { it.updatedAt } }
            .map { it.key }
    }

    override suspend fun pendingJobIds(): List<String> = jobIdsWithState(BatchProcessWorker.STATE_PENDING, descending = true)

    override suspend fun queuedJobIds(): List<String> = jobIdsWithState(BatchProcessWorker.STATE_QUEUED, descending = false)

    override suspend fun protectedJobIds(): List<String> = synchronized(lock) {
        entries.values
            .filter { it.state == BatchProcessWorker.STATE_PENDING || it.state == BatchProcessWorker.STATE_QUEUED }
            .groupBy { it.jobId }
            .entries
            .sortedByDescending { (_, rows) -> rows.maxOf { it.updatedAt } }
            .map { it.key }
    }

    override suspend fun deleteQueuedJob(jobId: String): Int = synchronized(lock) {
        val matching = entries.filter { (key, row) -> key.first == jobId && row.state == BatchProcessWorker.STATE_QUEUED }.keys
        matching.forEach(entries::remove)
        matching.size
    }

    override suspend fun upsert(entries: List<BatchManifestEntity>) {
        synchronized(lock) {
            entries.forEach { entry -> this.entries[entry.jobId to entry.sourceIndex] = entry }
        }
    }

    override suspend fun update(entry: BatchManifestEntity) {
        synchronized(lock) {
            entries[entry.jobId to entry.sourceIndex] = entry
        }
    }

    override suspend fun deleteJob(jobId: String) {
        synchronized(lock) {
            entries.filterKeys { it.first == jobId }.keys.toList().forEach(entries::remove)
        }
    }

    override suspend fun purgeOlderThan(cutoffMillis: Long): Int = synchronized(lock) {
        val keysToRemove = entries.values.groupBy { it.jobId }
            .filterValues { rows ->
                rows.maxOf { it.updatedAt } < cutoffMillis &&
                    rows.none { it.state == BatchProcessWorker.STATE_PENDING } &&
                    (rows.none { it.state == BatchProcessWorker.STATE_QUEUED } ||
                        rows.all { it.state == BatchProcessWorker.STATE_QUEUED })
            }
            .keys
            .flatMap { jobId -> entries.keys.filter { it.first == jobId } }
        keysToRemove.forEach(entries::remove)
        keysToRemove.size
    }

    private fun jobIdsWithState(state: String, descending: Boolean): List<String> = synchronized(lock) {
        val comparator = compareBy<Map.Entry<String, List<BatchManifestEntity>>> { (_, rows) ->
            if (descending) -rows.maxOf { it.updatedAt } else rows.minOf { it.updatedAt }
        }
        entries.values.filter { it.state == state }.groupBy { it.jobId }.entries
            .sortedWith(comparator)
            .map { it.key }
    }
}

private class ObservingBatchManifestDao(
    private val delegate: BatchManifestDao,
    private val pendingGate: CompletableDeferred<Unit>? = null,
    private val terminalUpsertGate: CompletableDeferred<Unit>? = null,
) : BatchManifestDao by delegate {
    private val pendingStarted = MutableStateFlow(0)
    private val pendingFinished = MutableStateFlow(0)
    private val queuedFinished = MutableStateFlow(0)
    private val jobIdsFinished = MutableStateFlow(0)
    private val forJobFinished = MutableStateFlow(0)
    private val upsertStarted = MutableStateFlow(0)
    private val upsertFinished = MutableStateFlow(0)

    override suspend fun pendingJobIds(): List<String> {
        pendingStarted.value += 1
        pendingGate?.await()
        return delegate.pendingJobIds().also { pendingFinished.value += 1 }
    }

    override suspend fun queuedJobIds(): List<String> =
        delegate.queuedJobIds().also { queuedFinished.value += 1 }

    override suspend fun jobIds(): List<String> =
        delegate.jobIds().also { jobIdsFinished.value += 1 }

    override suspend fun forJob(jobId: String): List<BatchManifestEntity> =
        delegate.forJob(jobId).also { forJobFinished.value += 1 }

    override suspend fun upsert(entries: List<BatchManifestEntity>) {
        upsertStarted.value += 1
        if (upsertStarted.value > 1) {
            terminalUpsertGate?.await()
        }
        delegate.upsert(entries)
        upsertFinished.value += 1
    }

    suspend fun awaitPendingStarted(expectedCount: Int) {
        pendingStarted.first { it >= expectedCount }
    }

    suspend fun awaitPendingFinished(expectedCount: Int) {
        pendingFinished.first { it >= expectedCount }
    }

    suspend fun awaitQueuedFinished(expectedCount: Int) {
        queuedFinished.first { it >= expectedCount }
    }

    suspend fun awaitJobIdsFinished(expectedCount: Int) {
        jobIdsFinished.first { it >= expectedCount }
    }

    suspend fun awaitForJobFinished(expectedCount: Int) {
        forJobFinished.first { it >= expectedCount }
    }

    suspend fun awaitUpsertStarted(expectedCount: Int) {
        upsertStarted.first { it >= expectedCount }
    }

    suspend fun awaitUpsertFinished(expectedCount: Int) {
        upsertFinished.first { it >= expectedCount }
    }
}

private class FakePreferencesDataStore(
    initialPreferences: Preferences = emptyPreferences(),
) : DataStore<Preferences> {
    private val state = MutableStateFlow(initialPreferences)

    override val data: Flow<Preferences> = state

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
        val updated = transform(state.value)
        state.value = updated
        return updated
    }
}

private class RecentContentProvider(
    private val displayName: String,
    private val sizeBytes: Long,
    private val mimeType: String,
) : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor = MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply {
        addRow(arrayOf<Any?>(displayName, sizeBytes))
    }

    override fun getType(uri: Uri): String = mimeType

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        throw FileNotFoundException("No dimensions fixture needed for $uri")
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}
