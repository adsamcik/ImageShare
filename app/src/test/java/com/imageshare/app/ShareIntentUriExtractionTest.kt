package com.imageshare.app

import android.content.ClipData
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

    @Test
    fun singleShareFallsBackToClipDataWhenExtraStreamIsMissing() {
        val shared = Uri.parse("content://imageshare.test/clip-only")
        val intent = Intent(Intent.ACTION_SEND).apply {
            clipData = ClipData.newRawUri("shared image", shared)
        }

        assertEquals(listOf(shared), intent.extractImageShareUris())
    }

    @Test
    fun multipleSharePreservesDuplicateExtraStreamsAndExcludesMirroredClipData() {
        val first = Uri.parse("content://imageshare.test/first")
        val second = Uri.parse("content://imageshare.test/second")
        val clipData = ClipData.newRawUri("first image", first).apply {
            addItem(ClipData.Item(second))
        }
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE)
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(first, first))
            .apply { this.clipData = clipData }

        assertEquals(listOf(first, first, second), intent.extractImageShareUris())
    }

    @Test
    fun multipleShareDropsEveryClipDataMirrorWithoutCollapsingExtraStreamPayload() {
        val first = Uri.parse("content://imageshare.test/first")
        val second = Uri.parse("content://imageshare.test/second")
        val clipData = ClipData.newRawUri("first image", first).apply {
            addItem(ClipData.Item(first))
            addItem(ClipData.Item(second))
        }
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE)
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(first, first, second))
            .apply { this.clipData = clipData }

        assertEquals(listOf(first, first, second), intent.extractImageShareUris())
    }
}
