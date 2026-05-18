package com.imageshare.api

public sealed class TransformResult {
    /**
     * Successful transform bytes and metadata.
     *
     * Equality and hash code compare [bytes] by content. The generated [copy] function remains
     * a shallow data-class copy and does not clone [bytes].
     */
    public data class Success(
        public val bytes: ByteArray,
        public val mimeType: String,
        public val sizeBytes: Long,
    ) : TransformResult() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Success) return false
            return bytes.contentEquals(other.bytes) &&
                mimeType == other.mimeType &&
                sizeBytes == other.sizeBytes
        }

        override fun hashCode(): Int {
            var result = bytes.contentHashCode()
            result = 31 * result + mimeType.hashCode()
            result = 31 * result + sizeBytes.hashCode()
            return result
        }
    }

    public data class Error(
        public val code: ErrorCode,
        public val message: String,
    ) : TransformResult()

    public enum class ErrorCode {
        GrantLost,
        RateLimit,
        ProcessingFailed,
        MalformedUri,
        UnsupportedVersion,
        MissingSource,
        UnsupportedFormat,
        SystemBusy,
        PixelBudgetExceeded,
        Unknown,
    }
}
