package com.bitlockerdroid.ui.dialogs

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bitlockerdroid.R
import com.bitlockerdroid.ntfs.VolumeDiagnostic
import com.bitlockerdroid.service.UnlockManager
import com.bitlockerdroid.service.UnlockedVolume
import com.bitlockerdroid.ui.theme.SuccessGreen
import com.bitlockerdroid.ui.theme.WarningAmber
import com.bitlockerdroid.ui.volumes.MetaChip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun RepairConfirmDialog(
    volume: UnlockedVolume,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isRepairing by remember { mutableStateOf(false) }
    var diagnosticResult by remember { mutableStateOf<VolumeDiagnostic?>(null) }
    var isDiagnosing by remember { mutableStateOf(true) }

    LaunchedEffect(volume.devicePath) {
        diagnosticResult = withContext(Dispatchers.IO) {
            UnlockManager.diagnoseVolume(volume.devicePath)
        }
        isDiagnosing = false
    }

    val diag = diagnosticResult
    val hasErrors = diag?.hasStructuralErrors == true

    AlertDialog(
        onDismissRequest = { if (!isRepairing) onDismiss() },
        title = {
            Text(
                text = if (hasErrors) stringResource(R.string.diagnostic_corrupted_title)
                else stringResource(R.string.repair_dirty_confirm_title),
                color = if (hasErrors) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (isDiagnosing) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text(
                            text = stringResource(R.string.diagnostic_in_progress),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else if (diag != null) {
                    if (hasErrors) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    text = stringResource(R.string.diagnostic_corrupted_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                                diag.items.filter { !it.passed }.forEach { item ->
                                    Text(
                                        text = "• ${item.name}: ${item.detail}",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    } else {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = SuccessGreen.copy(alpha = 0.12f),
                            border = BorderStroke(1.dp, SuccessGreen.copy(alpha = 0.35f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = stringResource(R.string.diagnostic_passed_title),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = SuccessGreen
                                )
                                Text(
                                    text = stringResource(R.string.diagnostic_passed_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                } else {
                    Text(
                        text = stringResource(R.string.repair_dirty_confirm_desc),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    isRepairing = true
                    scope.launch(Dispatchers.IO) {
                        val res = UnlockManager.repairDirtyVolume(volume.devicePath)
                        withContext(Dispatchers.Main) {
                            isRepairing = false
                            onDismiss()
                            res.onSuccess { fs ->
                                val msg = context.getString(R.string.repair_dirty_success, fs)
                                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                            }.onFailure { err ->
                                val msg = context.getString(R.string.repair_dirty_failed, err.message ?: err.toString())
                                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                },
                enabled = !isRepairing
            ) {
                Text(
                    text = if (hasErrors) stringResource(R.string.diagnostic_force_repair)
                    else stringResource(R.string.repair_dirty_action),
                    color = if (hasErrors) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isRepairing
            ) {
                Text(text = stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
fun StandaloneDiagnosticDialog(
    volume: UnlockedVolume,
    onDismiss: () -> Unit
) {
    var diagnosticResult by remember { mutableStateOf<VolumeDiagnostic?>(null) }
    var isDiagnosing by remember { mutableStateOf(true) }

    LaunchedEffect(volume.devicePath) {
        diagnosticResult = withContext(Dispatchers.IO) {
            UnlockManager.diagnoseVolume(volume.devicePath)
        }
        isDiagnosing = false
    }

    val diag = diagnosticResult
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.diagnostic_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (isDiagnosing) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.padding(vertical = 12.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text(
                            text = stringResource(R.string.diagnostic_in_progress),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                } else if (diag != null) {
                    val statusBg = if (diag.hasStructuralErrors) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
                    else if (diag.isDirty) WarningAmber.copy(alpha = 0.15f)
                    else SuccessGreen.copy(alpha = 0.15f)
                    val statusTextColor = if (diag.hasStructuralErrors) MaterialTheme.colorScheme.error
                    else if (diag.isDirty) WarningAmber
                    else SuccessGreen
                    val statusText = if (diag.hasStructuralErrors) stringResource(R.string.diagnostic_overall_corrupt)
                    else if (diag.isDirty) stringResource(R.string.diagnostic_overall_dirty_only)
                    else stringResource(R.string.diagnostic_overall_healthy)

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = statusBg,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (diag.hasStructuralErrors) Icons.Default.Warning else Icons.Default.Check,
                                contentDescription = null,
                                tint = statusTextColor,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = statusText,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = statusTextColor
                            )
                        }
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        diag.items.forEach { item ->
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = item.name,
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        MetaChip(
                                            text = if (item.passed) stringResource(R.string.diagnostic_item_passed)
                                            else stringResource(R.string.diagnostic_item_failed),
                                            color = if (item.passed) SuccessGreen else MaterialTheme.colorScheme.error
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = item.detail,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                } else {
                    Text(
                        text = stringResource(R.string.repair_dirty_failed, "无法读取底层文件系统诊断元数据"),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(android.R.string.ok))
            }
        }
    )
}
