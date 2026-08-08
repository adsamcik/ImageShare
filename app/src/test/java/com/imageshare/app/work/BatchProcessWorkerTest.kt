package com.imageshare.app.work

import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import androidx.room.Room
import androidx.work.Configuration
import androidx.work.Data
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.imageshare.app.AppContainer
import com.imageshare.app.data.BatchItemError
import com.imageshare.app.data.BatchManifestDao
import com.imageshare.app.data.BatchManifestEntity
import com.imageshare.app.data.ImageShareDatabase
import com.imageshare.app.processing.BatchOrchestrator
import com.imageshare.app.processing.PresetPipeline
import com.imageshare.app.processing.PresetPipelineRunner
import com.imageshare.core.io.OutputStore
import com.imageshare.core.io.SourceItem
import com.imageshare.core.processing.EncodeFormat
import com.imageshare.feature.preset.DefaultPresets
import com.imageshare.feature.preset.Preset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class BatchProcessWorkerTest {
    private lateinit var context: Context
    private lateinit var database: ImageShareDatabase

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        AppContainer.init(context)
        database = Room.inMemoryDatabaseBuilder(context, ImageShareDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setMinimumLoggingLevel(android.util.Log.DEBUG).build(),
        )
    }

    @After
    fun tearDown() {
        AppContainer.overrideForTests()
        database.close()
    }

    @Test
    fun unboundedBatchForegroundServiceUsesMediaProcessing() {
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING,
            BatchProcessWorker.foregroundServiceTypeForBatch(),
        )
    }

    @Test
    fun doWorkUpdatesManifestRowsToDone() = runTest {
        seedManifest()
        AppContainer.overrideForTests(
            batchManifestDao = database.batchManifestDao(),
            batchOrchestrator = BatchOrchestrator(FakePipeline(context), StandardTestDispatcher(testScheduler)),
            presetRepository = WorkerPresetRepository,
        )

        val result = worker().doWork()

        assertEquals(androidx.work.ListenableWorker.Result.success(), result)
        val rows = database.batchManifestDao().forJob(JOB_ID)
        assertEquals(3, rows.count { it.state == BatchProcessWorker.STATE_DONE })
        assertTrue(rows.all { it.storedFilePath != null })
    }

    @Test
    fun workerRestoresAllSourceMetadataFromManifest() = runTest {
        seedManifest()
        val seenSources = mutableListOf<SourceItem>()
        AppContainer.overrideForTests(
            batchManifestDao = database.batchManifestDao(),
            batchOrchestrator = BatchOrchestrator(
                FakePipeline(context) { source, preset, jobId ->
                    seenSources += source
                    success(context, source, preset, jobId)
                },
                StandardTestDispatcher(testScheduler),
            ),
            presetRepository = WorkerPresetRepository,
        )

        val result = worker().doWork()

        assertEquals(androidx.work.ListenableWorker.Result.success(), result)
        assertEquals((0 until 3).map(::sourceFor), seenSources)
    }

    @Test
    fun cancellationMarksPendingRowsCancelled() = runTest {
        seedManifest()
        AppContainer.overrideForTests(
            batchManifestDao = database.batchManifestDao(),
            batchOrchestrator = BatchOrchestrator(
                FakePipeline(context) { source, preset, jobId ->
                    if (source.uri.toString().endsWith("/1")) throw CancellationException("stop")
                    success(context, source, preset, jobId)
                },
                StandardTestDispatcher(testScheduler),
            ),
            presetRepository = WorkerPresetRepository,
        )

        val outcome = runCatching { worker().doWork() }

        val rows = database.batchManifestDao().forJob(JOB_ID)
        assertTrue(outcome.exceptionOrNull() is CancellationException)
        assertEquals(2, rows.count { it.state == BatchProcessWorker.STATE_CANCELLED })
    }

    @Test
    fun cancelledMidBatchFlipsPendingRowsToCancelled() = runTest {
        seedManifest()
        val item1Started = CompletableDeferred<Unit>()
        AppContainer.overrideForTests(
            batchManifestDao = database.batchManifestDao(),
            batchOrchestrator = BatchOrchestrator(
                FakePipeline(context) { source, preset, jobId ->
                    when (source.uri.lastPathSegment) {
                        "1" -> {
                            item1Started.complete(Unit)
                            delay(60_000)
                        }
                    }
                    success(context, source, preset, jobId)
                },
                StandardTestDispatcher(testScheduler),
            ),
            presetRepository = WorkerPresetRepository,
        )

        val workJob = async(Dispatchers.IO) { worker().doWork() }
        item1Started.await()

        workJob.cancelAndJoin()

        val rows = database.batchManifestDao().forJob(JOB_ID)
        assertEquals(BatchProcessWorker.STATE_CANCELLED, rows[1].state)
        assertEquals(BatchProcessWorker.STATE_CANCELLED, rows[2].state)
    }

    @Test
    fun cancellationMidBatchPersistsFinalProgressAndCancelledStateUnderNonCancellable() = runTest {
        seedManifest()
        val item1Started = CompletableDeferred<Unit>()
        val cancellationAwareDao = CancellationAwareBatchManifestDao(database.batchManifestDao())
        AppContainer.overrideForTests(
            batchManifestDao = cancellationAwareDao,
            batchOrchestrator = BatchOrchestrator(
                FakePipeline(context) { source, preset, jobId ->
                    when (source.uri.lastPathSegment) {
                        "0" -> success(context, source, preset, jobId)
                        "1" -> {
                            item1Started.complete(Unit)
                            delay(60_000)
                            success(context, source, preset, jobId)
                        }
                        else -> success(context, source, preset, jobId)
                    }
                },
                StandardTestDispatcher(testScheduler),
            ),
            presetRepository = WorkerPresetRepository,
        )
        val worker = worker()

        val workJob = async(Dispatchers.IO) { worker.doWork() }
        item1Started.await()

        workJob.cancelAndJoin()

        val rows = database.batchManifestDao().forJob(JOB_ID)
        // The first row can only stay Done if runBatch's final applyProgress survives cancellation;
        // the outer cancellation cleanup only flips Pending rows to Cancelled.
        assertEquals(BatchProcessWorker.STATE_DONE, rows[0].state)
        assertTrue(rows[0].storedFilePath != null)
        assertEquals(BatchProcessWorker.STATE_CANCELLED, rows[1].state)
        assertEquals(BatchProcessWorker.STATE_CANCELLED, rows[2].state)
        assertEquals(listOf(true, true), cancellationAwareDao.cancelledUpdateContexts)
    }

    @Test
    fun itemFailurePersistsOnlyAllowlistedErrorCode() = runTest {
        seedManifest()
        val sensitiveMessage = "content://other.app/private/album/secret.jpg"
        AppContainer.overrideForTests(
            batchManifestDao = database.batchManifestDao(),
            batchOrchestrator = BatchOrchestrator(
                FakePipeline(context) { source, preset, jobId ->
                    if (source.uri.lastPathSegment == "0") {
                        PresetPipeline.Result.Failure(
                            before = source,
                            cause = IllegalStateException(sensitiveMessage),
                            step = PresetPipeline.Step.Encoding,
                        )
                    } else {
                        success(context, source, preset, jobId)
                    }
                },
                StandardTestDispatcher(testScheduler),
            ),
            presetRepository = WorkerPresetRepository,
        )

        val result = worker().doWork()

        assertEquals(androidx.work.ListenableWorker.Result.success(), result)
        val failedRow = database.batchManifestDao().forJob(JOB_ID).first()
        assertEquals(BatchProcessWorker.STATE_FAILED, failedRow.state)
        assertEquals(BatchItemError.Encode.name, failedRow.errorCode)
        assertTrue(failedRow.toString().contains(sensitiveMessage).not())
    }

    @Test
    fun progressEmissionsCoalescedTo10Hz() = runTest {
        seedManifest()
        val countingDao = CountingBatchManifestDao(database.batchManifestDao())
        AppContainer.overrideForTests(
            batchManifestDao = countingDao,
            batchOrchestrator = BatchOrchestrator(
                FakePipeline(context, progressEmissions = 100),
                StandardTestDispatcher(testScheduler),
            ),
            presetRepository = WorkerPresetRepository,
        )

        val result = worker().doWork()

        assertEquals(androidx.work.ListenableWorker.Result.success(), result)
        assertTrue("Expected coalesced DAO updates, got ${countingDao.updateCount}", countingDao.updateCount <= 12)
        val rows = database.batchManifestDao().forJob(JOB_ID)
        assertEquals(3, rows.count { it.state == BatchProcessWorker.STATE_DONE })
    }

    private suspend fun seedManifest() {
        database.batchManifestDao().upsert(
            (0 until 3).map { index ->
                val source = sourceFor(index)
                BatchManifestEntity(
                    jobId = JOB_ID,
                    sourceIndex = index,
                    sourceUriString = source.uri.toString(),
                    state = BatchProcessWorker.STATE_PENDING,
                    storedFilePath = null,
                    outputMimeType = null,
                    errorCode = null,
                    updatedAt = 1_000L,
                    sourceMimeType = source.mimeType,
                    sourceDisplayName = source.displayName,
                    sourceSizeBytes = source.sizeBytes,
                    sourceWidth = source.width,
                    sourceHeight = source.height,
                )
            },
        )
    }

    private fun sourceFor(index: Int): SourceItem = SourceItem(
        uri = Uri.parse("content://images/$index"),
        mimeType = "image/png",
        displayName = "incoming-$index.png",
        sizeBytes = 10_000L + index,
        width = 6_000 + index,
        height = 4_000 + index,
    )

    private fun worker(): BatchProcessWorker = TestListenableWorkerBuilder<BatchProcessWorker>(context)
        .setInputData(
            Data.Builder()
                .putString(BatchProcessWorker.KEY_JOB_ID, JOB_ID)
                .putString(BatchProcessWorker.KEY_PRESET_ID, DefaultPresets.DEFAULT_PRESET_ID)
                .build(),
        ).build()

    private companion object {
        const val JOB_ID = "job"
    }
}

