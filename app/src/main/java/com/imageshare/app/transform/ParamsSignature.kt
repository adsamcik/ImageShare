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
