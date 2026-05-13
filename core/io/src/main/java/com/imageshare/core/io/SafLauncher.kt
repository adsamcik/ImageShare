package com.imageshare.core.io

import android.net.Uri
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable

@Composable
fun rememberOpenDocumentLauncher(
    onResult: (List<Uri>) -> Unit,
): OpenDocumentLauncher {
    val singleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        onResult(uri?.let(::listOf).orEmpty())
    }
    val multipleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        onResult(uris)
    }
    return OpenDocumentLauncher(singleLauncher, multipleLauncher)
}

class OpenDocumentLauncher internal constructor(
    private val singleLauncher: ManagedActivityResultLauncher<Array<String>, Uri?>,
    private val multipleLauncher: ManagedActivityResultLauncher<Array<String>, List<Uri>>,
) {
    fun launchSingle(mimeTypes: Array<String> = arrayOf("image/*")) {
        singleLauncher.launch(mimeTypes)
    }

    fun launchMultiple(mimeTypes: Array<String> = arrayOf("image/*")) {
        multipleLauncher.launch(mimeTypes)
    }
}
