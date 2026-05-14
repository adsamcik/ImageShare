package com.imageshare.app.work

import com.imageshare.app.MainViewModel
import com.imageshare.feature.preset.Preset
import com.imageshare.feature.preset.ResizeMode
import org.json.JSONObject

internal fun MainViewModel.CustomOverride.toWorkerJson(): String = when (val resize = resize) {
    is ResizeMode.Exact -> JSONObject()
        .put("type", "Exact")
        .put("width", resize.width)
        .put("height", resize.height)
        .toString()
    is ResizeMode.LongEdge -> JSONObject()
        .put("type", "LongEdge")
        .put("pixels", resize.pixels)
        .toString()
    is ResizeMode.Percentage -> JSONObject()
        .put("type", "Percentage")
        .put("pct", resize.pct)
        .toString()
    ResizeMode.Original -> JSONObject()
        .put("type", "Original")
        .toString()
}

internal fun Preset.withWorkerOverride(json: String?): Preset {
    if (json.isNullOrBlank()) return this
    return runCatching { copy(resize = json.toResizeMode()) }.getOrDefault(this)
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

private fun JSONObject.requiredInt(key: String): Int {
    check(has(key)) { "Missing $key" }
    return getInt(key)
}