private class FakePipeline(
    private val context: Context,
    private val progressEmissions: Int = 1,
    private val result: suspend (SourceItem, Preset, String) -> PresetPipeline.Result = { source, preset, jobId ->
        success(context, source, preset, jobId)
    },
) : PresetPipelineRunner {
    override suspend fun run(
        source: SourceItem,
        preset: Preset,
        jobId: String,
        onProgress: (PresetPipeline.Step) -> Unit,
    ): PresetPipeline.Result {
        repeat(progressEmissions) { onProgress(PresetPipeline.Step.Storing) }
        return result(source, preset, jobId)
    }
}

private fun success(
    context: Context,
    source: SourceItem,
    preset: Preset,
    jobId: String,
): PresetPipeline.Result.Success {
    val file = File(context.cacheDir, "${source.uri.lastPathSegment}-${preset.id}.jpg").apply {
        writeBytes(byteArrayOf(1, 2, 3))
    }
    return PresetPipeline.Result.Success(
        OutputStore.StoredItem(jobId, file, file.name, file.length(), "image/jpeg"),
        source,
        100,
        80,
        EncodeFormat.JPEG,
    )
}

private object WorkerPresetRepository : com.imageshare.feature.preset.PresetRepository {
    override fun observePresets() = kotlinx.coroutines.flow.flowOf(DefaultPresets.ALL)
    override fun observeDefaultPresetId() = kotlinx.coroutines.flow.flowOf(DefaultPresets.DEFAULT_PRESET_ID)
    override suspend fun setDefaultPresetId(id: String) = Unit
    override suspend fun getPreset(id: String): Preset? = DefaultPresets.ALL.firstOrNull { it.id == id }
}

