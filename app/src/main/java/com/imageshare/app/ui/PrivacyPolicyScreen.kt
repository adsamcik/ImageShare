package com.imageshare.app.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.imageshare.app.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacyPolicyScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.privacy_policy_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.privacy_policy_back_a11y),
                        )
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Text(
                    text = stringResource(R.string.privacy_policy_last_updated),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            item {
                PolicySection(
                    title = stringResource(R.string.privacy_policy_overview_title),
                    body = stringResource(R.string.privacy_policy_overview_body),
                )
            }
            item {
                PolicySection(
                    title = stringResource(R.string.privacy_policy_device_data_title),
                    body = stringResource(R.string.privacy_policy_device_data_body),
                )
            }
            item {
                PolicySection(
                    title = stringResource(R.string.privacy_policy_retention_title),
                    body = stringResource(R.string.privacy_policy_retention_body),
                )
            }
            item {
                PolicySection(
                    title = stringResource(R.string.privacy_policy_permissions_title),
                    body = stringResource(R.string.privacy_policy_permissions_body),
                )
            }
            item {
                PolicySection(
                    title = stringResource(R.string.privacy_policy_metadata_title),
                    body = stringResource(R.string.privacy_policy_metadata_body),
                )
            }
            item {
                PolicySection(
                    title = stringResource(R.string.privacy_policy_children_title),
                    body = stringResource(R.string.privacy_policy_children_body),
                )
            }
            item {
                ContactSection()
            }
        }
    }
}

@Composable
private fun PolicySection(title: String, body: String) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(text = title, style = MaterialTheme.typography.titleMedium)
        Text(text = body, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ContactSection() {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = stringResource(R.string.privacy_policy_contact_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(R.string.privacy_policy_contact_body),
            style = MaterialTheme.typography.bodyMedium,
        )
        TextButton(
            onClick = {
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SUPPORT_URL)))
                } catch (_: ActivityNotFoundException) {
                    // The policy text remains available even when no browser is installed.
                } catch (_: SecurityException) {
                    // Some OEMs reject otherwise valid view intents.
                }
            },
        ) {
            Text(stringResource(R.string.privacy_policy_open_support))
        }
    }
}

private const val SUPPORT_URL = "https://github.com/adsamcik/ImageShare/issues"
