package com.bitlockerdroid.ui.volumes

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.bitlockerdroid.R
import com.bitlockerdroid.service.DetectedVolume
import com.bitlockerdroid.service.UnlockedVolume

/** Content for Tab 0: Volumes list & detection */
@Composable
fun VolumesTabContent(
    unlockedVolumes: List<UnlockedVolume>,
    detectedVolumes: List<DetectedVolume>,
    isRefreshing: Boolean,
    onRefreshAndScan: () -> Unit,
    onOpenVolume: (String) -> Unit,
    onLockVolume: (String) -> Unit,
    onUnlockDetected: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        AnimatedVisibility(visible = isRefreshing) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        val hasContent = unlockedVolumes.isNotEmpty() || detectedVolumes.isNotEmpty()

        if (!hasContent) {
            EmptyStateView(
                isRefreshing = isRefreshing,
                onRefreshClick = onRefreshAndScan
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Section 1: Detected Locked Volumes
                if (detectedVolumes.isNotEmpty()) {
                    item {
                        SectionHeader(
                            title = stringResource(R.string.detected_volumes_header),
                            count = detectedVolumes.size,
                            isWarning = true
                        )
                    }
                    items(detectedVolumes) { detected ->
                        DetectedVolumeCard(
                            volume = detected,
                            onUnlock = { onUnlockDetected(detected.devicePath) }
                        )
                    }
                }

                // Section 2: Unlocked Volumes
                if (unlockedVolumes.isNotEmpty()) {
                    item {
                        SectionHeader(
                            title = stringResource(R.string.unlocked_volumes_header),
                            count = unlockedVolumes.size,
                            isWarning = false
                        )
                    }
                    items(unlockedVolumes) { volume ->
                        UnlockedVolumeCard(
                            volume = volume,
                            onOpen = { onOpenVolume(volume.devicePath) },
                            onLock = { onLockVolume(volume.devicePath) }
                        )
                    }
                }
            }
        }
    }
}
