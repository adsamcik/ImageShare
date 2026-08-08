package com.imageshare.app

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.imageshare.app.data.BatchManifestEntity
import com.imageshare.app.data.BatchManifestFailureCode
import com.imageshare.app.data.ImageShareDatabase
import com.imageshare.app.work.BatchProcessWorker
import com.imageshare.core.io.OutputStore
import com.imageshare.core.io.SharedIntakeStager
import com.imageshare.core.processing.EncodeFormat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

@RunWith(RobolectricTestRunner::class)
class MainActivityCacheSweepTest {
    private lateinit var database: ImageShareDatabase
    private lateinit var cacheRoot: File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        database = Room.inMemoryDatabaseBuilder(context, ImageShareDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        cacheRoot = File(context.cacheDir, "main-activity-cache-sweep-${System.nanoTime()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        database.close()
        cacheRoot.deleteRecursively()
    }

    @Test
    fun retainedManifestsKeepOldInputAndOutputUntilTheirRecoveryRecordsExpire() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val outputStore = OutputStore(cacheRoot)
        val intakeStager = SharedIntakeStager(context.contentResolver, cacheRoot.resolve(SHARED_INTAKE_DIR))
        val now = System.currentTimeMillis()
        val oldOutputAge = OutputStore.DEFAULT_SWEEP_AGE_MS + 60_000L
        val retainedOutput = outputStore.root().resolve("completed-job").resolve("result.jpg").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(1))
            setLastModified(now - oldOutputAge)
            parentFile?.setLastModified(now - oldOutputAge)
        }
        val orphanOutput = outputStore.root().resolve("orphan-job").resolve("result.jpg").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(2))
            setLastModified(now - oldOutputAge)
            parentFile?.setLastModified(now - oldOutputAge)
        }
        val oldInputAge = SharedIntakeStager.DEFAULT_SWEEP_AGE_MS + 60_000L
        val retainedInput = cacheRoot.resolve(SHARED_INTAKE_DIR).resolve("alpha-conflict-job").resolve("0-input.png").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(3))
            setLastModified(now - oldInputAge)
            parentFile?.setLastModified(now - oldInputAge)
        }
        val orphanInput = cacheRoot.resolve(SHARED_INTAKE_DIR).resolve("orphan-job").resolve("0-input.png").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(4))
            setLastModified(now - oldInputAge)
            parentFile?.setLastModified(now - oldInputAge)
        }
        database.batchManifestDao().upsert(
            listOf(
                BatchManifestEntity(
                    jobId = "completed-job",
                    sourceIndex = 0,
                    sourceUriString = "file:///staged/input.jpg",
                    state = BatchProcessWorker.STATE_DONE,
                    storedFilePath = retainedOutput.absolutePath,
                    outputMimeType = "image/jpeg",
                    errorCode = null,
                    updatedAt = now - (6L * 24L * 60L * 60L * 1_000L),
                ),
                BatchManifestEntity(
                    jobId = "alpha-conflict-job",
                    sourceIndex = 0,
                    sourceUriString = retainedInput.toURI().toString(),
                    state = BatchProcessWorker.STATE_FAILED,
                    storedFilePath = null,
                    outputMimeType = null,
                    errorCode = BatchManifestFailureCode.alphaConflict(EncodeFormat.JPEG),
                    updatedAt = now - (6L * 24L * 60L * 60L * 1_000L),
                ),
            ),
        )

        sweepSharedCaches(
            batchManifestDao = database.batchManifestDao(),
            sharedIntakeStager = intakeStager,
            outputStore = outputStore,
            nowMillis = now,
        )

        assertTrue(retainedOutput.exists())
        assertFalse(orphanOutput.parentFile?.exists() == true)
        assertTrue(retainedInput.exists())
        assertFalse(orphanInput.parentFile?.exists() == true)
        assertTrue(database.batchManifestDao().forJob("completed-job").isNotEmpty())
        assertTrue(database.batchManifestDao().forJob("alpha-conflict-job").isNotEmpty())
    }
}