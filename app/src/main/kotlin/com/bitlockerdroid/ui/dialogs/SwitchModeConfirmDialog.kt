package com.bitlockerdroid.ui.dialogs

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.bitlockerdroid.R

@Composable
fun SwitchModeConfirmDialog(
    activeVolumeCount: Int,
    isProcessing: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = {
            if (!isProcessing) onDismiss()
        },
        title = {
            Text(
                text = stringResource(R.string.switch_mode_title),
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (isProcessing) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(28.dp),
                            strokeWidth = 3.dp
                        )
                        Text(
                            text = stringResource(R.string.switch_mode_progress),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                } else {
                    val message = if (activeVolumeCount > 0) {
                        stringResource(R.string.switch_mode_msg_with_volumes, activeVolumeCount)
                    } else {
                        stringResource(R.string.switch_mode_msg_idle)
                    }
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = !isProcessing,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                val buttonText = if (activeVolumeCount > 0) {
                    stringResource(R.string.switch_mode_confirm_eject_restart)
                } else {
                    stringResource(R.string.switch_mode_confirm_restart)
                }
                Text(text = buttonText)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isProcessing
            ) {
                Text(text = stringResource(R.string.cancel))
            }
        }
    )
}
