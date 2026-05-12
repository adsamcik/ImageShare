package com.imageshare.core.io

import android.net.Uri
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.runtime.Composable

@Composable
fun rememberPhotoPickerLauncher(
    onResult: (List<Uri>) -> Unit,
): PhotoPickerLauncher {
    val singleLauncher = rememberLauncherForActivityResult(PickVisualMedia()) { uri ->
        onResult(uri?.let(::listOf).orEmpty())
    }
    val multipleLauncher = rememberLauncherForActivityResult(PickMultipleVisualMedia()) { uris ->
        onResult(uris)
    }

    return PhotoPickerLauncher(singleLauncher, multipleLauncher)
}

class PhotoPickerLauncher internal constructor(
    private val singleLauncher: ManagedActivityResultLauncher<PickVisualMediaRequest, Uri?>,
    private val multipleLauncher: ManagedActivityResultLauncher<PickVisualMediaRequest, List<Uri>>,
) {
    fun launchSingle() {
        singleLauncher.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly))
    }

    fun launchMultiple(maxItems: Int = Int.MAX_VALUE) {
        require(maxItems > 0) { "maxItems must be greater than 0" }
        multipleLauncher.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly))
    }
}
