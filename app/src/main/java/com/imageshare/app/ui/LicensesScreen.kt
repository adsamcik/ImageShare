package com.imageshare.app.ui

import android.content.Intent
import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.imageshare.app.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicensesScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.licenses_screen_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.licenses_back_a11y),
                        )
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(modifier = Modifier.padding(padding)) {
            items(LICENSES) { license ->
                LicenseRow(license)
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun LicenseRow(license: LicenseEntry) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 16.dp),
        ) {
            Text(license.name, style = MaterialTheme.typography.titleMedium)
            Text(license.copyright, style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(license.licenseRes), style = MaterialTheme.typography.bodySmall)
        }
        IconButton(
            onClick = {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(license.url)))
            },
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = stringResource(R.string.licenses_view_source_a11y, license.name),
            )
        }
    }
}

private data class LicenseEntry(
    val name: String,
    val copyright: String,
    @param:StringRes val licenseRes: Int,
    val url: String,
)

private val LICENSES = listOf(
    LicenseEntry(
        "AndroidX",
        "Copyright 2018-2026 The Android Open Source Project",
        R.string.license_label_apache_2,
        "https://developer.android.com/jetpack/androidx",
    ),
    LicenseEntry(
        "Material Components for Android",
        "Copyright 2018-2026 The Android Open Source Project",
        R.string.license_label_apache_2,
        "https://github.com/material-components/material-components-android",
    ),
    LicenseEntry(
        "Coil",
        "Copyright 2019-2026 Coil Contributors",
        R.string.license_label_apache_2,
        "https://github.com/coil-kt/coil",
    ),
    LicenseEntry(
        "Kotlin",
        "Copyright 2010-2026 JetBrains s.r.o. and Kotlin contributors",
        R.string.license_label_apache_2,
        "https://github.com/JetBrains/kotlin",
    ),
    LicenseEntry(
        "kotlinx.coroutines",
        "Copyright 2016-2026 JetBrains s.r.o. and contributors",
        R.string.license_label_apache_2,
        "https://github.com/Kotlin/kotlinx.coroutines",
    ),
    LicenseEntry(
        "JUnit 4",
        "Copyright 2002-2026 JUnit",
        R.string.license_label_epl_1,
        "https://junit.org/junit4/",
    ),
    LicenseEntry(
        "Robolectric",
        "Copyright 2010-2026 Robolectric contributors",
        R.string.license_label_mit,
        "https://robolectric.org/",
    ),
    LicenseEntry(
        "libjpeg-turbo",
        "Copyright 1991-2024 Thomas G. Lane, D. R. Commander, and contributors",
        R.string.license_label_bsd3,
        "https://github.com/libjpeg-turbo/libjpeg-turbo",
    ),
    LicenseEntry(
        "libavif",
        "Copyright 2019-2026 Alliance for Open Media contributors",
        R.string.license_label_bsd2,
        "https://github.com/AOMediaCodec/libavif",
    ),
    LicenseEntry(
        "aom",
        "Copyright 2016-2026 Alliance for Open Media contributors",
        R.string.license_label_bsd2_aom,
        "https://aomedia.googlesource.com/aom/",
    ),
)
