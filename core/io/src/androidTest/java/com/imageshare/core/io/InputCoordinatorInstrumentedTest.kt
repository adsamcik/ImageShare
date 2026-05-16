package com.imageshare.core.io

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class InputCoordinatorInstrumentedTest {
    /**
     * Regression test for the production bug where InputCoordinator.readDimensions
     * conflated "openInputStream returned null" with "decodeStream returned null Bitmap".
     * `BitmapFactory.decodeStream(..., inJustDecodeBounds = true)` always returns null
     * (it only mutates options.outWidth/outHeight). The pre-fix code used
     * `openInputStream(uri)?.use { decodeStream(...) } ?: return null`, where the Elvis
     * matched on the use-block's null Bitmap return and caused every picked image to
     * silently lose its dimensions. This test runs against real platform BitmapFactory
     * (not Robolectric) which faithfully reproduces the bounds-decode behavior.
     */
    @Test
    fun resolveGeneratedJpegContentUriPopulatesFields() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val uri = Uri.parse("content://com.imageshare.core.io.test.generated/image.jpg")

        val item = InputCoordinator(context.contentResolver).resolve(uri)

        assertEquals(uri, item.uri)
        assertEquals("image/jpeg", item.mimeType)
        assertEquals("generated-input.jpg", item.displayName)
        assertEquals(GeneratedImageProvider.imageSizeBytes, item.sizeBytes)
        assertEquals(GeneratedImageProvider.IMAGE_WIDTH, item.width)
        assertEquals(GeneratedImageProvider.IMAGE_HEIGHT, item.height)
    }

    /**
     * Regression test for the EXIF orientation dimensions bug discovered via end-to-end
     * smoke test on emulator. A JPEG with EXIF orientation = ROTATE_90 has file pixels in
     * landscape orientation but renders (via thumbnail loaders + ImageDecoder) in portrait.
     * Before the fix, `readDimensions` returned the file (pre-EXIF) dimensions, so the UI
     * showed "1,600x1,200" while the visible thumbnail was rotated to portrait — and after
     * processing through Decoder, the output was "1,200x1,600" (post-EXIF), creating the
     * appearance that the app silently rotated the image during processing.
     *
     * After the fix, readDimensions reads the EXIF orientation tag and swaps width/height
     * when orientation indicates a 90/270/transpose/transverse rotation. The before/after
     * card now consistently shows portrait dimensions for both source and output.
     */
    @Test
    fun resolveSwapsAxesForExifRotate90Jpeg(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "rot90-fixture.jpg")
        writeRot90Jpeg(file, fileWidth = 1600, fileHeight = 1200)
        val resolver = context.contentResolver
        val uri = Uri.fromFile(file)

        val item = InputCoordinator(resolver).resolve(uri)

        // Logical dimensions: rot90 of 1600x1200 → 1200x1600 (portrait).
        assertEquals("EXIF ROT90 should swap to logical portrait (file 1600x1200 → logical 1200x1600)", 1200, item.width)
        assertEquals(1600, item.height)

        file.delete()
    }

    private fun writeRot90Jpeg(file: File, fileWidth: Int, fileHeight: Int) {
        val bitmap = Bitmap.createBitmap(fileWidth, fileHeight, Bitmap.Config.ARGB_8888)
        try {
            file.outputStream().use { output ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)
            }
        } finally {
            bitmap.recycle()
        }
        ExifInterface(file.absolutePath).apply {
            setAttribute(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_ROTATE_90.toString(),
            )
            saveAttributes()
        }
        // Sanity-check that EXIF was actually persisted and that file pixels stay at the original orientation.
        val readBack = ExifInterface(file.absolutePath).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        )
        check(readBack == ExifInterface.ORIENTATION_ROTATE_90) { "EXIF not persisted, got $readBack" }
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opts)
        check(opts.outWidth == fileWidth && opts.outHeight == fileHeight) {
            "file pixels rotated unexpectedly: ${opts.outWidth}x${opts.outHeight} (expected ${fileWidth}x${fileHeight})"
        }
    }
}
