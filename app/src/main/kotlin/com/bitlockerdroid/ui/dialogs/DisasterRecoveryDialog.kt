package com.bitlockerdroid.ui.dialogs

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.bitlockerdroid.R
import com.bitlockerdroid.disaster.FveMetadataContainer
import com.bitlockerdroid.disaster.VolumeDumpService
import com.bitlockerdroid.ui.theme.BitLockerBlue
import com.bitlockerdroid.ui.theme.SuccessGreen
import com.bitlockerdroid.ui.theme.WarningAmber
import com.bitlockerdroid.util.NativeBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale

@Composable
fun DisasterRecoveryDialog(
    volumeGuid: String,
    devicePath: String,
    partitionOffset: Long,
    totalVolumeSize: Long,
    volumeLabel: String,
    sessionHandle: Long = 0L,
    isUnlocked: Boolean = false,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var selectedTab by remember { mutableIntStateOf(0) }

    // Operation states
    var isProcessing by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var isErrorMessage by remember { mutableStateOf(false) }

    // Restore confirmation dialog state
    var pendingRestoreBytes by remember { mutableStateOf<ByteArray?>(null) }
    var pendingRestoreHeader by remember { mutableStateOf<FveMetadataContainer.Header?>(null) }
    var showRestoreConfirmDialog by remember { mutableStateOf(false) }
    var restoreSecurityAlert by remember { mutableStateOf<String?>(null) }

    // Dump mode selection: default Decrypted if unlocked, otherwise Raw
    var selectedDumpMode by remember {
        mutableIntStateOf(if (isUnlocked) VolumeDumpService.MODE_DECRYPTED else VolumeDumpService.MODE_RAW_ENCRYPTED)
    }

    // Observe global dump progress
    val dumpProgress by VolumeDumpService.progressState.collectAsState()

    // SAF launcher for exporting .fvemeta
    val createFveMetaLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri: Uri? ->
        if (uri != null) {
            coroutineScope.launch {
                isProcessing = true
                statusMessage = null
                try {
                    val bytes = withContext(Dispatchers.IO) {
                        if (sessionHandle != 0L) {
                            NativeBridge.nativeExtractFveMetadataFromHandle(sessionHandle)
                        } else {
                            NativeBridge.nativeExtractFveMetadata(devicePath, partitionOffset)
                        }
                    }

                    if (bytes == null || bytes.isEmpty()) {
                        val errMsg = NativeBridge.nativeGetLastError().ifEmpty { "Extract failed" }
                        throw java.lang.IllegalStateException(errMsg)
                    }

                    val header = FveMetadataContainer.parseHeader(bytes)
                    if (header == null || !FveMetadataContainer.verifyIntegrity(bytes)) {
                        throw IllegalStateException("Generated backup integrity check failed")
                    }

                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri, "w")?.use { out ->
                            out.write(bytes)
                            out.flush()
                        } ?: throw IllegalStateException("Cannot open output stream")
                    }

                    val sizeStr = FveMetadataContainer.formatBytes(bytes.size.toLong())
                    statusMessage = context.getString(R.string.disaster_meta_backup_success, sizeStr)
                    isErrorMessage = false
                    Toast.makeText(context, statusMessage, Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    statusMessage = context.getString(R.string.disaster_meta_backup_failed, e.localizedMessage ?: "Unknown error")
                    isErrorMessage = true
                } finally {
                    isProcessing = false
                }
            }
        }
    }

    // SAF launcher for selecting .fvemeta to restore
    val openFveMetaLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            coroutineScope.launch {
                isProcessing = true
                statusMessage = null
                try {
                    val bytes = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.use { stream ->
                            stream.readBytes()
                        } ?: throw IllegalStateException("Cannot open input stream")
                    }

                    val header = FveMetadataContainer.parseHeader(bytes)
                    if (header == null || !FveMetadataContainer.verifyIntegrity(bytes)) {
                        restoreSecurityAlert = context.getString(R.string.disaster_meta_restore_invalid)
                        return@launch
                    }

                    // Strict safety check on volume size
                    val targetSize = totalVolumeSize
                    if (targetSize > 0L && header.volumeSize > 0L) {
                        if (targetSize < header.volumeSize || targetSize > header.volumeSize + (32L * 1024L * 1024L)) {
                            val targetStr = FveMetadataContainer.formatBytes(targetSize)
                            val backupStr = FveMetadataContainer.formatBytes(header.volumeSize)
                            restoreSecurityAlert = context.getString(
                                R.string.disaster_meta_restore_size_mismatch,
                                targetStr,
                                backupStr
                            )
                            return@launch
                        }
                    }

                    // Pre-checks passed: prompt confirmation
                    pendingRestoreBytes = bytes
                    pendingRestoreHeader = header
                    showRestoreConfirmDialog = true
                } catch (e: Exception) {
                    statusMessage = context.getString(R.string.disaster_meta_restore_failed, e.localizedMessage ?: "Unknown error")
                    isErrorMessage = true
                } finally {
                    isProcessing = false
                }
            }
        }
    }

    // SAF launcher for creating .img image dump file
    val createImgLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri: Uri? ->
        if (uri != null) {
            val intent = Intent(context, VolumeDumpService::class.java).apply {
                action = VolumeDumpService.ACTION_START_DUMP
                putExtra(VolumeDumpService.EXTRA_MODE, selectedDumpMode)
                putExtra(VolumeDumpService.EXTRA_HANDLE, sessionHandle)
                putExtra(VolumeDumpService.EXTRA_DEVICE_PATH, devicePath)
                putExtra(VolumeDumpService.EXTRA_PARTITION_OFFSET, partitionOffset)
                putExtra(VolumeDumpService.EXTRA_TOTAL_SIZE, totalVolumeSize)
                putExtra(VolumeDumpService.EXTRA_TARGET_URI, uri.toString())
                putExtra(VolumeDumpService.EXTRA_VOLUME_LABEL, volumeLabel)
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier
            .widthIn(min = 360.dp, max = 560.dp)
            .fillMaxWidth(0.92f)
            .padding(vertical = 16.dp),
        confirmButton = {
            Button(
                onClick = onDismiss,
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(text = stringResource(R.string.done))
            }
        },
        icon = {
            Icon(
                painter = painterResource(id = R.drawable.ic_shield_check),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp)
            )
        },
        title = {
            Text(
                text = stringResource(R.string.disaster_recovery_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                // Volume identification banner
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp)
                    ) {
                        Text(
                            text = volumeLabel,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "$devicePath · ${FveMetadataContainer.formatBytes(totalVolumeSize)}",
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Tabs: 0 -> FVE Metadata, 1 -> Disk Image Dump
                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = Color.Transparent,
                    contentColor = MaterialTheme.colorScheme.primary
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text(text = stringResource(R.string.disaster_backup_meta)) }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text(text = stringResource(R.string.disaster_dump_image)) }
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Status / Error Banner
                AnimatedVisibility(visible = statusMessage != null) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (isErrorMessage) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
                                else SuccessGreen.copy(alpha = 0.15f),
                        border = BorderStroke(
                            1.dp,
                            if (isErrorMessage) MaterialTheme.colorScheme.error.copy(alpha = 0.4f)
                            else SuccessGreen.copy(alpha = 0.4f)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp)
                    ) {
                        Text(
                            text = statusMessage ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isErrorMessage) MaterialTheme.colorScheme.error else SuccessGreen,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }

                if (selectedTab == 0) {
                    // TAB 0: FVE Metadata Operations
                    Text(
                        text = stringResource(R.string.disaster_backup_meta_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    // Action 1: Backup Metadata
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text(
                                text = stringResource(R.string.disaster_backup_meta),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Sector 0 VBR + 3x FVE regions (.fvemeta, ~200KB)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Button(
                                onClick = {
                                    val safeId = if (volumeGuid.length >= 8) volumeGuid.take(8) else "meta"
                                    val defaultName = "BitLocker_${safeId}_${System.currentTimeMillis()}.fvemeta"
                                    createFveMetaLauncher.launch(defaultName)
                                },
                                enabled = !isProcessing,
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                if (isProcessing) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = Color.White
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                }
                                Text(text = stringResource(R.string.disaster_backup_meta))
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Action 2: Restore Metadata
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = WarningAmber.copy(alpha = 0.08f),
                        border = BorderStroke(1.dp, WarningAmber.copy(alpha = 0.35f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text(
                                text = stringResource(R.string.disaster_restore_meta),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = stringResource(R.string.disaster_restore_meta_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            OutlinedButton(
                                onClick = {
                                    openFveMetaLauncher.launch(arrayOf("*/*"))
                                },
                                enabled = !isProcessing,
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = WarningAmber
                                ),
                                border = BorderStroke(1.dp, WarningAmber.copy(alpha = 0.6f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(text = stringResource(R.string.disaster_restore_meta))
                            }
                        }
                    }
                } else {
                    // TAB 1: Disk Image Export
                    if (dumpProgress.isRunning) {
                        // Running progress monitor
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = stringResource(R.string.dump_in_progress, dumpProgress.volumeLabel),
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        text = String.format(Locale.getDefault(), "%.1f%%", dumpProgress.progressPercent),
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                LinearProgressIndicator(
                                    progress = { dumpProgress.progressPercent / 100f },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(8.dp)
                                        .clip(RoundedCornerShape(4.dp)),
                                    color = MaterialTheme.colorScheme.primary,
                                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                                    drawStopIndicator = {}
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    val speedMb = dumpProgress.bytesPerSec / (1024.0 * 1024.0)
                                    Text(
                                        text = String.format(Locale.getDefault(), "%.1f MB/s", speedMb),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = "ETA: ${dumpProgress.etaSeconds}s",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Spacer(modifier = Modifier.height(10.dp))
                                OutlinedButton(
                                    onClick = { VolumeDumpService.cancelDump() },
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = MaterialTheme.colorScheme.error
                                    ),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(text = stringResource(R.string.cancel))
                                }
                            }
                        }
                    } else {
                        // Image Dump Configuration
                        Text(
                            text = stringResource(R.string.disaster_dump_size_label, FveMetadataContainer.formatBytes(totalVolumeSize)),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(10.dp))

                        // Option 1: Decrypted virtual volume image (default for unlocked)
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (selectedDumpMode == VolumeDumpService.MODE_DECRYPTED) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
                                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                            border = BorderStroke(
                                1.dp,
                                if (selectedDumpMode == VolumeDumpService.MODE_DECRYPTED) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = isUnlocked) {
                                    selectedDumpMode = VolumeDumpService.MODE_DECRYPTED
                                }
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = selectedDumpMode == VolumeDumpService.MODE_DECRYPTED,
                                    onClick = { if (isUnlocked) selectedDumpMode = VolumeDumpService.MODE_DECRYPTED },
                                    enabled = isUnlocked
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = stringResource(R.string.disaster_dump_mode_decrypted),
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isUnlocked) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = stringResource(R.string.disaster_dump_mode_decrypted_desc),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // Option 2: Raw Encrypted partition image
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (selectedDumpMode == VolumeDumpService.MODE_RAW_ENCRYPTED) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
                                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                            border = BorderStroke(
                                1.dp,
                                if (selectedDumpMode == VolumeDumpService.MODE_RAW_ENCRYPTED) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selectedDumpMode = VolumeDumpService.MODE_RAW_ENCRYPTED
                                }
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = selectedDumpMode == VolumeDumpService.MODE_RAW_ENCRYPTED,
                                    onClick = { selectedDumpMode = VolumeDumpService.MODE_RAW_ENCRYPTED }
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = stringResource(R.string.disaster_dump_mode_raw),
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = stringResource(R.string.disaster_dump_mode_raw_desc),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        Button(
                            onClick = {
                                val safeId = if (volumeGuid.length >= 8) volumeGuid.take(8) else "vol"
                                val suffix = if (selectedDumpMode == VolumeDumpService.MODE_DECRYPTED) "Decrypted" else "Raw"
                                val defaultName = "BitLocker_${safeId}_${suffix}.img"
                                createImgLauncher.launch(defaultName)
                            },
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(text = stringResource(R.string.disaster_dump_start))
                        }
                    }
                }
            }
        }
    )

    // Security Alert Dialog (Strict Prohibitions)
    if (restoreSecurityAlert != null) {
        AlertDialog(
            onDismissRequest = { restoreSecurityAlert = null },
            title = {
                Text(
                    text = stringResource(R.string.disaster_meta_restore_title),
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error
                )
            },
            text = {
                Text(
                    text = restoreSecurityAlert ?: "",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = { restoreSecurityAlert = null },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(text = stringResource(R.string.done))
                }
            }
        )
    }

    // Confirmation Dialog before actual writing of .fvemeta
    if (showRestoreConfirmDialog && pendingRestoreHeader != null && pendingRestoreBytes != null) {
        val hdr = pendingRestoreHeader!!
        val bytes = pendingRestoreBytes!!
        AlertDialog(
            onDismissRequest = {
                showRestoreConfirmDialog = false
                pendingRestoreBytes = null
                pendingRestoreHeader = null
            },
            title = {
                Text(
                    text = stringResource(R.string.disaster_meta_restore_title),
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = stringResource(
                        R.string.disaster_meta_restore_confirm,
                        hdr.volumeGuid,
                        hdr.formattedVolumeSize,
                        devicePath
                    ),
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showRestoreConfirmDialog = false
                        coroutineScope.launch {
                            isProcessing = true
                            statusMessage = null
                            try {
                                val ret = withContext(Dispatchers.IO) {
                                    NativeBridge.nativeRestoreFveMetadata(
                                        devicePath,
                                        partitionOffset,
                                        bytes,
                                        totalVolumeSize
                                    )
                                }
                                when (ret) {
                                    0 -> {
                                        statusMessage = context.getString(R.string.disaster_meta_restore_success)
                                        isErrorMessage = false
                                        Toast.makeText(context, statusMessage, Toast.LENGTH_LONG).show()
                                    }
                                    -101 -> {
                                        statusMessage = context.getString(
                                            R.string.disaster_meta_restore_size_mismatch,
                                            FveMetadataContainer.formatBytes(totalVolumeSize),
                                            hdr.formattedVolumeSize
                                        )
                                        isErrorMessage = true
                                    }
                                    else -> {
                                        val errStr = NativeBridge.nativeGetLastError().ifEmpty { "Error code $ret" }
                                        statusMessage = context.getString(R.string.disaster_meta_restore_failed, errStr)
                                        isErrorMessage = true
                                    }
                                }
                            } catch (e: Exception) {
                                statusMessage = context.getString(R.string.disaster_meta_restore_failed, e.localizedMessage ?: "Unknown error")
                                isErrorMessage = true
                            } finally {
                                isProcessing = false
                                pendingRestoreBytes = null
                                pendingRestoreHeader = null
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(text = stringResource(R.string.disaster_restore_meta))
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = {
                        showRestoreConfirmDialog = false
                        pendingRestoreBytes = null
                        pendingRestoreHeader = null
                    }
                ) {
                    Text(text = stringResource(R.string.cancel))
                }
            }
        )
    }
}
