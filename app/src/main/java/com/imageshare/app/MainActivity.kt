package com.imageshare.app

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.imageshare.app.ui.ImageShareTheme
import com.imageshare.app.ui.MainScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by lazy {
        ViewModelProvider(this, MainViewModel.Factory(applicationContext))[MainViewModel::class.java]
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ImageShareTheme {
                MainScreen(
                    viewModel = viewModel,
                    launchTargetInterceptor = { componentName ->
                        smartShareLaunchInterceptor?.invoke(componentName) == true
                    },
                )
            }
        }
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

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) {
            viewModel.onAppBackgrounded()
        }
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
                AppContainer.batchManifestDao.purgeOlderThan(System.currentTimeMillis() - MANIFEST_SWEEP_AGE_MS)
            }.onFailure { Log.w(TAG, "Failed to sweep shared caches", it) }
        }
    }

    companion object {
        @VisibleForTesting
        @Volatile
        var smartShareLaunchInterceptor: ((ComponentName) -> Boolean)? = null
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
        getParcelableExtra(name) as? Uri
    }

private fun Intent.getParcelableArrayListExtraCompat(name: String): ArrayList<Uri>? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableArrayListExtra(name, Uri::class.java)
    } else {
        @Suppress("DEPRECATION", "UNCHECKED_CAST")
        val raw = getParcelableArrayListExtra<android.os.Parcelable>(name) ?: return null
        raw.filterIsInstance<Uri>().let { if (it.isEmpty()) null else ArrayList(it) }
    }

private const val SHARE_RANDOM_BOUND = 10_000
private const val MANIFEST_SWEEP_AGE_MS = 7L * 24L * 60L * 60L * 1_000L
private const val TAG = "MainActivity"
