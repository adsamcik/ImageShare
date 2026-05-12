package com.imageshare.core.io

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

@RunWith(RobolectricTestRunner::class)
class ShareLauncherTest {
    @Test
    fun buildShareIntentSingleIncludesContentUriMimeAndReadGrant() {
        val context = testContext()
        val item = storedItem(context, "one.jpg", "image/jpeg")

        val intent = shareLauncher().buildShareIntent(context, item)
        val uri = intent.getParcelableStream()

        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("image/jpeg", intent.type)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertTrue(uri.toString().startsWith("content://${context.packageName}.shareprovider/"))
    }

    @Test
    fun buildShareIntentMultiSameMimePreservesMime() {
        val context = testContext()
        val items = listOf(
            storedItem(context, "one.jpg", "image/jpeg"),
            storedItem(context, "two.jpg", "image/jpeg"),
        )

        val intent = shareLauncher().buildShareIntent(context, items)

        assertEquals(Intent.ACTION_SEND_MULTIPLE, intent.action)
        assertEquals("image/jpeg", intent.type)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }

    @Test
    fun buildShareIntentMultiMixedMimeFallsBackToImageWildcard() {
        val context = testContext()
        val items = listOf(
            storedItem(context, "one.jpg", "image/jpeg"),
            storedItem(context, "two.png", "image/png"),
        )

        val intent = shareLauncher().buildShareIntent(context, items)

        assertEquals(Intent.ACTION_SEND_MULTIPLE, intent.action)
        assertEquals("image/*", intent.type)
    }

    @Test
    fun buildShareIntentEmptyThrows() {
        val context = testContext()

        assertThrows(IllegalArgumentException::class.java) {
            shareLauncher().buildShareIntent(context, emptyList())
        }
    }

    private fun storedItem(context: Context, filename: String, mimeType: String): OutputStore.StoredItem {
        val file = File(context.cacheDir, "shared-output/job-$filename/$filename").apply {
            parentFile?.mkdirs()
            writeText(filename)
        }
        return OutputStore.StoredItem(
            jobId = "job-$filename",
            file = file,
            filename = filename,
            sizeBytes = file.length(),
            mimeType = mimeType,
        )
    }

    private fun shareLauncher(): ShareLauncher =
        ShareLauncher { context, file ->
            Uri.parse("content://${context.packageName}.shareprovider/shared-output/${file.name}")
        }

    private fun testContext(): Context = RuntimeEnvironment.getApplication()
}

private fun Intent.getParcelableStream(): Uri? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(Intent.EXTRA_STREAM)
    }
