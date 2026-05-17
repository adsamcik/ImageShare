@file:Suppress("LongParameterList", "LongMethod", "MatchingDeclarationName", "MaxLineLength", "MagicNumber")

package com.imageshare.app.ui

import android.content.ComponentName
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.imageshare.app.R
import com.imageshare.app.sharing.SharingTarget

data class ResolveInfoEntry(
    val componentName: ComponentName,
    val displayName: String,
    val iconBitmap: ImageBitmap?,
)

@Composable
fun SmartShareChooser(
    topTargets: List<SharingTarget>,
    allTargets: List<ResolveInfoEntry>,
    onTargetSelected: (ComponentName) -> Unit,
    onMoreClicked: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val displayNames = remember(allTargets) { allTargets.associate { it.componentName to it.displayName } }
    val availableComponents = remember(allTargets) { allTargets.map { it.componentName }.toSet() }
    val recentTargets = remember(topTargets, availableComponents) {
        topTargets.filter { it.componentName in availableComponents }.take(3)
    }
    val sortedTargets = remember(allTargets) { allTargets.sortedBy { it.displayName.lowercase() } }
    val moreOptionsContentDescription = stringResource(R.string.smart_share_more_options_a11y)

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier.testTag("smart-share-chooser"),
        title = { Text(stringResource(R.string.smart_share_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (recentTargets.isNotEmpty()) {
                    Text(stringResource(R.string.smart_share_recently_used), style = MaterialTheme.typography.titleSmall)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(recentTargets, key = { it.componentName.flattenToString() }) { target ->
                            val displayName = displayNames[target.componentName] ?: target.componentName.packageName
                            val countText = pluralStringResource(
                                R.plurals.smart_share_share_count,
                                target.count,
                                target.count,
                            )
                            val targetDescription = stringResource(
                                R.string.smart_share_target_previous_a11y,
                                displayName,
                                target.count,
                            )
                            AssistChip(
                                onClick = { onTargetSelected(target.componentName) },
                                label = {
                                    Column {
                                        Text(displayName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(countText, style = MaterialTheme.typography.labelSmall)
                                    }
                                },
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .semantics(mergeDescendants = true) {
                                        contentDescription = targetDescription
                                    },
                            )
                        }
                    }
                }

                Text(stringResource(R.string.smart_share_all_apps), style = MaterialTheme.typography.titleSmall)
                LazyColumn(
                    modifier = Modifier
                        .heightIn(max = 320.dp)
                        .testTag("smart-share-all-apps"),
                ) {
                    items(
                        sortedTargets,
                        key = { it.componentName.flattenToString() },
                    ) { target ->
                        ShareTargetRow(target = target, onTargetSelected = onTargetSelected)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onMoreClicked,
                modifier = Modifier.semantics {
                    contentDescription = moreOptionsContentDescription
                },
            ) {
                Text(stringResource(R.string.smart_share_more))
            }
        },
    )
}

@Composable
private fun ShareTargetRow(
    target: ResolveInfoEntry,
    onTargetSelected: (ComponentName) -> Unit,
) {
    val targetContentDescription = stringResource(R.string.smart_share_target_a11y, target.displayName)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .testTag("smart-share-all-app-row")
            .clickable { onTargetSelected(target.componentName) }
            .semantics(mergeDescendants = true) {
                contentDescription = targetContentDescription
            }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        target.iconBitmap?.let {
            Image(bitmap = it, contentDescription = null, modifier = Modifier.size(32.dp))
            Spacer(Modifier.width(12.dp))
        }
        Text(target.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
