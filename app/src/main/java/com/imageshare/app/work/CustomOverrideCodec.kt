package com.imageshare.app.work

import com.imageshare.app.MainViewModel
import com.imageshare.feature.preset.AlphaFallback
import com.imageshare.feature.preset.MetadataPolicy
import com.imageshare.feature.preset.OutputFormat
import com.imageshare.feature.preset.Preset
import com.imageshare.feature.preset.ResizeMode
import org.json.JSONObject

/**
 * Captures the complete effective preset for WorkManager. A background hand-off must never read
 * the live UI override: the foreground and background portions of one batch are one conversion.
 */
internal fun Preset.toWorkerJson(): String = JSONObject()
    .put("type", resize.typeName())
    .putResizeValues(resize)
    .put("format", format.name)
    .put("quality", quality)
    .put("metadata", metadata.name)
    .put("alphaFallback", alphaFallback.name)
    .put("targetSizeBytes", targetSizeBytes ?: JSONObject.NULL)
    .toString()

/** Kept for legacy callers and older serialized work requests that only override resize. */
internal fun MainViewModel.CustomOverride.toWorkerJson(): String = JSONObject()
    .put("type", resize.typeName())
    .putResizeValues(resize)
    .toString()

internal fun Preset.withWorkerOverride(json: String?): Preset {
    if (json.isNullOrBlank()) return this
    return runCatching {
        val values = JSONObject(json)
        copy(
            resize = if (values.has("type")) values.toResizeMode() else resize,
            format = values.enumValue("format", OutputFormat.values()) ?: format,
            quality = values.optInt("quality", quality).takeIf { it in 1..100 } ?: quality,
            metadata = values.enumValue("metadata", MetadataPolicy.values()) ?: metadata,
            alphaFallback = values.enumValue("alphaFallback", AlphaFallback.values()) ?: alphaFallback,
            targetSizeBytes = if (!values.has("targetSizeBytes")) {
                targetSizeBytes
            } else if (values.isNull("targetSizeBytes")) {
                null
            } else {
                values.getLong("targetSizeBytes").takeIf { it > 0L }
            },
        )
    }.getOrDefault(this)
}

private fun ResizeMode.typeName(): String = when (this) {
    is ResizeMode.Exact -> "Exact"
    is ResizeMode.LongEdge -> "LongEdge"
    is ResizeMode.Percentage -> "Percentage"
    ResizeMode.Original -> "Original"
}

private fun JSONObject.putResizeValues(resize: ResizeMode): JSONObject = apply {
    when (resize) {
        is ResizeMode.Exact -> {
            put("width", resize.width)
            put("height", resize.height)
        }
        is ResizeMode.LongEdge -> put("pixels", resize.pixels)
        is ResizeMode.Percentage -> put("pct", resize.pct)
        ResizeMode.Original -> Unit
    }
}

private fun String.toResizeMode(): ResizeMode = JSONObject(this).let { json ->
    when (json.optString("type")) {
        "Exact" -> ResizeMode.Exact(json.requiredInt("width"), json.requiredInt("height"))
        "LongEdge" -> ResizeMode.LongEdge(json.requiredInt("pixels"))
        "Percentage" -> ResizeMode.Percentage(json.requiredInt("pct"))
        "Original" -> ResizeMode.Original
        else -> ResizeMode.Original
    }
}

private fun JSONObject.toResizeMode(): ResizeMode = toString().toResizeMode()

private fun <T : Enum<T>> JSONObject.enumValue(key: String, entries: Array<T>): T? =
    entries.firstOrNull { it.name == optString(key) }

private fun JSONObject.requiredInt(key: String): Int {
    check(has(key)) { "Missing $key" }
    return getInt(key)
}