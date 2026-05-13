package com.imageshare.app.work

import android.content.Context
import androidx.room.Room
import androidx.work.Configuration
import androidx.work.Data
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.imageshare.app.AppContainer
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CancellationException
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

    private suspend fun seedManifest() {
        database.batchManifestDao().upsert(
            (0 until 3).map { index ->
                BatchManifestEntity(
                    jobId = JOB_ID,
                    sourceIndex = index,
                    sourceUriString = "content://images/$index",
                    state = BatchProcessWorker.STATE_PENDING,
                    storedFilePath = null,
                    outputMimeType = null,
                    errorMessage = null,
                    updatedAt = 1_000L,
                )
            },
        )
    }

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
        onProgress(PresetPipeline.Step.Storing)
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
