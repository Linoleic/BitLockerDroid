package com.bitlockerdroid.ui.dialogs

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.bitlockerdroid.R
import com.bitlockerdroid.service.UnlockManager
import com.bitlockerdroid.ui.theme.SuccessGreen
import com.bitlockerdroid.ui.theme.WarningAmber
import com.bitlockerdroid.util.BenchmarkEngine
import com.bitlockerdroid.util.BenchmarkResult
import com.bitlockerdroid.util.BenchmarkTestType
import com.bitlockerdroid.util.PreferenceHelper
import kotlinx.coroutines.launch
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BenchmarkDialog(
    devicePath: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val core = remember(devicePath) {
        UnlockManager.activeSessions.find { it.devicePath == devicePath }
    }

    val isReadOnly = core == null || core.writer == null || PreferenceHelper.isVolumeReadOnly(context, core.volumeGuid, core.devicePath)

    // Initialize selected tests: all available tests based on read-only status
    var selectedTests by remember(isReadOnly) {
        mutableStateOf(
            if (isReadOnly) {
                setOf(BenchmarkTestType.SEQ_READ, BenchmarkTestType.RAND_4K_READ)
            } else {
                BenchmarkTestType.entries.toSet()
            }
        )
    }

    var isRunning by remember { mutableStateOf(false) }
    var currentPhase by remember { mutableStateOf("") }
    var currentProgress by remember { mutableStateOf(0f) }
    var result by remember { mutableStateOf<BenchmarkResult?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    fun runTests(testsToRun: Set<BenchmarkTestType>) {
        if (core == null) {
            errorMessage = context.getString(R.string.benchmark_session_lost)
            return
        }
        if (testsToRun.isEmpty()) return

        isRunning = true
        errorMessage = null
        currentProgress = 0f
        currentPhase = context.getString(R.string.benchmark_preparing)

        coroutineScope.launch {
            try {
                val newRes = BenchmarkEngine.runBenchmark(core, testsToRun, context) { phase, progress ->
                    currentPhase = phase
                    currentProgress = progress
                }
                result = result?.mergeWith(newRes) ?: newRes
            } catch (e: Exception) {
                errorMessage = context.getString(R.string.benchmark_error, e.message ?: "")
            } finally {
                isRunning = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!isRunning) onDismiss() },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                    modifier = Modifier.size(34.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = stringResource(R.string.benchmark_dialog_title),
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
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = stringResource(R.string.benchmark_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Quick preset filter chips
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val allSet = if (isReadOnly) {
                        setOf(BenchmarkTestType.SEQ_READ, BenchmarkTestType.RAND_4K_READ)
                    } else {
                        BenchmarkTestType.entries.toSet()
                    }
                    val readSet = setOf(BenchmarkTestType.SEQ_READ, BenchmarkTestType.RAND_4K_READ)
                    val writeSet = setOf(BenchmarkTestType.SEQ_WRITE, BenchmarkTestType.RAND_4K_WRITE)

                    FilterChip(
                        selected = selectedTests == allSet,
                        onClick = {
                            if (!isRunning) selectedTests = allSet
                        },
                        label = { Text(stringResource(R.string.benchmark_opt_all), fontSize = 12.sp) },
                        enabled = !isRunning
                    )

                    FilterChip(
                        selected = selectedTests == readSet,
                        onClick = {
                            if (!isRunning) selectedTests = readSet
                        },
                        label = { Text(stringResource(R.string.benchmark_opt_readonly), fontSize = 12.sp) },
                        enabled = !isRunning
                    )

                    FilterChip(
                        selected = !isReadOnly && selectedTests == writeSet,
                        onClick = {
                            if (!isRunning && !isReadOnly) selectedTests = writeSet
                        },
                        label = { Text(stringResource(R.string.benchmark_opt_writeonly), fontSize = 12.sp) },
                        enabled = !isRunning && !isReadOnly
                    )
                }

                // Progress indicator during run
                if (isRunning) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
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
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp)),
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

                // Error banner
                if (errorMessage != null) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = errorMessage ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }

                // Test Items List
                BenchmarkItemCard(
                    title = stringResource(R.string.benchmark_item_seq_read_title),
                    subtitle = stringResource(R.string.benchmark_item_seq_read_sub),
                    isSelected = selectedTests.contains(BenchmarkTestType.SEQ_READ),
                    isReadOnlyRestricted = false,
                    isRunning = isRunning,
                    valueText = result?.sequentialReadMbPerSec?.let { String.format(Locale.US, "%.1f MB/s", it) },
                    valueHighlight = (result?.sequentialReadMbPerSec ?: 0.0) >= 30.0,
                    onToggleSelect = {
                        selectedTests = if (selectedTests.contains(BenchmarkTestType.SEQ_READ)) {
                            selectedTests - BenchmarkTestType.SEQ_READ
                        } else {
                            selectedTests + BenchmarkTestType.SEQ_READ
                        }
                    },
                    onRunSingle = { runTests(setOf(BenchmarkTestType.SEQ_READ)) }
                )

                BenchmarkItemCard(
                    title = stringResource(R.string.benchmark_item_seq_write_title),
                    subtitle = stringResource(R.string.benchmark_item_seq_write_sub),
                    isSelected = selectedTests.contains(BenchmarkTestType.SEQ_WRITE),
                    isReadOnlyRestricted = isReadOnly,
                    isRunning = isRunning,
                    valueText = result?.sequentialWriteMbPerSec?.let { String.format(Locale.US, "%.1f MB/s", it) },
                    valueHighlight = (result?.sequentialWriteMbPerSec ?: 0.0) >= 20.0,
                    onToggleSelect = {
                        if (!isReadOnly) {
                            selectedTests = if (selectedTests.contains(BenchmarkTestType.SEQ_WRITE)) {
                                selectedTests - BenchmarkTestType.SEQ_WRITE
                            } else {
                                selectedTests + BenchmarkTestType.SEQ_WRITE
                            }
                        }
                    },
                    onRunSingle = { runTests(setOf(BenchmarkTestType.SEQ_WRITE)) }
                )

                BenchmarkItemCard(
                    title = stringResource(R.string.benchmark_item_rand_read_title),
                    subtitle = stringResource(R.string.benchmark_item_rand_read_sub),
                    isSelected = selectedTests.contains(BenchmarkTestType.RAND_4K_READ),
                    isReadOnlyRestricted = false,
                    isRunning = isRunning,
                    valueText = if (result?.random4kLatencyMs != null && result?.random4kIops != null) {
                        String.format(Locale.US, "%.2f ms (%.0f IOPS)", result!!.random4kLatencyMs, result!!.random4kIops)
                    } else null,
                    valueHighlight = false,
                    onToggleSelect = {
                        selectedTests = if (selectedTests.contains(BenchmarkTestType.RAND_4K_READ)) {
                            selectedTests - BenchmarkTestType.RAND_4K_READ
                        } else {
                            selectedTests + BenchmarkTestType.RAND_4K_READ
                        }
                    },
                    onRunSingle = { runTests(setOf(BenchmarkTestType.RAND_4K_READ)) }
                )

                BenchmarkItemCard(
                    title = stringResource(R.string.benchmark_item_rand_write_title),
                    subtitle = stringResource(R.string.benchmark_item_rand_write_sub),
                    isSelected = selectedTests.contains(BenchmarkTestType.RAND_4K_WRITE),
                    isReadOnlyRestricted = isReadOnly,
                    isRunning = isRunning,
                    valueText = if (result?.random4kWriteLatencyMs != null && result?.random4kWriteIops != null) {
                        String.format(Locale.US, "%.2f ms (%.0f IOPS)", result!!.random4kWriteLatencyMs, result!!.random4kWriteIops)
                    } else null,
                    valueHighlight = false,
                    onToggleSelect = {
                        if (!isReadOnly) {
                            selectedTests = if (selectedTests.contains(BenchmarkTestType.RAND_4K_WRITE)) {
                                selectedTests - BenchmarkTestType.RAND_4K_WRITE
                            } else {
                                selectedTests + BenchmarkTestType.RAND_4K_WRITE
                            }
                        }
                    },
                    onRunSingle = { runTests(setOf(BenchmarkTestType.RAND_4K_WRITE)) }
                )

                // Physical Link Speed Summary Card
                val linkDesc = result?.usbSpeedDesc
                if (linkDesc != null && linkDesc != "Unknown") {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.benchmark_link_speed),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = if (result?.usbLinkSpeedMbps != null && result!!.usbLinkSpeedMbps!! >= 5000)
                                    SuccessGreen.copy(alpha = 0.15f)
                                else WarningAmber.copy(alpha = 0.15f)
                            ) {
                                Text(
                                    text = linkDesc,
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold
                                    ),
                                    color = if (result?.usbLinkSpeedMbps != null && result!!.usbLinkSpeedMbps!! >= 5000)
                                        SuccessGreen
                                    else WarningAmber,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (!isRunning) {
                Button(
                    onClick = { runTests(selectedTests) },
                    enabled = selectedTests.isNotEmpty(),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(
                        imageVector = if (result != null) Icons.Default.Refresh else Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.benchmark_run_selected, selectedTests.size)
                    )
                }
            }
        },
        dismissButton = {
            if (!isRunning) {
                OutlinedButton(
                    onClick = onDismiss,
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(text = stringResource(R.string.close))
                }
            }
        }
    )
}

