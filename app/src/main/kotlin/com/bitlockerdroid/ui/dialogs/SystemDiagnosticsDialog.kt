package com.bitlockerdroid.ui.dialogs

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.bitlockerdroid.R
import com.bitlockerdroid.ui.settings.SettingsClickableItem
import com.bitlockerdroid.ui.settings.SettingsGroup
import com.bitlockerdroid.ui.settings.SettingsStatusItem
import com.bitlockerdroid.ui.theme.SuccessGreen
import com.bitlockerdroid.util.RootAccess

/**
 * Dedicated System Diagnostics Dialog:
 * - SELinux runtime enforcement status
 * - Application diagnostic execution logs viewer and exporter
 * (Legacy 16KB modern architecture card removed as requested)
 */
@Composable
fun SystemDiagnosticsDialog(
    onOpenLog: () -> Unit,
    onDismiss: () -> Unit
) {
    val selinuxStatus = RootAccess.getSelinuxStatus()

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
                        imageVector = Icons.Default.Info,
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
                    text = stringResource(R.string.settings_header_diag),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.settings_view_log_desc),
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
                SettingsGroup(
                    title = stringResource(R.string.settings_header_diag)
                ) {
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

                    SettingsClickableItem(
                        title = stringResource(R.string.log_title),
                        description = stringResource(R.string.settings_view_log_desc),
                        badgeText = stringResource(R.string.creds_view_password),
                        onClick = {
                            onDismiss()
                            onOpenLog()
                        }
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
