package com.bitlockerdroid.ui.volumes

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bitlockerdroid.R
import com.bitlockerdroid.service.UnlockedVolume
import com.bitlockerdroid.service.VirtualStorageMountManager
import com.bitlockerdroid.service.VirtualStorageMountManager.VirtualMountInfo
import com.bitlockerdroid.ui.theme.SuccessGreen
import com.bitlockerdroid.ui.theme.WarningAmber
import com.bitlockerdroid.util.DeviceIdentity
import com.bitlockerdroid.util.PreferenceHelper

private data class CapacityStats(
    val fraction: Float,
    val percent: Int,
    val usedText: String,
    val totalText: String,
    val freeText: String
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun UnlockedVolumeCard(
    volume: UnlockedVolume,
    vMount: VirtualMountInfo? = null,
    shareState: com.bitlockerdroid.share.LanShareState? = null,
    isVirtualMountSupported: Boolean = false,
    onMountReadOnlyChange: (Boolean) -> Unit,
    onOpen: () -> Unit,
    onLock: () -> Unit,
    onBenchmarkClick: () -> Unit,
    onLanShareClick: () -> Unit,
    onRepairClick: () -> Unit,
    onDiagnosticClick: () -> Unit,
    onDisasterClick: () -> Unit,
    isEjecting: Boolean = false,
    isCompact: Boolean = false,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    var detailsExpanded by remember { mutableStateOf(false) }

    val copyPainter = painterResource(id = R.drawable.ic_content_copy)
    val ejectPainter = painterResource(id = R.drawable.ic_eject)
    val lanSharePainter = painterResource(id = R.drawable.ic_lan_share)
    val repairPainter = painterResource(id = R.drawable.ic_repair)
    val shieldCheckPainter = painterResource(id = R.drawable.ic_shield_check)

    val outlineColor = MaterialTheme.colorScheme.outlineVariant
    val cardBorder = remember(outlineColor) {
        BorderStroke(1.dp, outlineColor.copy(alpha = 0.45f))
    }

    val displayName = remember(volume.label, volume.deviceName, volume.guid) {
        DeviceIdentity.getDisplayName(
            label = volume.label,
            deviceName = volume.deviceName,
            guid = volume.guid
        )
    }

    val formattedTotalSize = remember(volume.size) { DeviceIdentity.formatSize(volume.size) }
    val capacityStats = remember(volume.usedBytes, volume.size, volume.freeBytes) {
        if (volume.size > 0L && volume.freeBytes >= 0L) {
            val usedFraction = (volume.usedBytes.toFloat() / volume.size.toFloat()).coerceIn(0f, 1f)
            val usedPercent = (usedFraction * 100).toInt()
            val usedText = DeviceIdentity.formatSize(volume.usedBytes)
            val totalText = DeviceIdentity.formatSize(volume.size)
            val freeText = DeviceIdentity.formatSize(volume.freeBytes)
            CapacityStats(usedFraction, usedPercent, usedText, totalText, freeText)
        } else null
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = cardBorder
    ) {
        Column(
            modifier = Modifier.padding(18.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .background(SuccessGreen.copy(alpha = 0.15f), RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = SuccessGreen,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Spacer(modifier = Modifier.width(14.dp))

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
                Text(
                    text = stringResource(R.string.mounted),
                    style = MaterialTheme.typography.labelSmall,
                    color = SuccessGreen,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .background(SuccessGreen.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }

            // Full GUID Section with copy button
            if (!volume.guid.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(10.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
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
                                clipboardManager.setText(AnnotatedString(volume.guid))
                                Toast.makeText(context, R.string.guid_copied, Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                painter = copyPainter,
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
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
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
                                clipboardManager.setText(AnnotatedString(volume.recoveryKeyId))
                                Toast.makeText(context, R.string.recovery_id_copied, Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                painter = copyPainter,
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
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
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
                                clipboardManager.setText(AnnotatedString(vMount.mountPoint))
                                Toast.makeText(context, R.string.mount_path_copied, Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                painter = copyPainter,
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

            // LAN Wireless Sharing Section (Card-level, no need to expand)
            val effectiveShareGuid = volume.guid ?: volume.devicePath
            val isVolumeSharing = shareState?.isRunning == true

            Spacer(modifier = Modifier.height(8.dp))
            if (isVolumeSharing) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.lan_share_title),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = shareState.primaryUrl,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp
                                ),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        TextButton(
                            onClick = onLanShareClick,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                            modifier = Modifier.height(28.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.lan_share_manage_action),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        TextButton(
                            onClick = {
                                com.bitlockerdroid.share.LanShareManager.stopSharing(context, effectiveShareGuid)
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                            modifier = Modifier.height(28.dp),
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                        ) {
                            Text(
                                text = stringResource(R.string.lan_share_stop_action),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        IconButton(
                            onClick = {
                                clipboardManager.setText(AnnotatedString(shareState.primaryUrl))
                                Toast.makeText(context, R.string.lan_share_copied, Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                painter = copyPainter,
                                contentDescription = stringResource(R.string.lan_share_copy_url),
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            } else {
                OutlinedButton(
                    onClick = onLanShareClick,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(
                        painter = lanSharePainter,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.lan_share_title))
                }
            }

            // Drive capacity usage progress bar
            if (capacityStats != null) {
                Spacer(modifier = Modifier.height(12.dp))
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.storage_used, capacityStats.usedText, capacityStats.percent),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = stringResource(R.string.storage_available, capacityStats.freeText, capacityStats.totalText),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    VolumeCapacityBar(
                        progress = capacityStats.fraction,
                        color = when {
                            capacityStats.fraction > 0.9f -> MaterialTheme.colorScheme.error
                            capacityStats.fraction > 0.75f -> WarningAmber
                            else -> SuccessGreen
                        },
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Badges row: FileSystem, Cipher, Size, Writable
            if (!isCompact) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (volume.fsType.isNotBlank()) {
                        MetaChip(text = volume.fsType)
                    }
                    if (volume.cipher.isNotBlank()) {
                        MetaChip(text = volume.cipher)
                    }
                    MetaChip(text = formattedTotalSize)
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
            } else {
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
                    MetaChip(text = formattedTotalSize)
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
            }

            // Dirty volume compact warning banner with repair option
            if (volume.isDirty) {
                Spacer(modifier = Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(WarningAmber.copy(alpha = 0.12f), RoundedCornerShape(10.dp))
                        .border(1.dp, WarningAmber.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
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
                            onClick = onRepairClick,
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = WarningAmber.copy(alpha = 0.22f),
                                contentColor = WarningAmber
                            ),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Icon(
                                painter = repairPainter,
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

            Spacer(modifier = Modifier.height(14.dp))

            // Expandable Hardware & Volume Metadata Section
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
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

            if (detailsExpanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f), RoundedCornerShape(10.dp))
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
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
                                    clipboardManager.setText(AnnotatedString(volume.devicePath))
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
                                        clipboardManager.setText(AnnotatedString("0x$hexSerial"))
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
                                onClick = onDiagnosticClick,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth(),
                                contentPadding = PaddingValues(vertical = 6.dp)
                            ) {
                                Icon(
                                    painter = repairPainter,
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
                                onClick = onDisasterClick,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth(),
                                contentPadding = PaddingValues(vertical = 6.dp)
                            ) {
                                Icon(
                                    painter = shieldCheckPainter,
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
            var isVolumeRo by remember(volume.devicePath, volume.canWrite) {
                mutableStateOf(!volume.canWrite)
            }
            val roBgColor = if (isVolumeRo) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
            val roBorderColor = if (isVolumeRo) MaterialTheme.colorScheme.error.copy(alpha = 0.4f)
                                else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(roBgColor, RoundedCornerShape(12.dp))
                    .border(1.dp, roBorderColor, RoundedCornerShape(12.dp))
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
                            onClick = onBenchmarkClick,
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
                                    painter = ejectPainter,
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
                        onClick = onBenchmarkClick,
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
                                painter = ejectPainter,
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
                overflow = TextOverflow.Ellipsis
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
