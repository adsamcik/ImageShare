package com.imageshare.core.io

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

@RunWith(RobolectricTestRunner::class)
class OutputStoreTest {
    @Test
    fun storeWritesBytesUnderSharedOutputJobDir() = runBlocking {
        val cacheRoot = freshCacheRoot("write")
        val bytes = byteArrayOf(1, 2, 3, 4)

        val item = OutputStore(cacheRoot).store("job-a", "image.jpg", bytes, "image/jpeg")

        val expectedFile = cacheRoot.resolve("shared-output").resolve("job-a").resolve("image.jpg")
        assertEquals(expectedFile, item.file)
        assertEquals("image.jpg", item.filename)
        assertEquals(bytes.size.toLong(), item.sizeBytes)
        assertArrayEquals(bytes, item.file.readBytes())
    }

    @Test
    fun storeSanitizesUnsafeFilenameInsideJobDir() = runBlocking {
        val cacheRoot = freshCacheRoot("unsafe")

        val item = OutputStore(cacheRoot).store("job", "../etc/passwd", byteArrayOf(9), "image/jpeg")

        val jobDir = cacheRoot.resolve("shared-output").resolve("job").canonicalFile
        assertEquals(jobDir, requireNotNull(item.file.parentFile).canonicalFile)
        assertFalse(item.filename.contains(".."))
        assertFalse(item.filename.contains("/"))
        assertFalse(item.filename.contains("\\"))
        assertArrayEquals(byteArrayOf(9), item.file.readBytes())
    }

    @Test
    fun storeSanitizesControlCharsAndFallsBackForEmptyFilename() = runBlocking {
        val cacheRoot = freshCacheRoot("fallback")
        val store = OutputStore(cacheRoot)

        val control = store.store("job", "\u0000bad\u0001name.jpg", byteArrayOf(1), "image/jpeg")
        val empty = store.store("job", "", byteArrayOf(2), "image/jpeg")

        assertEquals("badname.jpg", control.filename)
        assertEquals("image.bin", empty.filename)
    }

    @Test
    fun storeMultipleItemsInSameJobDirectory() = runBlocking {
        val cacheRoot = freshCacheRoot("multi")
        val store = OutputStore(cacheRoot)

        val first = store.store("job", "a.jpg", byteArrayOf(1), "image/jpeg")
        val second = store.store("job", "b.jpg", byteArrayOf(2), "image/jpeg")

        assertEquals(
            requireNotNull(first.file.parentFile).canonicalFile,
            requireNotNull(second.file.parentFile).canonicalFile,
        )
        assertTrue(first.file.exists())
        assertTrue(second.file.exists())
    }

    @Test
    fun sweepDeletesOnlyOldJobDirsUnderSharedOutput() = runBlocking {
        val cacheRoot = freshCacheRoot("sweep")
        val sharedOutput = cacheRoot.resolve(OutputStore.SUBDIR_NAME).apply { mkdirs() }
        val oldOne = jobDir(sharedOutput, "old-one", "old")
        val oldTwo = jobDir(sharedOutput, "old-two", "old")
        val fresh = jobDir(sharedOutput, "fresh", "fresh")
        val directFile = sharedOutput.resolve("direct").apply { writeText("keep") }
        val intakeDir = cacheRoot.resolve("shared-intake").apply { mkdirs() }
        val now = System.currentTimeMillis()
        oldOne.setLastModified(now - OLD_JOB_AGE_MS)
        oldTwo.setLastModified(now - OLD_JOB_AGE_MS)
        fresh.setLastModified(now)
        sharedOutput.setLastModified(now - OLD_JOB_AGE_MS)

        OutputStore(cacheRoot).sweep(olderThanMillis = SWEEP_AGE_MS)

        assertFalse(oldOne.exists())
        assertFalse(oldTwo.exists())
        assertTrue(fresh.exists())
        assertTrue(sharedOutput.exists())
        assertTrue(directFile.exists())
        assertTrue(intakeDir.exists())
    }

    private fun freshCacheRoot(name: String): File =
        File(RuntimeEnvironment.getApplication().cacheDir, "output-store-$name-${System.nanoTime()}")
            .apply {
                deleteRecursively()
                mkdirs()
            }

    private fun jobDir(root: File, name: String, content: String): File =
        root.resolve(name).apply {
            mkdirs()
            resolve("item").writeText(content)
        }
}

private const val SWEEP_AGE_MS = 1_000L
private const val OLD_JOB_AGE_MS = 10_000L
