package com.imageshare.app.transform

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import com.imageshare.app.BuildConfig
import com.imageshare.core.processing.AlphaPolicy
import com.imageshare.core.processing.AvifAvailability
import com.imageshare.core.processing.Decoder
import com.imageshare.core.processing.EncodeFormat
import com.imageshare.core.processing.Encoder
import com.imageshare.core.processing.HeifAvailability
import com.imageshare.core.processing.MetadataApplier
import com.imageshare.core.processing.MetadataSource
import com.imageshare.core.processing.Resizer
import com.imageshare.core.processing.TargetSizeEncoder
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil
import kotlin.math.max
import kotlinx.coroutines.runBlocking

class TransformContentProvider : ContentProvider() {
    // TODO(TXA-2): normalize transform cache keys and enforce bounded per-request cache cleanup.
    private val memoryResults = ConcurrentHashMap<String, File>()

    override fun onCreate(): Boolean {
        if (!BuildConfig.TRANSFORM_API_ENABLED) {
            return false
        }
        context?.cacheDir?.let { cacheDir ->
            val tempDir = File(cacheDir, TEMP_DIR_NAME)
            tempDir.mkdirs()
            sweepTempDir(tempDir)
        }
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        if (!BuildConfig.TRANSFORM_API_ENABLED) return null
        val parsed = TransformUriParser.parse(uri)
        val params = parsed.getOrElse { error ->
            if (error is UnsupportedOperationException) return null
            return errorCursor(error.toTransformError())
        }
        val file = try {
            transformToFile(uri, params)
        } catch (error: TransformError) {
            return errorCursor(error)
        } catch (error: Exception) {
            return errorCursor(TransformError.ProcessingFailed(error.message ?: "Unable to transform source", error))
        }
        return MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply {
            addRow(arrayOf<Any>(displayNameFor(params), file.length()))
        }
    }

