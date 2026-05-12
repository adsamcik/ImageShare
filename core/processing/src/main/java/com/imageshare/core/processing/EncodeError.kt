package com.imageshare.core.processing

sealed class EncodeError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    data class AlphaConflict(val format: EncodeFormat) : EncodeError("Alpha is not allowed for $format")
    data class IoError(override val cause: Throwable) : EncodeError("Unable to encode image", cause)
    data class Invalid(override val message: String) : EncodeError(message)
}