private class CountingBatchManifestDao(
    private val delegate: BatchManifestDao,
) : BatchManifestDao {
    var updateCount: Int = 0
        private set

    override suspend fun forJob(jobId: String): List<BatchManifestEntity> = delegate.forJob(jobId)

    override suspend fun jobIds(): List<String> = delegate.jobIds()

    override suspend fun pendingJobIds(): List<String> = delegate.pendingJobIds()

    override suspend fun upsert(entries: List<BatchManifestEntity>) = delegate.upsert(entries)

    override suspend fun update(entry: BatchManifestEntity) {
        updateCount += 1
        delegate.update(entry)
    }

    override suspend fun deleteJob(jobId: String) = delegate.deleteJob(jobId)

    override suspend fun purgeOlderThan(cutoffMillis: Long): Int = delegate.purgeOlderThan(cutoffMillis)
}

private class CancellationAwareBatchManifestDao(
    private val delegate: BatchManifestDao,
) : BatchManifestDao by delegate {
    val cancelledUpdateContexts = mutableListOf<Boolean>()

    override suspend fun update(entry: BatchManifestEntity) {
        if (entry.state == BatchProcessWorker.STATE_CANCELLED) {
            cancelledUpdateContexts += currentCoroutineContext().isActive
        }
        delegate.update(entry)
    }
}