@Composable
private fun BenchmarkItemCard(
    title: String,
    subtitle: String,
    isSelected: Boolean,
    isReadOnlyRestricted: Boolean,
    isRunning: Boolean,
    valueText: String?,
    valueHighlight: Boolean,
    onToggleSelect: () -> Unit,
    onRunSingle: () -> Unit
) {
    val effectiveSelected = isSelected && !isReadOnlyRestricted

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (effectiveSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
        border = BorderStroke(
            1.dp,
            if (effectiveSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = !isRunning && !isReadOnlyRestricted) { onToggleSelect() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = effectiveSelected,
                    onCheckedChange = { onToggleSelect() },
                    enabled = !isRunning && !isReadOnlyRestricted,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isReadOnlyRestricted) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                    else MaterialTheme.colorScheme.onSurface
                        )
                        if (isReadOnlyRestricted) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                            ) {
                                Text(
                                    text = stringResource(R.string.benchmark_readonly_badge),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                )
                            }
                        }
                    }
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                    )
                }

                // Single-run test button
                OutlinedButton(
                    onClick = onRunSingle,
                    enabled = !isRunning && !isReadOnlyRestricted,
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.height(30.dp)
                ) {
                    Text(
                        text = stringResource(R.string.benchmark_item_run_single),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            // Metric Value Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 32.dp, top = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = valueText ?: "--",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    ),
                    color = when {
                        valueText == null -> MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                        valueHighlight -> SuccessGreen
                        else -> MaterialTheme.colorScheme.primary
                    }
                )
            }
        }
    }
}

