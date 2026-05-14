package com.imageshare.app

import android.content.Context
import android.os.Looper
import androidx.room.Room
import androidx.work.Configuration
import androidx.work.testing.WorkManagerTestInitHelper
import com.imageshare.app.data.BatchManifestEntity
import com.imageshare.app.data.ImageShareDatabase
import com.imageshare.app.work.BatchProcessWorker
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class WorkManagerBatchWorkSchedulerTest {
    private lateinit var context: Context
    private lateinit var database: ImageShareDatabase

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        AppContainer.init(context)
        database = Room.inMemoryDatabaseBuilder(context, ImageShareDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        AppContainer.overrideForTests(batchManifestDao = database.batchManifestDao())
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
    fun observeWithoutWorkInfoCancelsOrphanedManifestRows() = runBlocking {
        database.batchManifestDao().upsert(
            listOf(
                manifestRow(0, BatchProcessWorker.STATE_PENDING),
                manifestRow(1, BatchProcessWorker.STATE_PENDING),
                manifestRow(2, BatchProcessWorker.STATE_DONE),
            ),
        )

        val statusDeferred = async { WorkManagerBatchWorkScheduler(context).observe(JOB_ID).first() }
        repeat(10) {
            shadowOf(Looper.getMainLooper()).idle()
            if (statusDeferred.isCompleted) return@repeat
            delay(50)
        }
        val status = withTimeout(5_000) { statusDeferred.await() }

        assertEquals(BatchWorkState.Cancelled, status.state)
        val rows = database.batchManifestDao().forJob(JOB_ID)
        assertEquals(2, rows.count { it.state == BatchProcessWorker.STATE_CANCELLED })
        assertEquals(1, rows.count { it.state == BatchProcessWorker.STATE_DONE })
    }

    private fun manifestRow(index: Int, state: String) = BatchManifestEntity(
        jobId = JOB_ID,
        sourceIndex = index,
        sourceUriString = "content://images/$index",
        state = state,
        storedFilePath = null,
        outputMimeType = null,
        errorCode = null,
        updatedAt = 1_000L,
    )

    private companion object {
        const val JOB_ID = "orphaned-job"
    }
}
