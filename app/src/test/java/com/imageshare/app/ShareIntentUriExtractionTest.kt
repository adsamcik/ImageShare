package com.imageshare.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.S_V2])
class ShareIntentUriExtractionTest {
    @Test
    fun legacySingleShareDropsTypeConfusedParcelable() {
        val intent = Intent(Intent.ACTION_SEND)
            .putExtra(Intent.EXTRA_STREAM, Bundle())

        assertEquals(emptyList<Uri>(), intent.extractImageShareUris())
    }

    @Test
    fun legacySingleShareDropsNonParcelableExtra() {
        val intent = Intent(Intent.ACTION_SEND)
            .putExtra(Intent.EXTRA_STREAM, "not-a-uri")

        assertEquals(emptyList<Uri>(), intent.extractImageShareUris())
    }

    @Test
    fun legacyMultipleShareRetainsOnlyUris() {
        val accepted = Uri.parse("content://imageshare.test/accepted")
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE)
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(accepted, Bundle()))

        assertEquals(listOf(accepted), intent.extractImageShareUris())
    }
}
