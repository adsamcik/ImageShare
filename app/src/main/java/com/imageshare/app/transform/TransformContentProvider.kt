/*
 * ImageShare
 * Copyright (C) 2024-2026 adsamcik
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.imageshare.app.transform

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.Binder
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.annotation.VisibleForTesting
import com.imageshare.app.BuildConfig
import com.imageshare.core.processing.AlphaPolicy
import com.imageshare.core.processing.AvifAvailability
import com.imageshare.core.processing.DecodeError
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
import kotlin.math.ceil
import kotlin.math.max
import kotlinx.coroutines.runBlocking

class TransformContentProvider : ContentProvider() {
    private lateinit var cache: TransformCache

    override fun onCreate(): Boolean {
        if (!isTransformApiEnabled()) {
            return false
        }
        val cacheDir = context?.cacheDir ?: return false
        val tempDir = File(cacheDir, TEMP_DIR_NAME)
        tempDir.mkdirs()
        sweepTempDir(tempDir)
        cache = TransformCache(File(cacheDir, CACHE_DIR_NAME))
        cache.sweepExpired()
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        if (!isTransformApiEnabled()) return null
        val parsed = TransformUriParser.parse(uri)
        val params = parsed.getOrElse { error ->
            if (error is UnsupportedOperationException) return null
            return errorCursor(error.toTransformError())
        }
        val file = try {
            val pid = Binder.getCallingPid()
            val uid = Binder.getCallingUid()
            RATE_LIMITER.acquire(uid).use {
                transformToFile(uri, params, pid, uid)
            }
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
        if (!isTransformApiEnabled()) return null
        val uid = Binder.getCallingUid()
        return try {
            RATE_LIMITER.recordRequest(uid)
            val params = TransformUriParser.parse(uri).getOrNull() ?: return null
            params.mimeType
        } catch (error: TransformError) {
            null
        } catch (error: Exception) {
            null
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor = try {
        if (!isTransformApiEnabled()) {
            throw TransformError.ProcessingFailed("Transform API is disabled")
        }
        if (mode != "r") {
            throw TransformError.MalformedUri("Only read mode is supported")
        }
        val params = TransformUriParser.parse(uri).getOrElse { error -> throw error.toTransformError() }
        val pid = Binder.getCallingPid()
        val uid = Binder.getCallingUid()
        val file = RATE_LIMITER.acquire(uid).use {
            transformToFile(uri, params, pid, uid)
        }
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

    override fun onTrimMemory(level: Int) {
        RATE_LIMITER.trim()
        super.onTrimMemory(level)
    }

    private fun transformToFile(uri: Uri, params: TransformParams, callerPid: Int, callerUid: Int): File {
        val ctx = context ?: throw TransformError.ProcessingFailed("Provider context is unavailable")
        val resolver = ctx.contentResolver
        if (!callerCanReadSource(ctx, params.source, callerPid, callerUid)) {
            throw TransformError.GrantLost("caller does not hold read grant on source")
        }
        val signature = params.canonicalSignature()
        val key = cache.key(callerUid, params.source.toString(), signature)
        cache.lookup(key, params.extension)?.let { return it }
        validateFormatAvailable(params)
        val tempDir = File(ctx.cacheDir, TEMP_DIR_NAME).apply { mkdirs() }
        try {
            resolver.openInputStream(params.source)?.close()
        } catch (error: SecurityException) {
            throw TransformError.GrantLost("Read grant for source URI is missing or revoked", error)
        } catch (error: FileNotFoundException) {
            throw TransformError.GrantLost("Source URI is unreadable or grant was revoked", error)
        }
        val outputBytes = try {
            val decoder = Decoder(resolver)
            val metadata = decoder.readMetadata(params.source)
            enforcePixelBudget(metadata.width, metadata.height)
            runBlocking {
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
        } catch (error: TransformError) {
            throw error
        } catch (error: Exception) {
            val cause = (error as? DecodeError.IoError)?.cause
            when {
                error is SecurityException -> throw TransformError.GrantLost(
                    "Read grant for source URI is missing or revoked",
                    error,
                )
                error is FileNotFoundException -> throw TransformError.GrantLost(
                    "Source URI is unreadable or grant was revoked",
                    error,
                )
                cause is SecurityException -> throw TransformError.GrantLost(
                    "Read grant for source URI is missing or revoked",
                    cause,
                )
                cause is FileNotFoundException -> throw TransformError.GrantLost(
                    "Source URI is unreadable or grant was revoked",
                    cause,
                )
                else -> throw TransformError.ProcessingFailed(error.message ?: "Transform failed", error)
            }
        }
        val output = File(tempDir, "${System.nanoTime()}.${params.extension}")
        output.writeBytes(outputBytes)
        return cache.put(
            key = key,
            ext = params.extension,
            tempPayload = output,
            sourceUri = params.source.toString(),
            callerUid = callerUid,
            paramsSignature = signature,
            mime = params.mimeType,
        ) ?: output
    }

    private fun enforcePixelBudget(width: Int, height: Int) {
        val pixels = width.toLong() * height.toLong()
        if (pixels > BuildConfig.TRANSFORM_MAX_PIXELS) {
            throw TransformError.PixelBudgetExceeded
        }
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

    private fun callerCanReadSource(ctx: android.content.Context, source: Uri, callerPid: Int, callerUid: Int): Boolean {
        if (ctx.checkUriPermission(
                source,
                callerPid,
                callerUid,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            return true
        }
        val authority = source.authority ?: return false
        return ctx.packageManager.resolveContentProvider(authority, 0)?.applicationInfo?.uid == callerUid
    }

    private fun displayNameFor(params: TransformParams): String = "imageshare-transform.${params.extension}"

    private fun targetQualityStart(quality: Int?): Int = (quality ?: DEFAULT_AUTO_QUALITY).coerceIn(
        TargetSizeEncoder.Config.DEFAULT_QUALITY_MIN,
        TargetSizeEncoder.Config.DEFAULT_QUALITY_MAX,
    )

    private fun isTransformApiEnabled(): Boolean =
        transformApiEnabledOverrideForTests ?: BuildConfig.TRANSFORM_API_ENABLED

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

    companion object {
        @VisibleForTesting
        internal var transformApiEnabledOverrideForTests: Boolean? = null

        private val RATE_LIMITER = TransformRateLimiter(
            perUidPerMinute = BuildConfig.TRANSFORM_RATE_LIMIT_PER_UID_PER_MINUTE,
            maxConcurrentPerUid = BuildConfig.TRANSFORM_MAX_CONCURRENT_PER_UID,
            maxConcurrentProcessWide = BuildConfig.TRANSFORM_MAX_CONCURRENT_PROCESS_WIDE,
        )

        internal fun resetRateLimiterForTests() {
            transformApiEnabledOverrideForTests = null
            RATE_LIMITER.resetForTests()
        }

        private const val TEMP_DIR_NAME = "transform-tmp"
        private const val CACHE_DIR_NAME = "transform-cache"
        private const val TEMP_MAX_AGE_MS = 24L * 60L * 60L * 1000L
        private const val DEFAULT_AUTO_QUALITY = 80
        private const val OPAQUE_WHITE = -0x1
        private const val PERCENT_DIVISOR = 100.0
        private const val ERROR_CODE = "error_code"
        private const val ERROR_MESSAGE = "message"
    }
}
