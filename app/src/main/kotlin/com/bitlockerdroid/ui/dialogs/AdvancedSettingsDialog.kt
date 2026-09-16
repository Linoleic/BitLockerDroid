package com.bitlockerdroid.ui.dialogs

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bitlockerdroid.R
import com.bitlockerdroid.ui.settings.SettingsClickableItem
import com.bitlockerdroid.ui.settings.SettingsGroup
import com.bitlockerdroid.ui.settings.SettingsStatusItem
import com.bitlockerdroid.ui.settings.SettingsSwitchItem
import com.bitlockerdroid.ui.theme.SuccessGreen
import com.bitlockerdroid.util.RootAccess

/**
 * Dedicated Advanced Options dialog:
 * - Master switch for Root privilege usage (fallback to pure Non-Root USB Host mode)
 * - Root-exclusive feature switches (POSIX mount, notification suppressor)
 * - System environment and diagnostic indicators (Root, SELinux, 16KB alignment, App logs)
 */
@Composable
fun AdvancedSettingsDialog(
    rootSolution: RootAccess.RootSolutionInfo,
    useRootAccess: Boolean,
    onUseRootAccessChange: (Boolean) -> Unit,
    virtualMountEnabled: Boolean,
    onVirtualMountChange: (Boolean) -> Unit,
    suppressCorruptNotification: Boolean,
    onSuppressCorruptNotificationChange: (Boolean) -> Unit,
    onOpenLog: () -> Unit,
    onDismiss: () -> Unit
) {
    val cardBg = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
    val selinuxStatus = RootAccess.getSelinuxStatus()
    val canUseRootFeatures = useRootAccess && rootSolution.isDeviceRooted

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(
                onClick = onDismiss,
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(text = stringResource(R.string.done))
            }
        },
        icon = {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(48.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        },
        title = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = stringResource(R.string.settings_advanced_title),
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
                    .heightIn(max = 500.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Section 1: Root Permission Master Switch
                SettingsGroup(
                    title = stringResource(R.string.settings_use_root),
                    containerColor = cardBg
                ) {
                    SettingsSwitchItem(
                        title = stringResource(R.string.settings_use_root),
                        description = stringResource(R.string.settings_use_root_desc),
                        checked = useRootAccess,
                        onCheckedChange = onUseRootAccessChange
                    )
                }

                // Section 2: Root-Exclusive Features
                SettingsGroup(
                    title = stringResource(R.string.settings_root_features_header),
                    containerColor = cardBg
                ) {
                    if (!canUseRootFeatures) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.settings_root_required_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }

                    SettingsSwitchItem(
                        title = stringResource(R.string.settings_posix_mount),
                        description = stringResource(R.string.settings_posix_mount_desc),
                        checked = virtualMountEnabled && canUseRootFeatures,
                        enabled = canUseRootFeatures,
                        onCheckedChange = onVirtualMountChange
                    )

                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                    )

                    SettingsSwitchItem(
                        title = stringResource(R.string.settings_suppress_corrupt_notification),
                        description = stringResource(R.string.settings_suppress_corrupt_notification_desc),
                        checked = suppressCorruptNotification && canUseRootFeatures,
                        enabled = canUseRootFeatures,
                        onCheckedChange = onSuppressCorruptNotificationChange
                    )
                }

                // Section 3: System Environment & Diagnostics
                SettingsGroup(
                    title = stringResource(R.string.settings_header_diag),
                    containerColor = cardBg
                ) {
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

                    SettingsStatusItem(
                        title = stringResource(R.string.settings_root_status),
                        description = rootSummary,
                        badgeText = rootBadge,
                        badgeColor = rootBadgeColor
                    )

                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                    )

                    SettingsStatusItem(
                        title = stringResource(R.string.settings_selinux_status),
                        description = "SELinux: $selinuxStatus",
                        badgeText = selinuxStatus,
                        badgeColor = if (selinuxStatus == "Enforcing") SuccessGreen else MaterialTheme.colorScheme.tertiary
                    )

                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                    )

                    SettingsStatusItem(
                        title = stringResource(R.string.settings_align_status),
                        description = stringResource(R.string.settings_align_ok),
                        badgeText = "16KB Ready",
                        badgeColor = SuccessGreen
                    )

                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                    )

                    SettingsClickableItem(
                        title = stringResource(R.string.log_title),
                        description = stringResource(R.string.settings_view_log_desc),
                        onClick = onOpenLog
                    )
                }
            }
        }
    )
}
