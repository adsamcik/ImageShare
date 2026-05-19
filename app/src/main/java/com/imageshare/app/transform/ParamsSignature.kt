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

/**
 * Deterministic canonical signature for cache keying.
 * Sensitive to anything that affects output bytes.
 */
internal fun TransformParams.canonicalSignature(): String {
    val sb = StringBuilder()
    sb.append("format=").append(formatToken)
    sb.append("|quality=").append(quality?.toString() ?: "qauto")
    if (targetBytes != null) sb.append("|targetBytes=").append(targetBytes)
    sb.append("|resize=").append(resize.canonicalToken())
    sb.append("|aspectLock=").append(aspectLock)
    sb.append("|metadata=").append(metadata.name)
    return sb.toString()
}

private fun TransformParams.Resize.canonicalToken(): String = when (this) {
    TransformParams.Resize.Original -> "original"
    is TransformParams.Resize.LongEdge -> "longEdge$pixels"
    is TransformParams.Resize.Exact -> "exact${width}x$height"
    is TransformParams.Resize.Percent -> "percent$percent"
}
