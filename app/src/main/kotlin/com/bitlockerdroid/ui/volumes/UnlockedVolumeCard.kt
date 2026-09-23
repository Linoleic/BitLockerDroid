package com.bitlockerdroid.ui.volumes

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bitlockerdroid.R
import com.bitlockerdroid.service.UnlockedVolume
import com.bitlockerdroid.service.VirtualStorageMountManager
import com.bitlockerdroid.service.VirtualStorageMountManager.VirtualMountInfo
import com.bitlockerdroid.ui.dialogs.DisasterRecoveryDialog
import com.bitlockerdroid.ui.theme.SuccessGreen
import com.bitlockerdroid.ui.theme.WarningAmber
import com.bitlockerdroid.util.DeviceIdentity
import com.bitlockerdroid.util.PreferenceHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun UnlockedVolumeCard(
    volume: UnlockedVolume,
    vMount: VirtualMountInfo? = null,
    isVirtualMountSupported: Boolean = false,
    onMountReadOnlyChange: (Boolean) -> Unit,
    onOpen: () -> Unit,
    onLock: () -> Unit,
    isEjecting: Boolean = false
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var detailsExpanded by remember { mutableStateOf(false) }
    var showBenchmarkDialog by remember { mutableStateOf(false) }
    var showDisasterDialog by remember { mutableStateOf(false) }
    var showRepairConfirm by remember { mutableStateOf(false) }
    var showStandaloneDiagnostic by remember { mutableStateOf(false) }
    var isRepairing by remember { mutableStateOf(false) }
    var diagnosticResult by remember { mutableStateOf<com.bitlockerdroid.ntfs.VolumeDiagnostic?>(null) }
    var isDiagnosing by remember { mutableStateOf(false) }

    androidx.compose.runtime.LaunchedEffect(showRepairConfirm, showStandaloneDiagnostic) {
        if (showRepairConfirm || showStandaloneDiagnostic) {
            isDiagnosing = true
            diagnosticResult = withContext(Dispatchers.IO) {
                com.bitlockerdroid.service.UnlockManager.diagnoseVolume(volume.devicePath)
            }
            isDiagnosing = false
        }
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = CardDefaults.outlinedCardBorder().copy(
            brush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
        )
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = SuccessGreen.copy(alpha = 0.15f),
                    modifier = Modifier.size(44.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            tint = SuccessGreen,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(14.dp))

                val displayName = DeviceIdentity.getDisplayName(
                    label = volume.label,
                    deviceName = volume.deviceName,
                    guid = volume.guid
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    if (volume.deviceName.isNotBlank() && volume.deviceName != displayName) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = volume.deviceName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Mounted Badge
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = SuccessGreen.copy(alpha = 0.12f)
                ) {
                    Text(
                        text = stringResource(R.string.mounted),
                        style = MaterialTheme.typography.labelSmall,
                        color = SuccessGreen,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            // Full GUID Section with copy button
            if (!volume.guid.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(10.dp))
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.volume_guid),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.outline
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = volume.guid,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        IconButton(
                            onClick = {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                cm?.setPrimaryClip(ClipData.newPlainText("Volume GUID", volume.guid))
                                Toast.makeText(context, R.string.guid_copied, Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_content_copy),
                                contentDescription = stringResource(R.string.copy_guid),
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }

            // Recovery Key ID Section with copy button
            if (!volume.recoveryKeyId.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.recovery_key_id),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = volume.recoveryKeyId,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp
                                ),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        IconButton(
                            onClick = {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                cm?.setPrimaryClip(ClipData.newPlainText("Recovery Key ID", volume.recoveryKeyId))
                                Toast.makeText(context, R.string.recovery_id_copied, Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_content_copy),
                                contentDescription = stringResource(R.string.copy_recovery_id),
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }

            // POSIX Virtual Mount Section (Root Mode)
            if (vMount != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.posix_mount_path),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = vMount.mountPoint,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp
                                ),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        TextButton(
                            onClick = {
                                Thread {
                                    VirtualStorageMountManager.unmount(volume.devicePath)
                                    PreferenceHelper.setVolumeVirtualMountEnabled(context, volume.guid, volume.devicePath, false)
                                    (context as? android.app.Activity)?.runOnUiThread {
                                        Toast.makeText(context, R.string.unmount_success_toast, Toast.LENGTH_SHORT).show()
                                    }
                                }.start()
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                            modifier = Modifier.height(28.dp),
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                        ) {
                            Text(
                                text = stringResource(R.string.unmount_virtual_mount),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        IconButton(
                            onClick = {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                cm?.setPrimaryClip(ClipData.newPlainText("Mount Path", vMount.mountPoint))
                                Toast.makeText(context, R.string.mount_path_copied, Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_content_copy),
                                contentDescription = stringResource(R.string.copy_mount_path),
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            } else if (isVirtualMountSupported) {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        Thread {
                            val res = VirtualStorageMountManager.mountRemembered(context, volume.devicePath, volume.guid)
                            if (res.isSuccess) {
                                PreferenceHelper.setVolumeVirtualMountEnabled(context, volume.guid, volume.devicePath, true)
                            }
                            (context as? android.app.Activity)?.runOnUiThread {
                                if (res.isSuccess) {
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.mount_success_toast, res.getOrNull()?.mountPoint ?: ""),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                } else {
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.mount_failed_toast, res.exceptionOrNull()?.message ?: ""),
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                        }.start()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.virtual_mount_to_storage))
                }
            }

            // Drive capacity usage progress bar
            if (volume.size > 0L && volume.freeBytes >= 0L) {
                Spacer(modifier = Modifier.height(12.dp))
                val usedFraction = (volume.usedBytes.toFloat() / volume.size.toFloat()).coerceIn(0f, 1f)
                val usedPercent = (usedFraction * 100).toInt()
                val usedText = DeviceIdentity.formatSize(volume.usedBytes)
                val totalText = DeviceIdentity.formatSize(volume.size)
                val freeText = DeviceIdentity.formatSize(volume.freeBytes)

                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.storage_used, usedText, usedPercent),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = stringResource(R.string.storage_available, freeText, totalText),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { usedFraction },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = when {
                            usedFraction > 0.9f -> MaterialTheme.colorScheme.error
                            usedFraction > 0.75f -> WarningAmber
                            else -> SuccessGreen
                        },
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                        drawStopIndicator = {}
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Badges row: FileSystem, Cipher, Size, Writable
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (volume.fsType.isNotBlank()) {
                    MetaChip(text = volume.fsType)
                }
                if (volume.cipher.isNotBlank()) {
                    MetaChip(text = volume.cipher)
                }
                MetaChip(text = DeviceIdentity.formatSize(volume.size))
                MetaChip(
                    text = if (volume.canWrite) stringResource(R.string.writable) else stringResource(R.string.read_only),
                    color = if (volume.canWrite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                )
                if (volume.isDirty) {
                    MetaChip(
                        text = stringResource(R.string.dirty_volume_warning_chip),
                        color = WarningAmber
                    )
                }
            }

            // Dirty volume compact warning banner with repair option
            if (volume.isDirty) {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = WarningAmber.copy(alpha = 0.12f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, WarningAmber.copy(alpha = 0.35f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = null,
                                tint = WarningAmber,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.dirty_volume_compact_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        FilledTonalButton(
                            onClick = { showRepairConfirm = true },
                            enabled = !isRepairing,
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = WarningAmber.copy(alpha = 0.22f),
                                contentColor = WarningAmber
                            ),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.height(32.dp)
                        ) {
                            if (isRepairing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp,
                                    color = WarningAmber
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = stringResource(R.string.repair_dirty_in_progress),
                                    style = MaterialTheme.typography.labelSmall
                                )
                            } else {
                                Icon(
                                    painter = painterResource(R.drawable.ic_repair),
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = stringResource(R.string.repair_dirty_action),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Expandable Hardware & Volume Metadata Section
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                modifier = Modifier.fillMaxWidth(),
                onClick = { detailsExpanded = !detailsExpanded }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.hardware_details_title),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = if (detailsExpanded) stringResource(R.string.collapse) else stringResource(R.string.expand),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            AnimatedVisibility(visible = detailsExpanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // 1. 底层块设备节点路径
                            VolumeDetailRow(
                                label = stringResource(R.string.device_node),
                                value = volume.devicePath,
                                isMonospace = true,
                                onCopy = {
                                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                    cm?.setPrimaryClip(ClipData.newPlainText("Device Node", volume.devicePath))
                                    Toast.makeText(context, R.string.device_node_copied, Toast.LENGTH_SHORT).show()
                                }
                            )

                            // 2. 卷序列号
                            if (volume.volumeSerial != 0L) {
                                val hexSerial = if ((volume.volumeSerial ushr 32) != 0L) {
                                    "%016X".format(volume.volumeSerial)
                                } else {
                                    "%08X".format(volume.volumeSerial)
                                }
                                VolumeDetailRow(
                                    label = stringResource(R.string.volume_serial),
                                    value = "0x$hexSerial",
                                    isMonospace = true,
                                    onCopy = {
                                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                        cm?.setPrimaryClip(ClipData.newPlainText("Volume Serial", "0x$hexSerial"))
                                        Toast.makeText(context, R.string.volume_serial_copied, Toast.LENGTH_SHORT).show()
                                    }
                                )
                            }

                            // 3. 扇区大小与对齐情况
                            val sectorDesc = if (volume.sectorSize >= 4096) {
                                stringResource(R.string.sector_4kn_aligned, volume.sectorSize)
                            } else {
                                stringResource(R.string.sector_512e_aligned, volume.sectorSize)
                            }
                            VolumeDetailRow(
                                label = stringResource(R.string.sector_and_alignment),
                                value = sectorDesc,
                                isMonospace = false
                            )

                            // 4. 解锁方式
                            val unlockMethodDesc = if (volume.isRecovery) {
                                stringResource(R.string.method_recovery_key)
                            } else {
                                stringResource(R.string.method_user_password)
                            }
                            VolumeDetailRow(
                                label = stringResource(R.string.unlock_method),
                                value = unlockMethodDesc,
                                isMonospace = false
                            )

                            // 5. 系统挂载权限状态
                            val permissionDesc = if (volume.canWrite) {
                                stringResource(R.string.perm_read_write)
                            } else {
                                stringResource(R.string.perm_read_only)
                            }
                            VolumeDetailRow(
                                label = stringResource(R.string.mount_permission),
                                value = permissionDesc,
                                isMonospace = false
                            )

                            // 6. 文件系统健康度
                            val healthDesc = if (volume.isDirty) {
                                stringResource(R.string.health_dirty)
                            } else {
                                stringResource(R.string.health_clean)
                            }
                            VolumeDetailRow(
                                label = stringResource(R.string.volume_health),
                                value = healthDesc,
                                isMonospace = false
                            )

                            Spacer(modifier = Modifier.height(4.dp))
                            OutlinedButton(
                                onClick = { showStandaloneDiagnostic = true },
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth(),
                                contentPadding = PaddingValues(vertical = 6.dp)
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_repair),
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = stringResource(R.string.diagnostic_action_btn),
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }

                            Spacer(modifier = Modifier.height(6.dp))
                            OutlinedButton(
                                onClick = { showDisasterDialog = true },
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth(),
                                contentPadding = PaddingValues(vertical = 6.dp)
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_shield_check),
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = stringResource(R.string.disaster_recovery_title),
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Read-Only Access Mode Control (每个盘符独立控制)
            var isVolumeRo by remember(volume.devicePath, volume.guid) {
                mutableStateOf(PreferenceHelper.isVolumeReadOnly(context, volume.guid, volume.devicePath))
            }
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = if (isVolumeRo) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    if (isVolumeRo) MaterialTheme.colorScheme.error.copy(alpha = 0.4f)
                    else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.readonly_mode_title),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isVolumeRo) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = if (isVolumeRo) stringResource(R.string.readonly_mode_desc_on)
                                   else stringResource(R.string.readonly_mode_desc_off),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Switch(
                        checked = isVolumeRo,
                        onCheckedChange = { enabled ->
                            isVolumeRo = enabled
                            PreferenceHelper.setVolumeReadOnly(context, volume.guid, volume.devicePath, enabled)
                            onMountReadOnlyChange(enabled)
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Action Buttons
            val isCompact = LocalConfiguration.current.screenWidthDp < 480
            if (isCompact) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { showBenchmarkDialog = true },
                            enabled = !isEjecting,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = stringResource(R.string.benchmark_btn),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        OutlinedButton(
                            onClick = onLock,
                            enabled = !isEjecting,
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.primary
                            ),
                            modifier = Modifier.weight(1f)
                        ) {
                            if (isEjecting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = stringResource(R.string.safe_ejecting),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            } else {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_eject),
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = stringResource(R.string.safe_eject),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    Button(
                        onClick = onOpen,
                        enabled = !isEjecting,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = stringResource(R.string.open),
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = { showBenchmarkDialog = true },
                        enabled = !isEjecting,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(text = stringResource(R.string.benchmark_btn))
                    }

                    OutlinedButton(
                        onClick = onLock,
                        enabled = !isEjecting,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        if (isEjecting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(text = stringResource(R.string.safe_ejecting))
                        } else {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_eject),
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(text = stringResource(R.string.safe_eject))
                        }
                    }

                    Button(
                        onClick = onOpen,
                        enabled = !isEjecting,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(text = stringResource(R.string.open))
                    }
                }
            }
        }
    }

    if (showBenchmarkDialog) {
        com.bitlockerdroid.ui.dialogs.BenchmarkDialog(
            devicePath = volume.devicePath,
            onDismiss = { showBenchmarkDialog = false }
        )
    }

    if (showDisasterDialog) {
        val core = com.bitlockerdroid.service.UnlockManager.get(volume.devicePath)
        val handle = core?.handle ?: 0L
        val offset = core?.offset ?: 0L
        val displayName = if (volume.deviceName.isNotBlank()) {
            volume.deviceName
        } else {
            volume.label.ifBlank { stringResource(R.string.encrypted_storage_device) }
        }
        DisasterRecoveryDialog(
            volumeGuid = volume.guid ?: "",
            devicePath = volume.devicePath,
            partitionOffset = offset,
            totalVolumeSize = volume.size,
            volumeLabel = displayName,
            sessionHandle = handle,
            isUnlocked = true,
            onDismiss = { showDisasterDialog = false }
        )
    }

    if (showRepairConfirm) {
        val diag = diagnosticResult
        val hasErrors = diag?.hasStructuralErrors == true

        AlertDialog(
            onDismissRequest = { if (!isRepairing) showRepairConfirm = false },
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
                                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
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
                                border = androidx.compose.foundation.BorderStroke(1.dp, SuccessGreen.copy(alpha = 0.35f)),
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
                        showRepairConfirm = false
                        isRepairing = true
                        scope.launch(Dispatchers.IO) {
                            val res = com.bitlockerdroid.service.UnlockManager.repairDirtyVolume(volume.devicePath)
                            withContext(Dispatchers.Main) {
                                isRepairing = false
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
                    onClick = { showRepairConfirm = false },
                    enabled = !isRepairing
                ) {
                    Text(text = stringResource(R.string.cancel))
                }
            }
        )
    }

    if (showStandaloneDiagnostic) {
        val diag = diagnosticResult
        AlertDialog(
            onDismissRequest = { showStandaloneDiagnostic = false },
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
                TextButton(onClick = { showStandaloneDiagnostic = false }) {
                    Text(text = stringResource(android.R.string.ok))
                }
            }
        )
    }
}

@Composable
private fun VolumeDetailRow(
    label: String,
    value: String,
    isMonospace: Boolean = false,
    onCopy: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(8.dp))
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = value,
                style = if (isMonospace) {
                    MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                } else {
                    MaterialTheme.typography.bodySmall
                },
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
            if (onCopy != null) {
                Spacer(modifier = Modifier.width(4.dp))
                IconButton(
                    onClick = onCopy,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_content_copy),
                        contentDescription = stringResource(R.string.copy_field_format, label),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
    }
}
