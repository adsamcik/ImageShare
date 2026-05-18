package com.imageshare.app.transform

sealed class TransformError(
    val code: String,
    override val message: String,
    override val cause: Throwable? = null,
) : Exception(message, cause) {
    data class MalformedUri(override val message: String) : TransformError("MALFORMED_URI", message)
    data class UnsupportedVersion(override val message: String) : TransformError("UNSUPPORTED_VERSION", message)
    data class MissingSource(override val message: String) : TransformError("MISSING_SOURCE", message)
    data class GrantLost(override val message: String, override val cause: Throwable? = null) :
        TransformError("GRANT_LOST", message, cause)
    data class UnsupportedFormat(override val message: String) : TransformError("UNSUPPORTED_FORMAT", message)
    data class ProcessingFailed(override val message: String, override val cause: Throwable? = null) :
        TransformError("PROCESSING_FAILED", message, cause)

    fun fileNotFoundMessage(): String = "$PREFIX $code: $message"

    companion object {
        const val PREFIX = "ImageShareTransform:"
    }
}
