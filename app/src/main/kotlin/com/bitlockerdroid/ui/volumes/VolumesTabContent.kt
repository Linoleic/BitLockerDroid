package com.bitlockerdroid.ui.volumes

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.bitlockerdroid.R
import com.bitlockerdroid.service.DetectedVolume
import com.bitlockerdroid.service.UnencryptedVolume
import com.bitlockerdroid.service.UnlockedVolume
import com.bitlockerdroid.service.VirtualStorageMountManager

/** Content for Tab 0: Volumes list & detection */
@Composable
fun VolumesTabContent(
    unlockedVolumes: List<UnlockedVolume>,
    detectedVolumes: List<DetectedVolume>,
    unencryptedVolumes: List<UnencryptedVolume> = emptyList(),
    isRefreshing: Boolean,
    ejectingPaths: Set<String> = emptySet(),
    isVirtualMountSupported: Boolean = false,
    onMountReadOnlyChange: (Boolean) -> Unit,
    onRefreshAndScan: () -> Unit,
    onOpenVolume: (String) -> Unit,
    onOpenUnencrypted: (UnencryptedVolume) -> Unit = {},
    onLockVolume: (String) -> Unit,
    onUnlockDetected: (String) -> Unit,
    onBiometricUnlockDetected: ((String) -> Unit)? = null
) {
    val activeMounts by VirtualStorageMountManager.activeMountsFlow.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        AnimatedVisibility(visible = isRefreshing) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        val hasContent = unlockedVolumes.isNotEmpty() || detectedVolumes.isNotEmpty() || unencryptedVolumes.isNotEmpty()

        if (!hasContent) {
            EmptyStateView(
                isRefreshing = isRefreshing,
                onRefreshClick = onRefreshAndScan,
                modifier = Modifier.weight(1f)
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                flingBehavior = androidx.compose.foundation.gestures.ScrollableDefaults.flingBehavior(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Section 1: Detected Locked Volumes
                if (detectedVolumes.isNotEmpty()) {
                    item(key = "header_detected", contentType = "header") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .widthIn(max = 720.dp)
                        ) {
                            SectionHeader(
                                title = stringResource(R.string.detected_volumes_header),
                                count = detectedVolumes.size,
                                isWarning = true
                            )
                        }
                    }
                    items(
                        items = detectedVolumes,
                        key = { "detected_${it.devicePath}" },
                        contentType = { "detected_volume" }
                    ) { detected ->
                        val onUnlock = remember(detected.devicePath) { { onUnlockDetected(detected.devicePath) } }
                        val onBiometricUnlock = remember(detected.devicePath, onBiometricUnlockDetected) {
                            if (onBiometricUnlockDetected != null) { { onBiometricUnlockDetected(detected.devicePath) } } else null
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .widthIn(max = 720.dp)
                        ) {
                            DetectedVolumeCard(
                                volume = detected,
                                onMountReadOnlyChange = onMountReadOnlyChange,
                                onUnlock = onUnlock,
                                onBiometricUnlock = onBiometricUnlock
                            )
                        }
                    }
                }

                // Section 2: Unlocked Volumes
                if (unlockedVolumes.isNotEmpty()) {
                    item(key = "header_unlocked", contentType = "header") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .widthIn(max = 720.dp)
                        ) {
                            SectionHeader(
                                title = stringResource(R.string.unlocked_volumes_header),
                                count = unlockedVolumes.size,
                                isWarning = false
                            )
                        }
                    }
                    items(
                        items = unlockedVolumes,
                        key = { "unlocked_${it.devicePath}" },
                        contentType = { "unlocked_volume" }
                    ) { volume ->
                        val vMount = activeMounts[volume.devicePath]
                            ?: activeMounts.values.firstOrNull { !volume.guid.isNullOrBlank() && it.volumeGuid.equals(volume.guid, ignoreCase = true) }
                        val onOpen = remember(volume.devicePath) { { onOpenVolume(volume.devicePath) } }
                        val onLock = remember(volume.devicePath) { { onLockVolume(volume.devicePath) } }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .widthIn(max = 720.dp)
                        ) {
                            UnlockedVolumeCard(
                                volume = volume,
                                vMount = vMount,
                                isVirtualMountSupported = isVirtualMountSupported,
                                onMountReadOnlyChange = onMountReadOnlyChange,
                                onOpen = onOpen,
                                onLock = onLock,
                                isEjecting = ejectingPaths.contains(volume.devicePath)
                            )
                        }
                    }
                }

                // Section 3: Unencrypted Volumes
                if (unencryptedVolumes.isNotEmpty()) {
                    item(key = "header_unencrypted", contentType = "header") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .widthIn(max = 720.dp)
                        ) {
                            SectionHeader(
                                title = stringResource(R.string.unencrypted_volumes_header),
                                count = unencryptedVolumes.size,
                                isWarning = false
                            )
                        }
                    }
                    items(
                        items = unencryptedVolumes,
                        key = { "unencrypted_${it.id}" },
                        contentType = { "unencrypted_volume" }
                    ) { unenc ->
                        val onOpen = remember(unenc.id) { { onOpenUnencrypted(unenc) } }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .widthIn(max = 720.dp)
                        ) {
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
