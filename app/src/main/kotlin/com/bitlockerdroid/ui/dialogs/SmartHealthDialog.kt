package com.bitlockerdroid.ui.dialogs

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.bitlockerdroid.R
import com.bitlockerdroid.smart.SmartHealthInfo
import com.bitlockerdroid.smart.SmartHealthManager
import com.bitlockerdroid.smart.SmartOverallStatus
import com.bitlockerdroid.ui.theme.SuccessGreen
import com.bitlockerdroid.ui.theme.WarningAmber
import com.bitlockerdroid.ui.volumes.MetaChip
import kotlinx.coroutines.launch

@Composable
fun SmartHealthDialog(
    devicePath: String,
    usbDeviceId: Int? = null,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    var smartInfo by remember { mutableStateOf<SmartHealthInfo?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var showRawData by remember { mutableStateOf(false) }

    fun refreshTelemetry() {
        isLoading = true
        scope.launch {
            smartInfo = SmartHealthManager.querySmart(context, devicePath, usbDeviceId)
            isLoading = false
        }
    }

    LaunchedEffect(devicePath) {
        refreshTelemetry()
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.smart_dialog_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        val subtitle = smartInfo?.model
                            ?: smartInfo?.product
                            ?: devicePath
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.smart_close_btn),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                if (isLoading) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(modifier = Modifier.size(36.dp), strokeWidth = 3.dp)
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = stringResource(R.string.smart_loading),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    val info = smartInfo ?: SmartHealthInfo.unsupported()
                    val scrollState = rememberScrollState()

                    Column(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .verticalScroll(scrollState),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // 1. Overall Health Badge Card
                        val (statusText, statusBg, statusFg) = when (info.status) {
                            SmartOverallStatus.HEALTHY -> Triple(
                                stringResource(R.string.smart_status_healthy),
                                SuccessGreen.copy(alpha = 0.15f),
                                SuccessGreen
                            )
                            SmartOverallStatus.WARNING -> Triple(
                                stringResource(R.string.smart_status_warning),
                                WarningAmber.copy(alpha = 0.15f),
                                WarningAmber
                            )
                            SmartOverallStatus.CRITICAL -> Triple(
                                stringResource(R.string.smart_status_critical),
                                MaterialTheme.colorScheme.error.copy(alpha = 0.15f),
                                MaterialTheme.colorScheme.error
                            )
                            SmartOverallStatus.UNSUPPORTED -> Triple(
                                stringResource(R.string.smart_status_unsupported),
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(statusBg, RoundedCornerShape(12.dp))
                                .border(1.dp, statusFg.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                                .padding(14.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(
                                        text = statusText,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = statusFg
                                    )
                                    val diskTypeDesc = if (info.diskType != "Unknown") info.diskType else "Storage Device"
                                    Text(
                                        text = diskTypeDesc,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                if (info.healthPercentage != null) {
                                    Text(
                                        text = "${info.healthPercentage}%",
                                        style = MaterialTheme.typography.headlineMedium,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = statusFg
                                    )
                                }
                            }
                        }

                        // 2. Unsupported Telemetry Banner
                        if (!info.isSupported) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
                                    .padding(12.dp)
                            ) {
                                Row(verticalAlignment = Alignment.Top) {
                                    Icon(
                                        imageVector = Icons.Default.Info,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = info.reason ?: stringResource(R.string.smart_unsupported_desc),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        } else {
                            // 3. Core Telemetry Metric Grid
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    // Temperature
                                    info.temperatureCelsius?.let { temp ->
                                        val tempColor = if (temp <= 50) SuccessGreen
                                                        else if (temp <= 65) WarningAmber
                                                        else MaterialTheme.colorScheme.error
                                        SmartMetricCard(
                                            modifier = Modifier.weight(1f),
                                            title = stringResource(R.string.smart_metric_temp),
                                            value = "$temp °C",
                                            valueColor = tempColor
                                        )
                                    }

                                    // Health / Remaining Life
                                    info.healthPercentage?.let { hp ->
                                        SmartMetricCard(
                                            modifier = Modifier.weight(1f),
                                            title = stringResource(R.string.smart_metric_health),
                                            value = "$hp %",
                                            valueColor = if (hp >= 80) SuccessGreen else if (hp >= 20) WarningAmber else MaterialTheme.colorScheme.error
                                        )
                                    }
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    // Power Hours
                                    info.powerHours?.let { ph ->
                                        val days = ph / 24
                                        SmartMetricCard(
                                            modifier = Modifier.weight(1f),
                                            title = stringResource(R.string.smart_metric_power_hours),
                                            value = "$ph h",
                                            subtitle = if (days > 0) "≈ $days d" else null
                                        )
                                    }

                                    // Power Cycles
                                    info.powerCycles?.let { pc ->
                                        SmartMetricCard(
                                            modifier = Modifier.weight(1f),
                                            title = stringResource(R.string.smart_metric_power_cycles),
                                            value = "$pc"
                                        )
                                    }
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    // Unsafe Shutdowns
                                    info.unsafeShutdowns?.let { us ->
                                        SmartMetricCard(
                                            modifier = Modifier.weight(1f),
                                            title = stringResource(R.string.smart_metric_unsafe_shutdowns),
                                            value = "$us",
                                            valueColor = if (us > 50) WarningAmber else MaterialTheme.colorScheme.onSurface
                                        )
                                    }

                                    // Total Host Writes (TBW)
                                    info.totalTbWritten?.let { tbw ->
                                        val displayStr = if (tbw >= 1.0) "%.2f TB".format(tbw)
                                                         else "%.1f GB".format(tbw * 1024.0)
                                        SmartMetricCard(
                                            modifier = Modifier.weight(1f),
                                            title = stringResource(R.string.smart_metric_total_writes),
                                            value = displayStr
                                        )
                                    }
                                }

                                // SATA-Specific Error Metrics (05, C5, C6)
                                if (info.reallocatedSectors != null || info.pendingSectors != null) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        info.reallocatedSectors?.let { rs ->
                                            SmartMetricCard(
                                                modifier = Modifier.weight(1f),
                                                title = stringResource(R.string.smart_metric_reallocated),
                                                value = "$rs",
                                                valueColor = if (rs > 0) MaterialTheme.colorScheme.error else SuccessGreen
                                            )
                                        }
                                        info.pendingSectors?.let { ps ->
                                            SmartMetricCard(
                                                modifier = Modifier.weight(1f),
                                                title = stringResource(R.string.smart_metric_pending),
                                                value = "$ps",
                                                valueColor = if (ps > 0) MaterialTheme.colorScheme.error else SuccessGreen
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // 4. Physical Drive Information Box
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f), RoundedCornerShape(10.dp))
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
                                .padding(12.dp)
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                info.model?.let {
                                    SmartDetailRow(
                                        label = stringResource(R.string.smart_info_model),
                                        value = it,
                                        onCopy = {
                                            clipboardManager.setText(AnnotatedString(it))
                                            Toast.makeText(context, R.string.smart_copied, Toast.LENGTH_SHORT).show()
                                        }
                                    )
                                }

                                info.serial?.let {
                                    SmartDetailRow(
                                        label = stringResource(R.string.smart_info_serial),
                                        value = it,
                                        isMonospace = true,
                                        onCopy = {
                                            clipboardManager.setText(AnnotatedString(it))
                                            Toast.makeText(context, R.string.smart_copied, Toast.LENGTH_SHORT).show()
                                        }
                                    )
                                }

                                info.firmware?.let {
                                    SmartDetailRow(
                                        label = stringResource(R.string.smart_info_firmware),
                                        value = it,
                                        isMonospace = true
                                    )
                                }

                                val node = info.deviceNode ?: devicePath
                                SmartDetailRow(
                                    label = stringResource(R.string.smart_info_node),
                                    value = node,
                                    isMonospace = true
                                )
                            }
                        }

                        // 5. Raw S.M.A.R.T. Telemetry Data Box (Collapsible)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f), RoundedCornerShape(10.dp))
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
                                .padding(12.dp)
                        ) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { showRawData = !showRawData },
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Default.Info,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = stringResource(R.string.smart_raw_data_title),
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    }

                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = if (showRawData) stringResource(R.string.smart_raw_data_hide)
                                                   else stringResource(R.string.smart_raw_data_view),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Icon(
                                            imageVector = if (showRawData) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp),
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }

                                AnimatedVisibility(visible = showRawData) {
                                    Column(modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.End
                                        ) {
                                            OutlinedButton(
                                                onClick = {
                                                    clipboardManager.setText(AnnotatedString(info.formattedRawData))
                                                    Toast.makeText(context, R.string.smart_copied, Toast.LENGTH_SHORT).show()
                                                },
                                                shape = RoundedCornerShape(8.dp),
                                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                                modifier = Modifier.height(30.dp)
                                            ) {
                                                Icon(
                                                    painter = painterResource(R.drawable.ic_content_copy),
                                                    contentDescription = null,
                                                    modifier = Modifier.size(12.dp)
                                                )
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text(
                                                    text = stringResource(R.string.smart_raw_data_copy),
                                                    style = MaterialTheme.typography.labelSmall
                                                )
                                            }
                                        }

                                        Spacer(modifier = Modifier.height(8.dp))

                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .background(
                                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                                                    RoundedCornerShape(8.dp)
                                                )
                                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                                                .padding(10.dp)
                                        ) {
                                            SelectionContainer {
                                                Text(
                                                    text = info.formattedRawData,
                                                    style = MaterialTheme.typography.bodySmall.copy(
                                                        fontFamily = FontFamily.Monospace,
                                                        fontSize = 11.sp,
                                                        lineHeight = 16.sp
                                                    ),
                                                    color = MaterialTheme.colorScheme.onSurface
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Actions Footer
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = { refreshTelemetry() },
                        enabled = !isLoading
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(text = stringResource(R.string.smart_refresh_btn))
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Button(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(text = stringResource(R.string.smart_close_btn))
                    }
                }
            }
        }
    }
}

@Composable
private fun SmartMetricCard(
    modifier: Modifier = Modifier,
    title: String,
    value: String,
    subtitle: String? = null,
    valueColor: Color = MaterialTheme.colorScheme.onSurface
) {
    Box(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
            .padding(10.dp)
    ) {
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp
            )
            Spacer(modifier = Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = valueColor
                )
                subtitle?.let {
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun SmartDetailRow(
    label: String,
    value: String,
    isMonospace: Boolean = false,
    onCopy: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onCopy != null) Modifier.clickable { onCopy() } else Modifier),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = if (isMonospace) FontFamily.Monospace else FontFamily.Default
                ),
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium
            )
            if (onCopy != null) {
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    painter = painterResource(R.drawable.ic_content_copy),
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                    tint = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}
