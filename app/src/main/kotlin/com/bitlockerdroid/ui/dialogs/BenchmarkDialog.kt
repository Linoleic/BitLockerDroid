package com.bitlockerdroid.ui.dialogs

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bitlockerdroid.service.UnlockManager
import com.bitlockerdroid.ui.theme.SuccessGreen
import com.bitlockerdroid.ui.theme.WarningAmber
import com.bitlockerdroid.util.BenchmarkEngine
import com.bitlockerdroid.util.BenchmarkResult
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun BenchmarkDialog(
    devicePath: String,
    onDismiss: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    val core = remember(devicePath) {
        UnlockManager.activeSessions.find { it.devicePath == devicePath }
    }

    var isRunning by remember { mutableStateOf(false) }
    var currentPhase by remember { mutableStateOf("") }
    var currentProgress by remember { mutableStateOf(0f) }
    var result by remember { mutableStateOf<BenchmarkResult?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    fun startTest() {
        if (core == null) {
            errorMessage = "无法定位该卷的解密会话，请确保卷已解锁挂载"
            return
        }
        isRunning = true
        errorMessage = null
        result = null
        currentProgress = 0f
        currentPhase = "准备测试..."

        coroutineScope.launch {
            try {
                val res = BenchmarkEngine.runBenchmark(core) { phase, progress ->
                    currentPhase = phase
                    currentProgress = progress
                }
                result = res
            } catch (e: Exception) {
                errorMessage = "测速异常: ${e.message}"
            } finally {
                isRunning = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!isRunning) onDismiss() },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = "⚡", fontSize = 22.sp)
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = "驱动器基准测速",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = core?.volumeLabel ?: devicePath,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = "基准测试将通过底层驱动进行全链路读取与延时探测，并诊断 OTG 物理协议，全过程严格只读，不影响数据安全。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (isRunning) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = currentPhase,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            LinearProgressIndicator(
                                progress = { currentProgress },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(4.dp)),
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                            Text(
                                text = "${(currentProgress * 100).toInt()}%",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.align(Alignment.End)
                            )
                        }
                    }
                }

                if (errorMessage != null) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = errorMessage ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }

                if (result != null) {
                    val r = result!!
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = "基准测试结果",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "连续读取速率",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = String.format(Locale.US, "%.1f MB/s", r.sequentialReadMbPerSec),
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold
                                    ),
                                    color = if (r.sequentialReadMbPerSec >= 30.0) SuccessGreen else MaterialTheme.colorScheme.onSurface
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "4K 随机读取延时",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = String.format(Locale.US, "%.2f ms (%.0f IOPS)", r.random4kLatencyMs, r.random4kIops),
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.SemiBold
                                    ),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "物理链路协议",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = if (r.usbLinkSpeedMbps != null && r.usbLinkSpeedMbps >= 5000) SuccessGreen.copy(alpha = 0.15f)
                                            else WarningAmber.copy(alpha = 0.15f)
                                ) {
                                    Text(
                                        text = r.usbSpeedDesc,
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Bold
                                        ),
                                        color = if (r.usbLinkSpeedMbps != null && r.usbLinkSpeedMbps >= 5000) SuccessGreen else WarningAmber,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (!isRunning) {
                Button(
                    onClick = { startTest() },
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(
                        imageVector = if (result != null) Icons.Default.Refresh else Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = if (result != null) "重新测速" else "开始测速")
                }
            }
        },
        dismissButton = {
            if (!isRunning) {
                OutlinedButton(
                    onClick = onDismiss,
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(text = "关闭")
                }
            }
        }
    )
}
