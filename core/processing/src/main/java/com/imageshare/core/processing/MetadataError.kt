package com.imageshare.core.processing

sealed class MetadataError : Exception() {
    data class UnsupportedFormat(val format: EncodeFormat) : MetadataError()
    data class ReadFailed(override val cause: Throwable) : MetadataError()
    data class WriteFailed(override val cause: Throwable) : MetadataError()
}
