package com.imageshare.core.processing

import android.net.Uri

data class MetadataSource(
    val originalBytes: ByteArray?,
    val originalUri: Uri?,
) {
    companion object {
        val NONE = MetadataSource(null, null)
    }
}
