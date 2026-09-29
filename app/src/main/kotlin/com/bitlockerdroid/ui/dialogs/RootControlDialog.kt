package com.bitlockerdroid.ui.dialogs

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.bitlockerdroid.R
import com.bitlockerdroid.ui.settings.SettingsGroup
import com.bitlockerdroid.ui.settings.SettingsStatusItem
import com.bitlockerdroid.ui.settings.SettingsSwitchItem
import com.bitlockerdroid.ui.theme.SuccessGreen
import com.bitlockerdroid.util.RootAccess

/**
 * Dedicated Root Permission Control Dialog:
 * - Master switch for Root privilege usage (with fallback to non-root USB Host mode)
 * - Detailed Root environment and solution status detection
 * - Root-exclusive feature switches (system corrupt notification suppression)
 */
@Composable
fun RootControlDialog(
    rootSolution: RootAccess.RootSolutionInfo,
    useRootAccess: Boolean,
    onUseRootAccessChange: (Boolean) -> Unit,
    suppressCorruptNotification: Boolean,
    onSuppressCorruptNotificationChange: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val canUseRootFeatures = useRootAccess && rootSolution.isDeviceRooted

    val rootSummary = if (!useRootAccess) {
        if (rootSolution.isDeviceRooted) {
            "${rootSolution.solutionName} · ${stringResource(R.string.settings_root_disabled_badge)}"
        } else {
            stringResource(R.string.settings_non_root_mode)
        }
    } else {
        rootSolution.summary
    }

    val rootBadge = if (!useRootAccess) {
        stringResource(R.string.settings_non_root_mode)
    } else if (rootSolution.hasRoot) {
        rootSolution.solutionName
    } else {
        stringResource(R.string.settings_root_not_granted)
    }

    val rootBadgeColor = if (!useRootAccess) {
        MaterialTheme.colorScheme.outline
    } else if (rootSolution.hasRoot) {
        SuccessGreen
    } else {
        MaterialTheme.colorScheme.error
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier
            .fillMaxWidth(0.92f)
            .widthIn(max = 520.dp),
        icon = {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(52.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(26.dp)
                    )
                }
            }
        },
        title = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = stringResource(R.string.settings_header_root_mode),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.settings_advanced_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Section 1: Root Environment Status
                SettingsGroup(
                    title = stringResource(R.string.settings_root_status)
                ) {
                    SettingsStatusItem(
                        title = stringResource(R.string.settings_root_status),
                        description = rootSummary,
                        badgeText = rootBadge,
                        badgeColor = rootBadgeColor
                    )
                }

                // Section 2: Root Master Switch
                SettingsGroup(
                    title = stringResource(R.string.settings_header_root_mode)
                ) {
                    SettingsSwitchItem(
                        title = stringResource(R.string.settings_use_root),
                        description = stringResource(R.string.settings_use_root_desc),
                        checked = useRootAccess,
                        onCheckedChange = onUseRootAccessChange
                    )
                }

                // Section 3: Root-Exclusive Features
                SettingsGroup(
                    title = stringResource(R.string.settings_root_features_header)
                ) {
                    if (!canUseRootFeatures) {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Lock,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = stringResource(R.string.settings_root_required_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
                    }

                    SettingsSwitchItem(
                        title = stringResource(R.string.settings_suppress_corrupt_notification),
                        description = stringResource(R.string.settings_suppress_corrupt_notification_desc),
                        checked = suppressCorruptNotification && canUseRootFeatures,
                        enabled = canUseRootFeatures,
                        onCheckedChange = onSuppressCorruptNotificationChange
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    text = stringResource(R.string.close),
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    )
}
