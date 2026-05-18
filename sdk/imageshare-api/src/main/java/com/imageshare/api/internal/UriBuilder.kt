package com.imageshare.api.internal

import android.net.Uri
import com.imageshare.api.ImageShareTransform
import com.imageshare.api.TransformRequest

internal object UriBuilder {
    fun build(request: TransformRequest): Uri {
        val builder = Uri.Builder()
            .scheme("content")
            .authority(ImageShareTransform.TRANSFORM_AUTHORITY)
            .appendPath("v1")
            .appendPath(request.format.token)
            .appendPath(request.quality.token)
            .appendPath(request.resize.token)
            .appendPath(request.metadata.token)
            .appendQueryParameter("source", request.source.toString())

        request.targetBytes?.let { builder.appendQueryParameter("targetBytes", it.toString()) }
        if (request.resize is TransformRequest.Resize.Exact && !request.aspectLock) {
            builder.appendQueryParameter("aspectLock", "false")
        }
        return builder.build()
    }

    private val TransformRequest.Quality.token: String
        get() = when (this) {
            TransformRequest.Quality.Auto -> "qauto"
            is TransformRequest.Quality.Fixed -> "q$value"
        }

    private val TransformRequest.Resize.token: String
        get() = when (this) {
            TransformRequest.Resize.Original -> "original"
            is TransformRequest.Resize.LongEdge -> "longEdge$pixels"
            is TransformRequest.Resize.Exact -> "exact${width}x$height"
            is TransformRequest.Resize.Percent -> "percent$percent"
        }
}
