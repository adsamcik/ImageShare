package com.imageshare.app.data

import com.imageshare.core.processing.EncodeError
import com.imageshare.core.processing.EncodeFormat

/** Stable, non-sensitive encodings for recoverable manifest failure details. */
object BatchManifestFailureCode {
    private const val ALPHA_CONFLICT_PREFIX = "alpha-conflict/v1/"

    fun alphaConflict(format: EncodeFormat): String = "$ALPHA_CONFLICT_PREFIX${format.name}"

    fun parseAlphaConflict(errorCode: String?): EncodeError.AlphaConflict? {
        val formatName = errorCode?.takeIf { it.startsWith(ALPHA_CONFLICT_PREFIX) }
            ?.removePrefix(ALPHA_CONFLICT_PREFIX)
            ?: return null
        val format = runCatching { EncodeFormat.valueOf(formatName) }.getOrNull() ?: return null
        return EncodeError.AlphaConflict(format)
    }
}