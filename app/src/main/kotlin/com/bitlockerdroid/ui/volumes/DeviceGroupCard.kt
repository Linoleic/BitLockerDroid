package com.bitlockerdroid.ui.volumes

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bitlockerdroid.R
import com.bitlockerdroid.service.DetectedVolume
import com.bitlockerdroid.service.UnencryptedVolume
import com.bitlockerdroid.service.UnlockedVolume
import com.bitlockerdroid.service.VirtualStorageMountManager.VirtualMountInfo
import com.bitlockerdroid.share.LanShareState
import com.bitlockerdroid.util.DeviceIdentity

@Composable
fun DeviceGroupCard(
    group: DeviceVolumeGroup,
    isVirtualMountSupported: Boolean = false,
    canBiometric: Boolean = false,
    savedCredentialGuids: Set<String> = emptySet(),
    ejectingPaths: Set<String> = emptySet(),
    painters: VolumeCardPainters? = null,
    activeMounts: Map<String, VirtualMountInfo> = emptyMap(),
    shareStates: Map<String, LanShareState> = emptyMap(),
    isCompact: Boolean = false,
    onMountReadOnlyChange: (Boolean) -> Unit,
    onOpenVolume: (String) -> Unit,
    onLockVolume: (String) -> Unit,
    onUnlockDetected: (String) -> Unit,
    onBiometricUnlockDetected: ((String) -> Unit)? = null,
    onOpenUnencrypted: (UnencryptedVolume) -> Unit,
    onBenchmarkClick: (String) -> Unit,
    onLanShareClick: (UnlockedVolume) -> Unit,
    onRepairClick: (UnlockedVolume) -> Unit,
    onDiagnosticClick: (UnlockedVolume) -> Unit,
    onDisasterUnlockedClick: (UnlockedVolume) -> Unit,
    onDisasterDetectedClick: (DetectedVolume) -> Unit,
    onSmartHealthClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var isExpanded by remember { mutableStateOf(true) }
    val outlineColor = MaterialTheme.colorScheme.outlineVariant
    val context = androidx.compose.ui.platform.LocalContext.current

    var isSmartSupported by remember(group.physicalDiskPath) {
        mutableStateOf(com.bitlockerdroid.smart.SmartHealthManager.getCachedSupport(group.physicalDiskPath) ?: false)
    }

    LaunchedEffect(group.physicalDiskPath) {
        val supported = com.bitlockerdroid.smart.SmartHealthManager.isDeviceSmartSupported(
            context,
            group.physicalDiskPath,
            group.usbDeviceId
        )
        isSmartSupported = supported
    }

    val formattedCapacity = remember(group.totalCapacityBytes) {
        if (group.totalCapacityBytes > 0L) DeviceIdentity.formatSize(group.totalCapacityBytes) else null
    }

    val partitionCountText = if (group.totalVolumeCount <= 1) {
        stringResource(R.string.device_group_partition_single)
    } else {
        stringResource(R.string.device_group_partitions_count, group.totalVolumeCount)
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Physical Device Classification Header
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f), RoundedCornerShape(20.dp))
                .border(1.dp, outlineColor.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_storage_device),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = group.deviceName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            if (group.physicalDiskPath.isNotBlank() && !group.physicalDiskPath.startsWith("storage:")) {
                                Text(
                                    text = group.physicalDiskPath,
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp
                                    ),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "·",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                            if (formattedCapacity != null) {
                                Text(
                                    text = formattedCapacity,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "·",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                            Text(
                                text = partitionCountText,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    // S.M.A.R.T. Telemetry Action Button (Only shown on supported storage devices)
                    if (isSmartSupported) {
                        AppButton(
                            onClick = { onSmartHealthClick(group.physicalDiskPath) },
                            shape = RoundedCornerShape(10.dp),
                            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_smart_health),
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            if (!isCompact) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = stringResource(R.string.device_smart_telemetry_btn),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(4.dp))
                    }

                    // Expand / Collapse Chevron
                    AppIconButton(
                        onClick = { isExpanded = !isExpanded },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }

        // Child Partitions List
        AnimatedVisibility(visible = isExpanded) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // 1. Detected Locked BitLocker Volumes
                if (group.hasDetected) {
                    if (group.hasUnlocked || group.hasUnencrypted) {
                        SectionHeader(
                            title = stringResource(R.string.device_group_locked_header),
                            count = group.detectedVolumes.size,
                            isWarning = true
                        )
                    }
                    group.detectedVolumes.forEach { detected ->
                        key(detected.devicePath) {
                            val hasSavedCredential = remember(detected.guid, savedCredentialGuids) {
                                !detected.guid.isNullOrBlank() && detected.guid.lowercase() in savedCredentialGuids
                            }
                            val onUnlock = remember(detected.devicePath, onUnlockDetected) { { onUnlockDetected(detected.devicePath) } }
                            val onBiometricUnlock = remember(detected.devicePath, onBiometricUnlockDetected) {
                                if (onBiometricUnlockDetected != null) { { onBiometricUnlockDetected(detected.devicePath) } } else null
                            }
                            val onDisaster = remember(detected.devicePath) { { onDisasterDetectedClick(detected) } }

                            DetectedVolumeCard(
                                volume = detected,
                                hasSavedCredential = hasSavedCredential,
                                canBiometric = canBiometric,
                                painters = painters,
                                onMountReadOnlyChange = onMountReadOnlyChange,
                                onUnlock = onUnlock,
                                onBiometricUnlock = onBiometricUnlock,
                                onDisasterClick = onDisaster
                            )
                        }
                    }
                }

                // 2. Unlocked Volumes
                if (group.hasUnlocked) {
                    if (group.hasDetected || group.hasUnencrypted) {
                        SectionHeader(
                            title = stringResource(R.string.device_group_unlocked_header),
                            count = group.unlockedVolumes.size,
                            isWarning = false
                        )
                    }
                    group.unlockedVolumes.forEach { volume ->
                        key(volume.devicePath) {
                            val vMount = activeMounts[volume.devicePath]
                                ?: activeMounts.values.firstOrNull { !volume.guid.isNullOrBlank() && it.volumeGuid.equals(volume.guid, ignoreCase = true) }
                            val effectiveShareGuid = volume.guid ?: volume.devicePath
                            val shareState = shareStates[effectiveShareGuid]
                            val onOpen = remember(volume.devicePath, onOpenVolume) { { onOpenVolume(volume.devicePath) } }
                            val onLock = remember(volume.devicePath, onLockVolume) { { onLockVolume(volume.devicePath) } }
                            val onBenchmark = remember(volume.devicePath) { { onBenchmarkClick(volume.devicePath) } }
                            val onLanShare = remember(volume.devicePath) { { onLanShareClick(volume) } }
                            val onRepair = remember(volume.devicePath) { { onRepairClick(volume) } }
                            val onDiagnostic = remember(volume.devicePath) { { onDiagnosticClick(volume) } }
                            val onDisaster = remember(volume.devicePath) { { onDisasterUnlockedClick(volume) } }

                            UnlockedVolumeCard(
                                volume = volume,
                                vMount = vMount,
                                shareState = shareState,
                                painters = painters,
                                isVirtualMountSupported = isVirtualMountSupported,
                                onMountReadOnlyChange = onMountReadOnlyChange,
                                onOpen = onOpen,
                                onLock = onLock,
                                onBenchmarkClick = onBenchmark,
                                onLanShareClick = onLanShare,
                                onRepairClick = onRepair,
                                onDiagnosticClick = onDiagnostic,
                                onDisasterClick = onDisaster,
                                isEjecting = ejectingPaths.contains(volume.devicePath),
                                isCompact = isCompact
                            )
                        }
                    }
                }

                // 3. Unencrypted Volumes
                if (group.hasUnencrypted) {
                    if (group.hasDetected || group.hasUnlocked) {
                        SectionHeader(
                            title = stringResource(R.string.device_group_unencrypted_header),
                            count = group.unencryptedVolumes.size,
                            isWarning = false
                        )
                    }
                    group.unencryptedVolumes.forEach { unenc ->
                        key(unenc.id) {
                            val onOpen = remember(unenc.id, onOpenUnencrypted) { { onOpenUnencrypted(unenc) } }
                            UnencryptedVolumeCard(
                                volume = unenc,
                                onOpen = onOpen
                            )
                        }
                    }
                }
            }
        }
    }
}
