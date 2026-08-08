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
import com.imageshare.app.processing.BatchOrchestrator
import com.imageshare.app.saving.PersistentSaver
import com.imageshare.core.io.MediaStoreSaver
import com.imageshare.app.processing.PresetPipeline
import com.imageshare.app.processing.PresetPipelineRunner
import com.imageshare.app.work.BatchProcessWorker
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.StandardTestDispatcher
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
    fun onRecentSelectedRestagesSourceInSourcesFlow() = runTest {
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

    @Test
    fun heuristicUsesWorkManagerForLargeBatches() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val viewModel = viewModel(context) { before, preset, _ -> success(context, preset, before) }

        assertTrue(viewModel.shouldRunWithWorkManager(LARGE_BATCH_THRESHOLD, runInBackground = false))
    }

    @Test
    fun heuristicUsesInActivityForSmallBatchWhenToggleOff() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val viewModel = viewModel(context) { before, preset, _ -> success(context, preset, before) }

        assertEquals(false, viewModel.shouldRunWithWorkManager(LARGE_BATCH_THRESHOLD - 1, runInBackground = false))
    }

    @Test
    fun heuristicUsesWorkManagerWhenBackgroundToggleOn() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val viewModel = viewModel(context) { before, preset, _ -> success(context, preset, before) }

        assertTrue(viewModel.shouldRunWithWorkManager(1, runInBackground = true))
    }

    @Test
    @Config(sdk = [30])
    fun notificationPermissionPolicyNoOpsBeforeAndroid13AndEnqueuesBackgroundBatch() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val scheduler = FakeBatchWorkScheduler()
        val viewModel = viewModel(context, scheduler) { before, preset, _ -> success(context, preset, before) }
        var permissionRequests = 0
        stage(viewModel, source)
        viewModel.onRunInBackgroundChanged(true)
        advanceUntilIdle()

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
    fun grantedNotificationPermissionDoesNotPromptOrFlipAskedFlagAndEnqueuesBackgroundBatch() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val scheduler = FakeBatchWorkScheduler()
        val viewModel = viewModel(context, scheduler) { before, preset, _ -> success(context, preset, before) }
        var permissionRequests = 0
        stage(viewModel, source)
        viewModel.onRunInBackgroundChanged(true)
        advanceUntilIdle()

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
    fun ungrantedNotificationPermissionPromptsOnceThenDenialEnqueuesBackgroundBatch() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val scheduler = FakeBatchWorkScheduler()
        val viewModel = viewModel(context, scheduler) { before, preset, _ -> success(context, preset, before) }
        var permissionRequests = 0
        stage(viewModel, source)
        viewModel.onRunInBackgroundChanged(true)
        advanceUntilIdle()

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
        viewModel.processingState.first { it is ProcessingState.Done }

        viewModel.onProcessAndShareRequestingNotificationsIfNeeded(
            postNotificationsGranted = false,
            requestPostNotifications = { permissionRequests += 1 },
        )
        // This is a later explicit request after the first background job completed, not an in-flight
        // double tap; the chaos-workstream guard should suppress only active duplicate starts.
        assertEnqueuedEventually(scheduler, expectedCount = 2)
        assertEquals(1, permissionRequests)
        assertEquals(true, viewModel.postNotificationsPermissionAskedThisSession)
    }

    @Test
    fun autoProcessOnShareStartsProcessingAfterStagingSharedUris() = runTest {
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
    fun autoProcessOnShareUsesPersistedEnabledBeforeInitialLoadCompletes() = runTest {
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
    fun backgroundBatchPersistsAllSourceMetadataInManifest() = runTest {
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
        result: suspend (SourceItem, Preset, String) -> PresetPipeline.Result,
    ): MainViewModel {
        return MainViewModel(
            appContext = context,
            presetRepository = FakePresetRepository(),
            shareLauncher = ShareLauncher(ShareUriResolver { _, file -> Uri.parse("content://share/${file.name}") }),
            saver = PersistentSaver(MediaStoreSaver(context.contentResolver), context.contentResolver),
            sharedIntakeRepositoryFactory = { _, _ -> FakeSharedIntakeRepository(listOf(source)) },
            batchOrchestrator = BatchOrchestrator(FakePipelineRunner(result), mainDispatcherRule.dispatcher),
            persistableUriRegistry = persistableUriRegistry(context),
            batchManifestDao = manifestDao,
            batchWorkScheduler = scheduler,
            sharingTargetsRepository = DataStoreSharingTargetsRepository(sharingDataStore),
            autoProcessOnShareSettings = AutoProcessOnShareSettings(sharingDataStore),
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

private class FakeBatchWorkScheduler : BatchWorkScheduler {
    val enqueued = mutableListOf<String>()
    private val enqueuedCount = MutableStateFlow(0)

    override fun enqueue(jobId: String, presetId: String, customOverrideJson: String?): Flow<BatchWorkStatus> {
        enqueued += jobId
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
