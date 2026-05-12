package com.imageshare.core.processing

data class EncodeResult(
    val bytes: ByteArray,
    val width: Int,
    val height: Int,
    val format: EncodeFormat,
    val quality: Int,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EncodeResult) return false

        return bytes.contentEquals(other.bytes) &&
            width == other.width &&
            height == other.height &&
            format == other.format &&
            quality == other.quality
    }

    override fun hashCode(): Int {
        var result = bytes.contentHashCode()
        result = HASH_MULTIPLIER * result + width
        result = HASH_MULTIPLIER * result + height
        result = HASH_MULTIPLIER * result + format.hashCode()
        result = HASH_MULTIPLIER * result + quality
        return result
    }

    private companion object {
        private const val HASH_MULTIPLIER = 31
    }
}
