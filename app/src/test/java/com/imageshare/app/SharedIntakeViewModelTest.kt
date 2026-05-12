package com.imageshare.app

import android.net.Uri
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SharedIntakeViewModelTest {
    @Test
    fun stageSharedUrisUpdatesSourcesAndSweeps() = runBlocking {
        val viewModel = SharedIntakeViewModel()
        val repository = FakeSharedIntakeRepository(
            listOf(StagedSource(Uri.parse("file://cached/image.jpg"), "image.jpg", 12L)),
        )

        viewModel.stageSharedUris("job", listOf(Uri.parse("content://source/image")), repository)

        assertEquals(1, viewModel.sources.value.size)
        assertEquals("image.jpg", viewModel.sources.value.first().displayName)
        assertEquals("job", repository.jobId)
        assertEquals(true, repository.swept)
    }
}

private class FakeSharedIntakeRepository(
    private val stagedSources: List<StagedSource>,
) : SharedIntakeRepository {
    var jobId: String? = null
    var swept: Boolean = false

    override suspend fun stage(jobId: String, uris: List<Uri>): List<StagedSource> {
        this.jobId = jobId
        return stagedSources
    }

    override suspend fun sweep() {
        swept = true
    }
}
