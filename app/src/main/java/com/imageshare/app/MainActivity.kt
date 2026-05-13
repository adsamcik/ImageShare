package com.imageshare.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.imageshare.app.ui.MainScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by lazy {
        ViewModelProvider(this, MainViewModel.Factory(applicationContext))[MainViewModel::class.java]
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MainScreen(viewModel) }
        sweepCachesOnStart()
        if (savedInstanceState == null) {
            handleShareIntent(intent)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
    }

    private fun handleShareIntent(intent: Intent?) {
        val sharedUris = intent.extractImageShareUris()
        if (sharedUris.isEmpty()) return

        val jobId = "share-${System.currentTimeMillis()}-${(0 until SHARE_RANDOM_BOUND).random()}"
        lifecycleScope.launch {
            viewModel.stageSharedUris(jobId, sharedUris)
        }
    }

    private fun sweepCachesOnStart() {
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                AppContainer.persistableUriRegistry.reconcile()
                AppContainer.sharedIntakeStager.sweep()
                AppContainer.outputStore.sweep()
            }.onFailure { Log.w(TAG, "Failed to sweep shared caches", it) }
        }
    }
}

private fun Intent?.extractImageShareUris(): List<Uri> {
    if (this == null) return emptyList()

    return when (action) {
        Intent.ACTION_SEND -> listOfNotNull(getParcelableExtraCompat(Intent.EXTRA_STREAM))
        Intent.ACTION_SEND_MULTIPLE -> getParcelableArrayListExtraCompat(Intent.EXTRA_STREAM).orEmpty()
        else -> emptyList()
    }
}

private fun Intent.getParcelableExtraCompat(name: String): Uri? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(name, Uri::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(name)
    }

private fun Intent.getParcelableArrayListExtraCompat(name: String): ArrayList<Uri>? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableArrayListExtra(name, Uri::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableArrayListExtra(name)
    }

private const val SHARE_RANDOM_BOUND = 10_000
private const val TAG = "MainActivity"
