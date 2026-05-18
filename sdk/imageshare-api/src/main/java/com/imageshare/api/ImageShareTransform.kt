package com.imageshare.api

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.annotation.WorkerThread
import com.imageshare.api.internal.UriBuilder
import java.io.FileNotFoundException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

public object ImageShareTransform {
    public const val IMAGESHARE_PACKAGE: String = "com.imageshare.app"
    public const val TRANSFORM_AUTHORITY: String = "com.imageshare.app.transform"

    public fun buildUri(request: TransformRequest): Uri = UriBuilder.build(request)

    /**
     * Reads transformed bytes from ImageShare.
     *
     * This method blocks while the provider reads and encodes the image. Call it from a worker
     * thread, or use [transformAsync] to dispatch work to [Dispatchers.IO].
     *
     * Provider [FileNotFoundException] errors are mapped to [TransformException]. Permission
     * failures such as [SecurityException] propagate unchanged.
     */
    @WorkerThread
    public fun transform(context: Context, request: TransformRequest): ByteArray {
        val transformUri = buildUri(request)
        context.grantUriPermission(
            IMAGESHARE_PACKAGE,
            request.source,
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
        return try {
            val stream = context.contentResolver.openInputStream(transformUri)
                ?: throw TransformException(
                    TransformResult.ErrorCode.ProcessingFailed,
                    "Transform provider returned no data",
                )
            stream.use { it.readBytes() }
        } catch (error: FileNotFoundException) {
            throw mapFileNotFoundToException(error)
        }
    }

    public suspend fun transformAsync(context: Context, request: TransformRequest): ByteArray =
        withContext(Dispatchers.IO) {
            transform(context, request)
        }

    /**
     * Returns a [TransformResult] for mapped transform failures.
     *
     * This catches [TransformException] only; permission failures such as [SecurityException]
     * propagate unchanged.
     */
    @WorkerThread
    public fun transformResult(context: Context, request: TransformRequest): TransformResult = try {
        val bytes = transform(context, request)
        TransformResult.Success(
            bytes = bytes,
            mimeType = request.format.mimeType,
            sizeBytes = bytes.size.toLong(),
        )
    } catch (error: TransformException) {
        TransformResult.Error(error.code, error.message.orEmpty())
    }

    public fun isAvailable(context: Context): Boolean {
        val providerInfo = context.packageManager.resolveContentProvider(TRANSFORM_AUTHORITY, 0)
        return providerInfo?.packageName == IMAGESHARE_PACKAGE
    }

    /**
     * Parses ImageShare provider error messages in the form `"ImageShareTransform: CODE: message"`.
     * Non-prefixed or malformed messages map to [TransformResult.ErrorCode.ProcessingFailed];
     * unrecognized codes map to [TransformResult.ErrorCode.Unknown].
     */
    internal fun mapFileNotFoundToException(error: FileNotFoundException): TransformException {
        val rawMessage = error.message.orEmpty()
        if (!rawMessage.startsWith(ERROR_PREFIX)) {
            return TransformException(
                TransformResult.ErrorCode.ProcessingFailed,
                rawMessage.ifBlank { "Transform failed" },
                error,
            )
        }

        val detail = rawMessage.removePrefix(ERROR_PREFIX).trimStart()
        val codeToken = detail.substringBefore(':', missingDelimiterValue = detail).trim()
        val message = detail.substringAfter(':', missingDelimiterValue = "").trim()
            .ifBlank { rawMessage }
        return TransformException(codeToken.toErrorCode(), message, error)
    }

    private fun String.toErrorCode(): TransformResult.ErrorCode = when (this) {
        "GRANT_LOST" -> TransformResult.ErrorCode.GrantLost
        "RATE_LIMIT" -> TransformResult.ErrorCode.RateLimit
        "PROCESSING_FAILED" -> TransformResult.ErrorCode.ProcessingFailed
        "MALFORMED_URI" -> TransformResult.ErrorCode.MalformedUri
        "UNSUPPORTED_VERSION" -> TransformResult.ErrorCode.UnsupportedVersion
        "MISSING_SOURCE" -> TransformResult.ErrorCode.MissingSource
        "UNSUPPORTED_FORMAT" -> TransformResult.ErrorCode.UnsupportedFormat
        "SYSTEM_BUSY" -> TransformResult.ErrorCode.SystemBusy
        "PIXEL_BUDGET_EXCEEDED" -> TransformResult.ErrorCode.PixelBudgetExceeded
        else -> TransformResult.ErrorCode.Unknown
    }

    private const val ERROR_PREFIX = "ImageShareTransform:"
}
