package com.imageshare.core.processing

import android.graphics.Bitmap
import android.graphics.Color
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.CRC32
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MetadataApplierTest {
    private val applier = MetadataApplier()

    @Test
    fun stripAllRemovesExifFromJpeg() = runBlocking {
        val input = jpegWithExif()

        val result = applier.apply(input, EncodeFormat.JPEG, MetadataMode.StripAll)
        val exif = exifFromBytes(result, "strip-all.jpg")

        assertNull(exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE))
        assertNull(exif.getAttribute(ExifInterface.TAG_DATETIME))
        assertNull(exif.getAttribute(ExifInterface.TAG_MAKE))
    }

    @Test
    fun preserveSafeKeepsOnlyWhitelistAndNormalOrientation() = runBlocking {
        val input = jpegWithExif()

        val result = applier.apply(
            encoded = input,
            format = EncodeFormat.JPEG,
            mode = MetadataMode.PreserveSafe,
            source = MetadataSource(originalBytes = input, originalUri = null),
        )
        val exif = exifFromBytes(result, "preserve-safe.jpg")

        assertEquals(DATETIME, exif.getAttribute(ExifInterface.TAG_DATETIME))
        assertNull(exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE))
        assertNull(exif.getAttribute(ExifInterface.TAG_MAKE))
        assertEquals("1", exif.getAttribute(ExifInterface.TAG_ORIENTATION))
    }

    @Test
    fun preserveAllCopiesSourceTagsAndNormalOrientation() = runBlocking {
        val input = jpegWithExif()

        val result = applier.apply(
            encoded = input,
            format = EncodeFormat.JPEG,
            mode = MetadataMode.PreserveAll,
            source = MetadataSource(originalBytes = input, originalUri = null),
        )
        val exif = exifFromBytes(result, "preserve-all.jpg")

        assertEquals(DATETIME, exif.getAttribute(ExifInterface.TAG_DATETIME))
        assertTrue(exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE).isNullOrBlank().not())
        assertEquals("TestCam", exif.getAttribute(ExifInterface.TAG_MAKE))
        assertEquals("1", exif.getAttribute(ExifInterface.TAG_ORIENTATION))
    }

    @Test
    fun preserveSafeWithoutSourceBehavesLikeStripAll() = runBlocking {
        val input = jpegWithExif()

        val result = applier.apply(
            encoded = input,
            format = EncodeFormat.JPEG,
            mode = MetadataMode.PreserveSafe,
            source = MetadataSource.NONE,
        )
        val exif = exifFromBytes(result, "preserve-safe-no-source.jpg")

        assertNull(exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE))
        assertNull(exif.getAttribute(ExifInterface.TAG_DATETIME))
        assertNull(exif.getAttribute(ExifInterface.TAG_MAKE))
    }

    @Test
    fun pngPreserveModesPassThroughUnchanged() = runBlocking {
        val input = bitmapBytes(Bitmap.CompressFormat.PNG)

        listOf(MetadataMode.PreserveSafe, MetadataMode.PreserveAll).forEach { mode ->
            val result = applier.apply(input, EncodeFormat.PNG, mode)
            assertTrue("mode=$mode", result.contentEquals(input))
        }
    }

    @Test
    fun pngStripAllRemovesTextAndExifChunks() = runBlocking {
        val input = bitmapBytes(Bitmap.CompressFormat.PNG)
            .injectPngChunk("tEXt", "GPS\u000051.5N 0.1W".encodeToByteArray())
            .injectPngChunk("eXIf", "Exif\u0000\u0000sensitive".encodeToByteArray())

        val result = applier.apply(input, EncodeFormat.PNG, MetadataMode.StripAll, MetadataSource.NONE)
        val chunks = result.pngChunkTypes()

        assertFalse(chunks.contains("tEXt"))
        assertFalse(chunks.contains("eXIf"))
        assertTrue(chunks.contains("IHDR"))
        assertTrue(chunks.contains("IDAT"))
        assertTrue(chunks.contains("IEND"))
    }

    @Ignore("Robolectric does not consistently support WebP EXIF writing; covered by instrumented test.")
    @Test
    fun webpLossyStripAllRemovesExif() = runBlocking {
        val input = imageWithExif("webp-unit.webp", Bitmap.CompressFormat.WEBP_LOSSY)

        val result = applier.apply(input, EncodeFormat.WEBP_LOSSY, MetadataMode.StripAll)
        val exif = exifFromBytes(result, "webp-strip.webp")

        assertNull(exif.getAttribute(ExifInterface.TAG_DATETIME))
        assertNull(exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE))
    }

    @Test
    fun stripAllRemovesAllGpsFields() = runBlocking {
        val input = jpegWithExif(includeFullGps = true)

        val result = applier.apply(input, EncodeFormat.JPEG, MetadataMode.StripAll)
        val exif = exifFromBytes(result, "gps-scrub.jpg")

        GPS_TAGS.forEach { tag ->
            assertNull("Expected $tag to be stripped", exif.getAttribute(tag))
        }
    }

    private fun jpegWithExif(includeFullGps: Boolean = false): ByteArray =
        imageWithExif("source-${System.nanoTime()}.jpg", Bitmap.CompressFormat.JPEG, includeFullGps)

    private fun imageWithExif(
        name: String,
        format: Bitmap.CompressFormat,
        includeFullGps: Boolean = false,
    ): ByteArray {
        val file = testFile(name)
        file.writeBytes(bitmapBytes(format))
        ExifInterface(file).apply {
            setAttribute(ExifInterface.TAG_GPS_LATITUDE, GPS_LATITUDE)
            setAttribute(ExifInterface.TAG_GPS_LONGITUDE, GPS_LONGITUDE)
            setAttribute(ExifInterface.TAG_DATETIME, DATETIME)
            setAttribute(ExifInterface.TAG_MAKE, "TestCam")
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            if (includeFullGps) {
                setAttribute(ExifInterface.TAG_GPS_ALTITUDE, "123/1")
                setAttribute(ExifInterface.TAG_GPS_TIMESTAMP, "12:34:56")
                setAttribute(ExifInterface.TAG_GPS_DATESTAMP, "2024:01:01")
                setAttribute(ExifInterface.TAG_GPS_PROCESSING_METHOD, "GPS")
            }
            saveAttributes()
        }
        return file.readBytes()
    }

    private fun bitmapBytes(format: Bitmap.CompressFormat): ByteArray {
        val output = ByteArrayOutputStream()
        Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(32, 96, 160))
            setHasAlpha(false)
            check(compress(format, 90, output))
            recycle()
        }
        return output.toByteArray()
    }

    private fun exifFromBytes(bytes: ByteArray, name: String): ExifInterface =
        ExifInterface(testFile(name).apply { writeBytes(bytes) })

    private fun testFile(name: String): File =
        File(TEST_DIR, name).apply {
            parentFile?.mkdirs()
            delete()
        }

    private fun ByteArray.injectPngChunk(chunkType: String, chunkData: ByteArray): ByteArray {
        val iendOffset = chunkOffset("IEND")
        val output = ByteArrayOutputStream(size + PNG_CHUNK_OVERHEAD + chunkData.size)
        output.write(this, 0, iendOffset)
        output.writePngInt(chunkData.size)
        val typeBytes = chunkType.encodeToByteArray()
        output.write(typeBytes)
        output.write(chunkData)
        output.writePngInt(crc(typeBytes, chunkData))
        output.write(this, iendOffset, size - iendOffset)
        return output.toByteArray()
    }

    private fun ByteArray.pngChunkTypes(): List<String> {
        val chunks = mutableListOf<String>()
        var offset = PNG_SIGNATURE_SIZE
        while (offset + PNG_CHUNK_OVERHEAD <= size) {
            val length = readPngInt(offset)
            val typeStart = offset + PNG_LENGTH_SIZE
            val dataStart = typeStart + PNG_TYPE_SIZE
            val chunkEnd = dataStart + length + PNG_CRC_SIZE
            if (length < 0 || chunkEnd > size) break
            chunks += decodeToString(typeStart, dataStart)
            offset = chunkEnd
        }
        return chunks
    }

    private fun ByteArray.chunkOffset(chunkType: String): Int {
        var offset = PNG_SIGNATURE_SIZE
        while (offset + PNG_CHUNK_OVERHEAD <= size) {
            val length = readPngInt(offset)
            val typeStart = offset + PNG_LENGTH_SIZE
            val dataStart = typeStart + PNG_TYPE_SIZE
            if (decodeToString(typeStart, dataStart) == chunkType) return offset
            offset = dataStart + length + PNG_CRC_SIZE
        }
        error("PNG chunk $chunkType not found")
    }

    private fun ByteArray.readPngInt(offset: Int): Int =
        ((this[offset].toInt() and BYTE_MASK) shl PNG_BYTE_3_SHIFT) or
            ((this[offset + 1].toInt() and BYTE_MASK) shl PNG_BYTE_2_SHIFT) or
            ((this[offset + 2].toInt() and BYTE_MASK) shl PNG_BYTE_1_SHIFT) or
            (this[offset + 3].toInt() and BYTE_MASK)

    private fun ByteArrayOutputStream.writePngInt(value: Int) {
        write((value ushr PNG_BYTE_3_SHIFT) and BYTE_MASK)
        write((value ushr PNG_BYTE_2_SHIFT) and BYTE_MASK)
        write((value ushr PNG_BYTE_1_SHIFT) and BYTE_MASK)
        write(value and BYTE_MASK)
    }

    private fun crc(type: ByteArray, data: ByteArray): Int {
        val crc = CRC32()
        crc.update(type)
        crc.update(data)
        return crc.value.toInt()
    }

    private companion object {
        private const val DATETIME = "2024:01:01 12:00:00"
        private const val GPS_LATITUDE = "12/1,20/1,42000/1000"
        private const val GPS_LONGITUDE = "67/1,53/1,24000/1000"
        private val TEST_DIR = File("core/processing/build/test-metadata")
        private const val BYTE_MASK = 0xff
        private const val PNG_SIGNATURE_SIZE = 8
        private const val PNG_LENGTH_SIZE = 4
        private const val PNG_TYPE_SIZE = 4
        private const val PNG_CRC_SIZE = 4
        private const val PNG_CHUNK_OVERHEAD = PNG_LENGTH_SIZE + PNG_TYPE_SIZE + PNG_CRC_SIZE
        private const val PNG_BYTE_3_SHIFT = 24
        private const val PNG_BYTE_2_SHIFT = 16
        private const val PNG_BYTE_1_SHIFT = 8
        private val GPS_TAGS = listOf(
            ExifInterface.TAG_GPS_LATITUDE,
            ExifInterface.TAG_GPS_LONGITUDE,
            ExifInterface.TAG_GPS_ALTITUDE,
            ExifInterface.TAG_GPS_TIMESTAMP,
            ExifInterface.TAG_GPS_DATESTAMP,
            ExifInterface.TAG_GPS_PROCESSING_METHOD,
        )
    }
}