    override fun getType(uri: Uri): String? {
        if (!BuildConfig.TRANSFORM_API_ENABLED) return null
        return TransformUriParser.parse(uri).getOrNull()?.mimeType
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor = try {
        if (!BuildConfig.TRANSFORM_API_ENABLED) {
            throw TransformError.ProcessingFailed("Transform API is disabled")
        }
        if (mode != "r") {
            throw TransformError.MalformedUri("Only read mode is supported")
        }
        val params = TransformUriParser.parse(uri).getOrElse { error -> throw error.toTransformError() }
        val file = transformToFile(uri, params)
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    } catch (error: FileNotFoundException) {
        if (error.message.orEmpty().startsWith(TransformError.PREFIX)) {
            throw error
        }
        throw TransformError.ProcessingFailed(error.message ?: "Unable to open transform output", error)
            .toFileNotFoundException()
    } catch (error: TransformError) {
        throw error.toFileNotFoundException()
    } catch (error: SecurityException) {
        throw TransformError.GrantLost("Read grant for source URI is missing or revoked", error)
            .toFileNotFoundException()
    } catch (error: Exception) {
        throw TransformError.ProcessingFailed(error.message ?: "Transform failed", error)
            .toFileNotFoundException()
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri = throw UnsupportedOperationException("Transform API is read-only")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Transform API is read-only")

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw UnsupportedOperationException("Transform API is read-only")

    private fun transformToFile(uri: Uri, params: TransformParams): File {
        memoryResults[uri.toString()]?.takeIf { it.isFile }?.let { return it }
        validateFormatAvailable(params)
        val ctx = context ?: throw TransformError.ProcessingFailed("Provider context is unavailable")
        val tempDir = File(ctx.cacheDir, TEMP_DIR_NAME).apply { mkdirs() }
        val resolver = ctx.contentResolver
        try {
            resolver.openInputStream(params.source)?.close()
        } catch (error: SecurityException) {
            throw TransformError.GrantLost("Read grant for source URI is missing or revoked", error)
        } catch (error: FileNotFoundException) {
            throw TransformError.GrantLost("Source URI is unreadable or grant was revoked", error)
        }
        val outputBytes = try {
            runBlocking {
                val decoder = Decoder(resolver)
                val metadata = decoder.readMetadata(params.source)
                val decodeLongEdge = decodeLongEdgeFor(params, metadata.width, metadata.height)
                val decoded = decoder.decode(params.source, decodeLongEdge)
                val bitmap = resize(decoded.bitmap, params, metadata.width, metadata.height)
                try {
                    val encoded = if (params.targetBytes != null) {
                        TargetSizeEncoder().encodeToTarget(
                            bitmap,
                            TargetSizeEncoder.Config(
                                format = params.format,
                                targetBytes = params.targetBytes,
                                qualityStart = targetQualityStart(params.quality),
                                alphaPolicy = AlphaPolicy.FillBackground(OPAQUE_WHITE),
                            ),
                        ).bytes
                    } else {
                        Encoder().encode(
                            bitmap,
                            params.format,
                            params.quality ?: DEFAULT_AUTO_QUALITY,
                            AlphaPolicy.FillBackground(OPAQUE_WHITE),
                        ).bytes
                    }
                    MetadataApplier(resolver).apply(
                        encoded = encoded,
                        format = params.format,
                        mode = params.metadata,
                        source = MetadataSource(originalBytes = null, originalUri = params.source),
                    )
                } finally {
                    if (!bitmap.isRecycled) {
                        bitmap.recycle()
                    }
                }
            }
        } catch (error: SecurityException) {
            throw TransformError.GrantLost("Read grant for source URI is missing or revoked", error)
        } catch (error: FileNotFoundException) {
            throw TransformError.GrantLost("Source URI is unreadable or grant was revoked", error)
        } catch (error: TransformError) {
            throw error
        } catch (error: Exception) {
            throw TransformError.ProcessingFailed(error.message ?: "Transform failed", error)
        }
        val output = File(tempDir, "${System.nanoTime()}.${params.extension}")
        output.writeBytes(outputBytes)
        memoryResults[uri.toString()] = output
        return output
    }

    private fun resize(bitmap: Bitmap, params: TransformParams, sourceWidth: Int, sourceHeight: Int): Bitmap =
        when (val resize = params.resize) {
            TransformParams.Resize.Original -> bitmap
            is TransformParams.Resize.LongEdge -> Resizer().toLongEdge(bitmap, resize.pixels, recycleSrc = true)
            is TransformParams.Resize.Exact -> Resizer().toExact(
                bitmap,
                resize.width,
                resize.height,
                if (params.aspectLock) Resizer.ExactMode.CenterCrop else Resizer.ExactMode.Stretch,
                recycleSrc = true,
            )
            is TransformParams.Resize.Percent -> {
                val width = ceil(sourceWidth * resize.percent / PERCENT_DIVISOR).toInt().coerceAtLeast(1)
                val height = ceil(sourceHeight * resize.percent / PERCENT_DIVISOR).toInt().coerceAtLeast(1)
                Resizer().toExact(bitmap, width, height, Resizer.ExactMode.Stretch, recycleSrc = true)
            }
        }

    private fun decodeLongEdgeFor(params: TransformParams, sourceWidth: Int, sourceHeight: Int): Int {
        val sourceLongEdge = max(sourceWidth, sourceHeight).coerceAtLeast(1)
        return when (val resize = params.resize) {
            TransformParams.Resize.Original -> sourceLongEdge
            is TransformParams.Resize.LongEdge -> resize.pixels.coerceAtMost(sourceLongEdge).coerceAtLeast(1)
            is TransformParams.Resize.Exact -> max(resize.width, resize.height).coerceAtMost(sourceLongEdge).coerceAtLeast(1)
            is TransformParams.Resize.Percent -> {
                val target = ceil(sourceLongEdge * resize.percent / PERCENT_DIVISOR).toInt().coerceAtLeast(1)
                target.coerceAtMost(sourceLongEdge)
            }
        }
    }

    private fun validateFormatAvailable(params: TransformParams) {
        when (params.format) {
            EncodeFormat.HEIF -> if (!HeifAvailability.isWriteSupported()) {
                throw TransformError.UnsupportedFormat("HEIF encoding is not available on this device")
            }
            EncodeFormat.AVIF -> if (!AvifAvailability.isAnyWriteSupported()) {
                throw TransformError.UnsupportedFormat("AVIF encoding is not available on this device")
            }
            EncodeFormat.JPEG,
            EncodeFormat.PNG,
            EncodeFormat.WEBP_LOSSY,
            EncodeFormat.WEBP_LOSSLESS,
            -> Unit
        }
    }

    private fun displayNameFor(params: TransformParams): String = "imageshare-transform.${params.extension}"

    private fun targetQualityStart(quality: Int?): Int = (quality ?: DEFAULT_AUTO_QUALITY).coerceIn(
        TargetSizeEncoder.Config.DEFAULT_QUALITY_MIN,
        TargetSizeEncoder.Config.DEFAULT_QUALITY_MAX,
    )

    private fun errorCursor(error: TransformError): Cursor = MatrixCursor(arrayOf(ERROR_CODE, ERROR_MESSAGE)).apply {
        addRow(arrayOf(error.code, error.message))
    }

    private fun Throwable.toTransformError(): TransformError = when (this) {
        is TransformError -> this
        is UnsupportedOperationException -> TransformError.MalformedUri(message ?: "Unsupported transform URI")
        else -> TransformError.ProcessingFailed(message ?: "Transform failed", this)
    }

    private fun Throwable.toFileNotFoundException(): FileNotFoundException {
        val transformError = toTransformError()
        return FileNotFoundException(transformError.fileNotFoundMessage())
    }

    private fun sweepTempDir(tempDir: File) {
        val cutoff = System.currentTimeMillis() - TEMP_MAX_AGE_MS
        tempDir.listFiles()?.forEach { file ->
            if (file.isFile && file.lastModified() < cutoff) {
                file.delete()
            }
        }
    }

    private companion object {
        private const val TEMP_DIR_NAME = "transform-tmp"
        private const val TEMP_MAX_AGE_MS = 24L * 60L * 60L * 1000L
        private const val DEFAULT_AUTO_QUALITY = 80
        private const val OPAQUE_WHITE = -0x1
        private const val PERCENT_DIVISOR = 100.0
        private const val ERROR_CODE = "error_code"
        private const val ERROR_MESSAGE = "message"
    }
}
