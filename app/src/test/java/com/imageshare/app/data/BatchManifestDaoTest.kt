package com.imageshare.app.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.imageshare.app.work.BatchProcessWorker
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BatchManifestDaoTest {
    private lateinit var database: ImageShareDatabase
    private lateinit var dao: BatchManifestDao

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            ImageShareDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = database.batchManifestDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun insertUpdateDeleteAndPurge() = runTest {
        val first = entry(0)
        val second = entry(1)

        dao.upsert(listOf(first, second))
        assertEquals(listOf(first, second), dao.forJob("job"))

        dao.update(first.copy(state = BatchProcessWorker.STATE_DONE, storedFilePath = "out.jpg"))
        assertEquals(BatchProcessWorker.STATE_DONE, dao.forJob("job").first().state)

        assertEquals(1, dao.purgeOlderThan(1_501L))
        assertEquals(listOf(1), dao.forJob("job").map { it.sourceIndex })

        dao.deleteJob("job")
        assertEquals(emptyList<BatchManifestEntity>(), dao.forJob("job"))
    }


    @Test
    fun jobIdsAreOrderedByEachJobsLatestUpdate() = runTest {
        dao.upsert(
            listOf(
                entry(0).copy(jobId = "oldest", updatedAt = 100L),
                entry(1).copy(jobId = "oldest", updatedAt = 1_000L),
                entry(0).copy(jobId = "middle", updatedAt = 900L),
                entry(1).copy(jobId = "middle", updatedAt = 200L),
                entry(0).copy(jobId = "newest", updatedAt = 1_100L),
            ),
        )

        assertEquals(listOf("newest", "oldest", "middle"), dao.jobIds())
    }
    private fun entry(index: Int) = BatchManifestEntity(
        jobId = "job",
        sourceIndex = index,
        sourceUriString = "content://images/$index",
        state = BatchProcessWorker.STATE_PENDING,
        storedFilePath = null,
        outputMimeType = null,
        errorCode = null,
        updatedAt = 1_000L + index * 1_000L,
    )
}
