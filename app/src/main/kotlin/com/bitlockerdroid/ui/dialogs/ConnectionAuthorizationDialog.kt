package com.bitlockerdroid.ui.dialogs

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.bitlockerdroid.R
import com.bitlockerdroid.ui.AuthorizationState
import com.bitlockerdroid.ui.AuthorizationStatus
import com.bitlockerdroid.ui.authorizationLabel
import com.bitlockerdroid.util.RootAccess

/** The same panel is opened from the home indicator and the settings shortcut. */
@Composable
internal fun ConnectionAuthorizationDialog(
    useRootAccess: Boolean,
    status: AuthorizationStatus,
    rootSolution: RootAccess.RootSolutionInfo,
    onRecheck: () -> Unit,
    onUseRootAccessChange: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.connection_authorization_title)) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(stringResource(R.string.connection_mode_label), style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilterChip(
                        selected = !useRootAccess,
                        onClick = { if (useRootAccess) onUseRootAccessChange(false) },
                        label = { Text(stringResource(R.string.connection_mode_usb)) }
                    )
                    FilterChip(
                        selected = useRootAccess,
                        onClick = { if (!useRootAccess) onUseRootAccessChange(true) },
                        label = { Text(stringResource(R.string.connection_mode_root)) }
                    )
                }
                Text(
                    stringResource(if (useRootAccess) R.string.connection_mode_root_desc else R.string.connection_mode_usb_desc),
                    style = MaterialTheme.typography.bodyMedium
                )
                HorizontalDivider()
                Text(stringResource(R.string.settings_root_status), style = MaterialTheme.typography.titleSmall)
                Text(rootSolution.summary.ifBlank { stringResource(R.string.authorization_checking) },
                    style = MaterialTheme.typography.bodyMedium)
                if (useRootAccess) {
                    Text(stringResource(R.string.connection_root_warning_filter),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider()
                Text(
                    if (status.deviceCount != null) stringResource(R.string.connection_usb_connected, status.deviceCount)
                    else stringResource(if (status.state == AuthorizationState.CHECKING) R.string.authorization_checking else R.string.connection_usb_unknown),
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(stringResource(R.string.connection_permission_label), style = MaterialTheme.typography.titleSmall)
                Text(
                    authorizationLabel(useRootAccess, status.state),
                    color = if (status.state == AuthorizationState.REQUIRED || status.state == AuthorizationState.ERROR)
                        MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    stringResource(when {
                        status.requestFailed -> R.string.authorization_request_failed
                        useRootAccess -> R.string.authorization_su_help
                        status.state == AuthorizationState.NO_DEVICE -> R.string.authorization_usb_connect_help
                        status.state == AuthorizationState.ERROR -> R.string.authorization_error_help
                        else -> R.string.authorization_usb_help
                    }, status.grantedCount, status.deviceCount ?: 0),
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    stringResource(R.string.connection_access_note),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
                if (!useRootAccess && status.state == AuthorizationState.REQUIRED) {
                    Button(onClick = status.requestUsbPermission) { Text(stringResource(R.string.authorization_request_usb)) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onRecheck, enabled = status.state != AuthorizationState.CHECKING) {
                Text(stringResource(R.string.authorization_recheck))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } }
    )
}
