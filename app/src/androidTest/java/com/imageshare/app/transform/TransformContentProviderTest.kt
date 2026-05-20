package com.imageshare.app.transform

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import com.imageshare.app.BuildConfig
import com.imageshare.core.processing.AvifAvailability
import com.imageshare.core.processing.HeifAvailability
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TransformContentProviderTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val resolver = context.contentResolver

    @Before fun resetRateLimiter() {
        TransformContentProvider.resetRateLimiterForTests()
        RevokingSourceProvider.reset()
        grantTestSourceRead(Uri.parse("content://com.imageshare.app.testsource/image/1"))
    }

    @Test fun jpegTransformHasJpegMagic() {
        val bytes = openBytes(transformUri())
        assertTrue(bytes.size > 2)
        assertEquals(0xFF, bytes[0].toInt() and 0xFF)
        assertEquals(0xD8, bytes[1].toInt() and 0xFF)
    }

    @Test fun malformedUriThrowsStablePrefix() {
        val uri = Uri.parse("content://${context.packageName}.transform/v1/jpeg/q85/original")
        val error = assertFileNotFound(uri)
        assertTrue(error.message.orEmpty().startsWith("ImageShareTransform: MALFORMED_URI:"))
    }

    @Test fun selfReferenceSourceThrowsMalformed() {
        val self = transformUri(source = Uri.parse("content://${context.packageName}.transform/v1/jpeg/q85/original/stripall"))
        val error = assertFileNotFound(self)
        assertTrue(error.message.orEmpty().startsWith("ImageShareTransform: MALFORMED_URI:"))
    }

    @Test fun unsupportedVersionThrowsStablePrefix() {
        val uri = transformUri(version = "v2")
        val error = assertFileNotFound(uri)
        assertTrue(error.message.orEmpty().startsWith("ImageShareTransform: UNSUPPORTED_VERSION:"))
    }

    @Test fun qautoWithoutTargetBytesThrowsMalformed() {
        val uri = transformUri(quality = "qauto")
        val error = assertFileNotFound(uri)
        assertTrue(error.message.orEmpty().startsWith("ImageShareTransform: MALFORMED_URI:"))
    }

    @Test fun qautoWithTargetBytesProducesJpegMagic() {
        val bytes = openBytes(transformUri(quality = "qauto", targetBytes = 2048))
        assertTrue(bytes.size > 2)
        assertEquals(0xFF, bytes[0].toInt() and 0xFF)
        assertEquals(0xD8, bytes[1].toInt() and 0xFF)
    }

    @Test fun unreadableSourceThrowsGrantLost() {
        val uri = transformUri(source = Uri.parse("content://missing.source.provider/image/1"))
        val error = assertFileNotFound(uri)
        assertTrue(error.message.orEmpty().startsWith("ImageShareTransform: GRANT_LOST:"))
    }

    @Test fun callerWithoutGrantOnSourceThrowsGrantLost() {
        val unreachable = Uri.parse("content://com.example.no-such-authority/image.jpg")
        val transformUri = Uri.parse(
            "content://${BuildConfig.APPLICATION_ID}.transform/v1/jpeg/q80/original/stripall?source=" +
                Uri.encode(unreachable.toString()),
        )
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        val ex = assertThrows(FileNotFoundException::class.java) {
            resolver.openFileDescriptor(transformUri, "r")
        }
        val msg = ex.message ?: ""
        assertTrue(
            "expected GRANT_LOST: $msg",
            msg.contains("GRANT_LOST"),
        )
        assertTrue(
            "expected the new checkUriPermission branch to fire, got: $msg",
            msg.contains("caller does not hold read grant on source"),
        )
    }

    @Test fun revokedGrantMidPipelineThrowsGrantLost() {
        val source = Uri.parse("content://com.imageshare.app.testsource/image/1")
        val uri = transformUri(source = source)
        val error = assertFileNotFound(uri)
        assertTrue(error.message.orEmpty().contains("GRANT_LOST"))
    }

    @Test fun oversizedSourceThrowsPixelBudgetExceeded() {
        val uri = transformUri(source = hugePngUri())
        val error = assertFileNotFound(uri)
        assertTrue(error.message.orEmpty().contains("PIXEL_BUDGET_EXCEEDED"))
    }

    @Test fun perUidRateLimitRejects101stTransform() {
        val uri = transformUri()
        repeat(BuildConfig.TRANSFORM_RATE_LIMIT_PER_UID_PER_MINUTE) {
            openBytes(uri)
        }
        val error = assertFileNotFound(uri)
        assertTrue(error.message.orEmpty().contains("RATE_LIMIT"))
    }

    @Test fun getTypeReturnsNullUnderRateLimit() {
        val uri = transformUri(source = Uri.parse("content://example/image.jpg"))
        repeat(BuildConfig.TRANSFORM_RATE_LIMIT_PER_UID_PER_MINUTE) {
            resolver.getType(uri)
        }
        assertNull(resolver.getType(uri))
    }

    @Test fun pngFromJpegSourceHasPngMagicAndMimeType() {
        val uri = transformUri(format = "png")
        assertEquals("image/png", resolver.getType(uri))
        val bytes = openBytes(uri)
        assertTrue(bytes.take(PNG_MAGIC.size).toByteArray().contentEquals(PNG_MAGIC))
    }

    @Test fun fileProviderContentUriSmokePathDecodesResult() {
        val uri = transformUri(resize = "longEdge16")
        val bytes = openBytes(uri)
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        assertNotNull(decoded)
        assertEquals(16, maxOf(decoded.width, decoded.height))
        decoded.recycle()
    }

    @Test fun cacheHitReturnsSameBytesOnSecondCall() {
        val uri = transformUri(format = "jpeg", source = sourceJpegUri())

        val first = openBytes(uri)
        val second = openBytes(uri)

        assertArrayEquals(first, second)
    }

    @Test fun jpegProducesJpegMagic() {
        val bytes = openBytes(transformUri(format = "jpeg"))
        assertTrue(bytes.size >= 3)
        assertEquals(0xFF, bytes[0].toInt() and 0xFF)
        assertEquals(0xD8, bytes[1].toInt() and 0xFF)
        assertEquals(0xFF, bytes[2].toInt() and 0xFF)
    }

    @Test fun webpProducesWebpMagic() {
        val bytes = openBytes(transformUri(format = "webp"))
        assertRiffWebp(bytes)
    }

    @Test fun heifProducesHeifMagic() {
        assumeTrue("Device under test has no HEIF encoder", HeifAvailability.isWriteSupported())
        val bytes = openBytes(transformUri(format = "heif"))
        assertIsoBrand(bytes, setOf("heic", "heix", "hevc", "hevx", "mif1", "msf1"))
    }

    @Test fun avifProducesAvifMagic() {
        assumeTrue("Device under test has no AVIF encoder", AvifAvailability.isAnyWriteSupported())
        val bytes = openBytes(transformUri(format = "avif"))
        assertIsoBrand(bytes, setOf("avif", "avis", "mif1", "msf1"))
    }

    @Test fun unavailableHeifExplainsDeviceEncoderRequirement() {
        assumeTrue("Device under test has a HEIF encoder", !HeifAvailability.isWriteSupported())

        val error = assertFileNotFound(transformUri(format = "heif"))

        val message = error.message.orEmpty()
        assertTrue(message.contains("UNSUPPORTED_FORMAT"))
        assertTrue(message.contains("Android exposes no HEIF encoder"))
        assertTrue(message.contains("Use JPEG, PNG, or WebP instead"))
    }

    @Test fun unavailableAvifExplainsSoftwarePathNotBundled() {
        assumeTrue("Device under test has an AVIF encoder", !AvifAvailability.isAnyWriteSupported())

        val error = assertFileNotFound(transformUri(format = "avif"))

        val message = error.message.orEmpty()
        assertTrue(message.contains("UNSUPPORTED_FORMAT"))
        assertTrue(message.contains("Android exposes no AVIF encoder"))
        assertTrue(message.contains("software AVIF path is not bundled"))
        assertTrue(message.contains("Use JPEG, PNG, or WebP instead"))
    }

    @Test fun stripallActuallyStripsExif() {
        val bytes = openBytes(transformUri(metadata = "stripall", source = exifJpegUri()))

        withExif(bytes) { exif ->
            assertNull(exif.getAttribute(ExifInterface.TAG_DATETIME))
            assertNull(exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE))
            assertEquals(ExifInterface.ORIENTATION_UNDEFINED, exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED))
        }
    }

    @Test fun preservesafeRetainsSafeTagsAndDropsGps() {
        val bytes = openBytes(transformUri(metadata = "preservesafe", source = exifJpegUri()))

        withExif(bytes) { exif ->
            assertEquals(EXIF_DATETIME, exif.getAttribute(ExifInterface.TAG_DATETIME))
            assertNull(exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE))
            assertEquals(ExifInterface.ORIENTATION_NORMAL, exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED))
        }
    }

    @Test fun preserveallRetainsGps() {
        val bytes = openBytes(transformUri(metadata = "preserveall", source = exifJpegUri()))

        withExif(bytes) { exif ->
            assertEquals(EXIF_DATETIME, exif.getAttribute(ExifInterface.TAG_DATETIME))
            assertNotNull(exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE))
            assertEquals(ExifInterface.ORIENTATION_NORMAL, exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED))
        }
    }

    @Test fun longEdge2048ProducesLongEdge2048() {
        val decoded = decode(openBytes(transformUri(resize = "longEdge2048", source = largeJpegUri())))
        assertEquals(2048, maxOf(decoded.width, decoded.height))
        decoded.recycle()
    }

    @Test fun longEdgeUpscaleSkipped() {
        val decoded = decode(openBytes(transformUri(resize = "longEdge2048", source = sourceJpegUri())))
        assertEquals(24, decoded.width)
        assertEquals(16, decoded.height)
        decoded.recycle()
    }

    @Test fun exactDimensionsHonored() {
        val decoded = decode(openBytes(transformUri(resize = "exact13x7")))
        assertEquals(13, decoded.width)
        assertEquals(7, decoded.height)
        decoded.recycle()
    }

    @Test fun percent50ProducesHalfSize() {
        val decoded = decode(openBytes(transformUri(resize = "percent50")))
        assertEquals(12, decoded.width)
        assertEquals(8, decoded.height)
        decoded.recycle()
    }

    @Test fun aspectLockTrueAndFalseProduceDifferentPixels() {
        val source = sourceJpegUri()
        val locked = decode(openBytes(transformUri(resize = "exact10x20", source = source, aspectLock = true)))
        val stretched = decode(openBytes(transformUri(resize = "exact10x20", source = source, aspectLock = false)))
        try {
            assertEquals(10, locked.width)
            assertEquals(20, locked.height)
            assertEquals(10, stretched.width)
            assertEquals(20, stretched.height)
            assertNotEquals(
                "aspectLock=true and aspectLock=false produced identical pixels",
                pixelHash(locked),
                pixelHash(stretched),
            )
        } finally {
            locked.recycle()
            stretched.recycle()
        }
    }

    @Test fun aspectLockFalseStretchesFreelyToRequestedCanvas() {
        val decoded = decode(openBytes(transformUri(resize = "exact10x20", aspectLock = false)))
        try {
            assertEquals(10, decoded.width)
            assertEquals(20, decoded.height)
        } finally {
            decoded.recycle()
        }
    }

    @Test fun killSwitchPreventsTransformOperations() {
        assertTrue("TRANSFORM_API_ENABLED expected true in debug", BuildConfig.TRANSFORM_API_ENABLED)
        TransformContentProvider.transformApiEnabledOverrideForTests = false
        try {
            val uri = transformUri()
            assertNull(resolver.query(uri, null, null, null, null))
            assertNull(resolver.getType(uri))
            val error = assertFileNotFound(uri)
            assertTrue(error.message.orEmpty().contains("Transform API is disabled"))
        } finally {
            TransformContentProvider.transformApiEnabledOverrideForTests = null
        }
    }

    @Test fun cacheRespectsPerUidIsolationByProviderSignatureAndKeyDerivation() {
        val params = TransformUriParser.parse(
            transformUri(format = "jpeg", resize = "exact10x20", aspectLock = false),
        ).getOrThrow()
        val cacheDir = File(
            context.cacheDir,
            "test-provider-uid-isolation-${System.nanoTime()}",
        ).apply { mkdirs() }
        try {
            val cache = TransformCache(cacheDir)
            val signature = params.canonicalSignature()
            val first = cache.key(
                callerUid = 10_500,
                source = params.source.toString(),
                paramsSignature = signature,
            )
            val second = cache.key(
                callerUid = 10_501,
                source = params.source.toString(),
                paramsSignature = signature,
            )

            assertNotEquals(first, second)
        } finally {
            cacheDir.deleteRecursively()
        }
    }

    @Test fun corruptSourceProducesProcessingFailed() {
        val error = assertFileNotFound(transformUri(source = corruptSourceUri()))
        assertTrue(error.message.orEmpty().contains("PROCESSING_FAILED"))
        assertFalse(error.message.orEmpty().contains("GRANT_LOST"))
    }

    @Test fun successfulQueryReturnsDisplayNameAndSize() {
        resolver.query(transformUri(format = "png"), null, null, null, null).use { cursor ->
            assertNotNull(cursor)
            assertTrue(cursor!!.moveToFirst())
            assertEquals("imageshare-transform.png", cursor.getString(0))
            assertTrue(cursor.getLong(1) > 0L)
        }
    }

    @Test fun getTypeReturnsWebpMimeType() {
        assertEquals("image/webp", resolver.getType(transformUri(format = "webp")))
    }

    @Test fun concurrentTransformsOfSameUriReturnIdenticalBytes() {
        val uri = transformUri(format = "png", resize = "longEdge12")
        val executor = Executors.newFixedThreadPool(2)
        try {
            val results = executor.invokeAll(List(2) { Callable { openBytes(uri) } }, 30, TimeUnit.SECONDS)
                .map { it.get() }
            assertArrayEquals(results[0], results[1])
        } finally {
            executor.shutdownNow()
        }
    }

    @Test fun openFileRejectsWriteMode() {
        val error = assertThrows(FileNotFoundException::class.java) {
            resolver.openFileDescriptor(transformUri(), "w")
        }
        assertTrue(error.message.orEmpty().contains("MALFORMED_URI"))
    }

    private fun openBytes(uri: Uri): ByteArray = resolver.openInputStream(uri)?.use { it.readBytes() }
        ?: throw AssertionError("Expected transform stream for $uri")

    private fun assertFileNotFound(uri: Uri): FileNotFoundException = try {
        resolver.openInputStream(uri)?.close()
        throw AssertionError("Expected FileNotFoundException for $uri")
    } catch (error: FileNotFoundException) {
        error
    }

    private fun transformUri(
        version: String = "v1",
        format: String = "jpeg",
        quality: String = "q85",
        resize: String = "original",
        metadata: String = "stripall",
        source: Uri = sourceJpegUri(),
        targetBytes: Long? = null,
        aspectLock: Boolean? = null,
    ): Uri = Uri.Builder()
        .scheme("content")
        .authority("${context.packageName}.transform")
        .appendPath(version)
        .appendPath(format)
        .appendPath(quality)
        .appendPath(resize)
        .appendPath(metadata)
        .appendQueryParameter("source", source.toString())
        .apply { targetBytes?.let { appendQueryParameter("targetBytes", it.toString()) } }
        .apply { aspectLock?.let { appendQueryParameter("aspectLock", it.toString()) } }
        .build()

    private fun hugePngUri(): Uri {
        val file = File(sharedOutputDir(), "huge-20000x20000-valid.png")
        if (!file.isFile) {
            FileOutputStream(file).use { writeHugePng(it, 20_000, 20_000) }
        }
        return fileProviderUri(file)
    }

    private fun sourceJpegUri(): Uri {
        val file = File(sharedOutputDir(), "transform-source.jpg")
        if (!file.isFile) {
            writeGradientJpeg(file, 24, 16)
        }
        return fileProviderUri(file)
    }

    private fun largeJpegUri(): Uri {
        val file = File(sharedOutputDir(), "transform-large-3000x2000.jpg")
        if (!file.isFile) {
            writeGradientJpeg(file, 3000, 2000)
        }
        return fileProviderUri(file)
    }

    private fun exifJpegUri(): Uri {
        val file = File(sharedOutputDir(), "transform-source-exif.jpg")
        if (!file.isFile) {
            writeGradientJpeg(file, 64, 48)
            ExifInterface(file.absolutePath).apply {
                setAttribute(ExifInterface.TAG_DATETIME, EXIF_DATETIME)
                setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, EXIF_DATETIME)
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
                setLatLong(37.4219983, -122.084)
                saveAttributes()
            }
        }
        return fileProviderUri(file)
    }

    private fun corruptSourceUri(): Uri {
        val file = File(sharedOutputDir(), "transform-corrupt.bin")
        if (!file.isFile) {
            file.writeBytes(ByteArray(100) { 0xAA.toByte() })
        }
        return fileProviderUri(file)
    }

    private fun writeGradientJpeg(file: File, width: Int, height: Int) {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            for (y in 0 until bitmap.height) {
                for (x in 0 until bitmap.width) {
                    bitmap.setPixel(x, y, Color.rgb(64, x * 255 / bitmap.width, y * 255 / bitmap.height))
                }
            }
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        } finally {
            bitmap.recycle()
        }
    }

    private fun sharedOutputDir(): File = File(context.cacheDir, "shared-output").apply { mkdirs() }

    private fun fileProviderUri(file: File): Uri {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.shareprovider", file)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        listOf(context.packageName, instrumentation.context.packageName).distinct().forEach { packageName ->
            context.grantUriPermission(packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return uri
    }

    private fun writeHugePng(output: FileOutputStream, width: Int, height: Int) {
        output.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        val ihdr = ByteArrayOutputStream()
        DataOutputStream(ihdr).use { data ->
            data.writeInt(width)
            data.writeInt(height)
            data.writeByte(8)
            data.writeByte(0)
            data.writeByte(0)
            data.writeByte(0)
            data.writeByte(0)
        }
        writePngChunk(output, "IHDR", ihdr.toByteArray())
        val compressed = ByteArrayOutputStream()
        DeflaterOutputStream(compressed).use { deflater ->
            val row = ByteArray(width + 1)
            repeat(height) {
                deflater.write(row)
            }
        }
        writePngChunk(output, "IDAT", compressed.toByteArray())
        writePngChunk(output, "IEND", ByteArray(0))
    }

    private fun writePngChunk(output: FileOutputStream, type: String, data: ByteArray) {
        val chunkBytes = ByteArrayOutputStream()
        DataOutputStream(chunkBytes).use { chunk ->
            chunk.writeInt(data.size)
            val typeBytes = type.toByteArray(Charsets.US_ASCII)
            chunk.write(typeBytes)
            chunk.write(data)
            val crc = CRC32()
            crc.update(typeBytes)
            crc.update(data)
            chunk.writeInt(crc.value.toInt())
        }
        output.write(chunkBytes.toByteArray())
    }

    private fun decode(bytes: ByteArray): Bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        ?: throw AssertionError("Expected decodable image")

    private fun pixelHash(bitmap: Bitmap): Long {
        var hash = 0L
        val yStep = maxOf(1, bitmap.height / 8)
        val xStep = maxOf(1, bitmap.width / 8)
        for (y in 0 until bitmap.height step yStep) {
            for (x in 0 until bitmap.width step xStep) {
                hash = hash * 31 + bitmap.getPixel(x, y)
            }
        }
        return hash
    }

    private fun assertRiffWebp(bytes: ByteArray) {
        assertTrue(bytes.size >= 12)
        assertEquals("RIFF", bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII))
        assertEquals("WEBP", bytes.copyOfRange(8, 12).toString(Charsets.US_ASCII))
    }

    private fun assertIsoBrand(bytes: ByteArray, expectedBrands: Set<String>) {
        assertTrue(bytes.size >= 16)
        assertEquals("ftyp", bytes.copyOfRange(4, 8).toString(Charsets.US_ASCII))
        val header = bytes.copyOfRange(8, minOf(bytes.size, 64)).toString(Charsets.ISO_8859_1)
        assertTrue("Expected one of $expectedBrands in ISO BMFF header", expectedBrands.any { header.contains(it) })
    }

    private fun withExif(bytes: ByteArray, block: (ExifInterface) -> Unit) {
        val file = File(sharedOutputDir(), "transformed-exif-${System.nanoTime()}.jpg")
        try {
            file.writeBytes(bytes)
            block(ExifInterface(file.absolutePath))
        } finally {
            file.delete()
        }
    }

    private fun grantTestSourceRead(uri: Uri) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val packages = listOf(
            instrumentation.targetContext.packageName,
            instrumentation.context.packageName,
        ).distinct()
        packages.forEach { packageName ->
            instrumentation.context.grantUriPermission(
                packageName,
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
    }

    private companion object {
        val PNG_MAGIC = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        const val EXIF_DATETIME = "2024:01:02 03:04:05"
    }
}
