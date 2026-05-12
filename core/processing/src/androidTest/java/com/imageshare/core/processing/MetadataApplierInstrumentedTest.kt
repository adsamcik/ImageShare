package com.imageshare.core.processing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MetadataApplierInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val applier = MetadataApplier()

    @Test
    fun jpegStripAllRemovesPrivacySensitiveTags() = runBlocking {
        val input = imageWithExif("metadata-input.jpg", Bitmap.CompressFormat.JPEG, 1024, 768)

        val result = applier.apply(input, EncodeFormat.JPEG, MetadataMode.StripAll)
        val exif = exifFromBytes(result, "metadata-output.jpg")

        PRIVACY_TAGS.forEach { tag ->
            assertNull("Expected $tag to be stripped", exif.getAttribute(tag))
        }
    }

    @Test
    fun webpLossyStripAllRemovesPrivacySensitiveTags() = runBlocking {
        val input = imageWithExif("metadata-input.webp", Bitmap.CompressFormat.WEBP_LOSSY, 1024, 768)

        val result = applier.apply(input, EncodeFormat.WEBP_LOSSY, MetadataMode.StripAll)
        val exif = exifFromBytes(result, "metadata-output.webp")

        PRIVACY_TAGS.forEach { tag ->
            assertNull("Expected $tag to be stripped", exif.getAttribute(tag))
        }
    }

    private fun imageWithExif(
        name: String,
        format: Bitmap.CompressFormat,
        width: Int,
        height: Int,
    ): ByteArray {
        val file = File(context.cacheDir, name)
        file.writeBytes(bitmapBytes(format, width, height))
        ExifInterface(file).apply {
            setAttribute(ExifInterface.TAG_GPS_LATITUDE, "12/1,20/1,42000/1000")
            setAttribute(ExifInterface.TAG_GPS_LONGITUDE, "67/1,53/1,24000/1000")
            setAttribute(ExifInterface.TAG_GPS_ALTITUDE, "123/1")
            setAttribute(ExifInterface.TAG_GPS_TIMESTAMP, "12:34:56")
            setAttribute(ExifInterface.TAG_GPS_DATESTAMP, "2024:01:01")
            setAttribute(ExifInterface.TAG_GPS_PROCESSING_METHOD, "GPS")
            setAttribute(ExifInterface.TAG_DATETIME, "2024:01:01 12:00:00")
            setAttribute(ExifInterface.TAG_MAKE, "TestCam")
            setAttribute(ExifInterface.TAG_MODEL, "Unit")
            setAttribute(ExifInterface.TAG_BODY_SERIAL_NUMBER, "SERIAL")
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }
        return file.readBytes()
    }

    private fun bitmapBytes(format: Bitmap.CompressFormat, width: Int, height: Int): ByteArray {
        val output = ByteArrayOutputStream()
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(32, 96, 160))
            setHasAlpha(false)
            check(compress(format, 90, output))
            recycle()
        }
        return output.toByteArray()
    }

    private fun exifFromBytes(bytes: ByteArray, name: String): ExifInterface =
        ExifInterface(File(context.cacheDir, name).apply { writeBytes(bytes) })

    private companion object {
        private val PRIVACY_TAGS = listOf(
            ExifInterface.TAG_GPS_LATITUDE,
            ExifInterface.TAG_GPS_LONGITUDE,
            ExifInterface.TAG_GPS_ALTITUDE,
            ExifInterface.TAG_GPS_TIMESTAMP,
            ExifInterface.TAG_GPS_DATESTAMP,
            ExifInterface.TAG_GPS_PROCESSING_METHOD,
            ExifInterface.TAG_MAKE,
            ExifInterface.TAG_MODEL,
            ExifInterface.TAG_BODY_SERIAL_NUMBER,
        )
    }
}
