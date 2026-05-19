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
