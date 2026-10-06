package com.bitlockerdroid.ui.volumes

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalOverscrollConfiguration
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.bitlockerdroid.R
import com.bitlockerdroid.service.DetectedVolume
import com.bitlockerdroid.service.UnencryptedVolume
import com.bitlockerdroid.service.UnlockedVolume
import com.bitlockerdroid.service.VirtualStorageMountManager
import com.bitlockerdroid.ui.dialogs.BenchmarkDialog
import com.bitlockerdroid.ui.dialogs.DisasterRecoveryDialog
import com.bitlockerdroid.ui.dialogs.LanShareDialog
import com.bitlockerdroid.ui.dialogs.RepairConfirmDialog
import com.bitlockerdroid.ui.dialogs.SmartHealthDialog
import com.bitlockerdroid.ui.dialogs.StandaloneDiagnosticDialog

/** Content for Tab 0: Volumes list & detection */
@Composable
fun VolumesTabContent(
    unlockedVolumes: List<UnlockedVolume>,
    detectedVolumes: List<DetectedVolume>,
    unencryptedVolumes: List<UnencryptedVolume> = emptyList(),
    isRefreshing: Boolean,
    ejectingPaths: Set<String> = emptySet(),
    isVirtualMountSupported: Boolean = false,
    canBiometric: Boolean = false,
    savedCredentialGuids: Set<String> = emptySet(),
    onMountReadOnlyChange: (Boolean) -> Unit,
    onRefreshAndScan: () -> Unit,
    onOpenVolume: (String) -> Unit,
    onOpenUnencrypted: (UnencryptedVolume) -> Unit = {},
    onLockVolume: (String) -> Unit,
    onUnlockDetected: (String) -> Unit,
    onBiometricUnlockDetected: ((String) -> Unit)? = null
) {
    val activeMounts by VirtualStorageMountManager.activeMountsFlow.collectAsState()
    val shareStates by com.bitlockerdroid.share.LanShareManager.shareStates.collectAsState()

    var benchmarkTarget by remember { mutableStateOf<String?>(null) }
    var disasterUnlockedTarget by remember { mutableStateOf<UnlockedVolume?>(null) }
    var disasterDetectedTarget by remember { mutableStateOf<DetectedVolume?>(null) }
    var lanShareTarget by remember { mutableStateOf<UnlockedVolume?>(null) }
    var repairTarget by remember { mutableStateOf<UnlockedVolume?>(null) }
    var diagnosticTarget by remember { mutableStateOf<UnlockedVolume?>(null) }
    var smartHealthTarget by remember { mutableStateOf<String?>(null) }

    val isCompact = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp < 480
    val cardModifier = remember {
        Modifier
            .fillMaxWidth()
            .widthIn(max = 720.dp)
            .graphicsLayer { }
            .clearAndSetSemantics { }
    }

    val painters = rememberVolumeCardPainters()

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
            @OptIn(ExperimentalFoundationApi::class)
            CompositionLocalProvider(
                LocalOverscrollConfiguration provides null
            ) {
                val scrollState = rememberScrollState()
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(scrollState)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                // Section 1: Detected Locked Volumes
                if (detectedVolumes.isNotEmpty()) {
                    Box(modifier = cardModifier) {
                        SectionHeader(
                            title = stringResource(R.string.detected_volumes_header),
                            count = detectedVolumes.size,
                            isWarning = true
                        )
                    }
                    detectedVolumes.forEach { detected ->
                        androidx.compose.runtime.key(detected.devicePath) {
                            val hasSavedCredential = remember(detected.guid, savedCredentialGuids) {
                                !detected.guid.isNullOrBlank() && detected.guid.lowercase() in savedCredentialGuids
                            }
                            val onUnlock = remember(detected.devicePath, onUnlockDetected) { { onUnlockDetected(detected.devicePath) } }
                            val onBiometricUnlock = remember(detected.devicePath, onBiometricUnlockDetected) {
                                if (onBiometricUnlockDetected != null) { { onBiometricUnlockDetected(detected.devicePath) } } else null
                            }
                            val onDisaster = remember(detected.devicePath) { { disasterDetectedTarget = detected } }
                            val onSmartHealth = remember(detected.devicePath) { { smartHealthTarget = detected.devicePath } }
                            DetectedVolumeCard(
                                volume = detected,
                                hasSavedCredential = hasSavedCredential,
                                canBiometric = canBiometric,
                                painters = painters,
                                onMountReadOnlyChange = onMountReadOnlyChange,
                                onUnlock = onUnlock,
                                onBiometricUnlock = onBiometricUnlock,
                                onDisasterClick = onDisaster,
                                onSmartHealthClick = onSmartHealth,
                                modifier = cardModifier
                            )
                        }
                    }
                }

                // Section 2: Unlocked Volumes
                if (unlockedVolumes.isNotEmpty()) {
                    Box(modifier = cardModifier) {
                        SectionHeader(
                            title = stringResource(R.string.unlocked_volumes_header),
                            count = unlockedVolumes.size,
                            isWarning = false
                        )
                    }
                    unlockedVolumes.forEach { volume ->
                        androidx.compose.runtime.key(volume.devicePath) {
                            val vMount = activeMounts[volume.devicePath]
                                ?: activeMounts.values.firstOrNull { !volume.guid.isNullOrBlank() && it.volumeGuid.equals(volume.guid, ignoreCase = true) }
                            val effectiveShareGuid = volume.guid ?: volume.devicePath
                            val shareState = shareStates[effectiveShareGuid]
                            val onOpen = remember(volume.devicePath, onOpenVolume) { { onOpenVolume(volume.devicePath) } }
                            val onLock = remember(volume.devicePath, onLockVolume) { { onLockVolume(volume.devicePath) } }
                            val onBenchmark = remember(volume.devicePath) { { benchmarkTarget = volume.devicePath } }
                            val onLanShare = remember(volume.devicePath) { { lanShareTarget = volume } }
                            val onRepair = remember(volume.devicePath) { { repairTarget = volume } }
                            val onDiagnostic = remember(volume.devicePath) { { diagnosticTarget = volume } }
                            val onDisaster = remember(volume.devicePath) { { disasterUnlockedTarget = volume } }
                            val onSmartHealth = remember(volume.devicePath) { { smartHealthTarget = volume.devicePath } }

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
                                onSmartHealthClick = onSmartHealth,
                                isEjecting = ejectingPaths.contains(volume.devicePath),
                                isCompact = isCompact,
                                modifier = cardModifier
                            )
                        }
                    }
                }

                // Section 3: Unencrypted Volumes
                if (unencryptedVolumes.isNotEmpty()) {
                    Box(modifier = cardModifier) {
                        SectionHeader(
                            title = stringResource(R.string.unencrypted_volumes_header),
                            count = unencryptedVolumes.size,
                            isWarning = false
                        )
                    }
                    unencryptedVolumes.forEach { unenc ->
                        androidx.compose.runtime.key(unenc.id) {
                            val onOpen = remember(unenc.id, onOpenUnencrypted) { { onOpenUnencrypted(unenc) } }
                            UnencryptedVolumeCard(
                                volume = unenc,
                                onOpen = onOpen,
                                modifier = cardModifier
                            )
                        }
                    }
                }
            }
        }
    }
    }

    // Hoisted Dialogs
    benchmarkTarget?.let { path ->
        BenchmarkDialog(
            devicePath = path,
            onDismiss = { benchmarkTarget = null }
        )
    }

    disasterUnlockedTarget?.let { volume ->
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
            onDismiss = { disasterUnlockedTarget = null }
        )
    }

    disasterDetectedTarget?.let { volume ->
        val displayName = if (volume.deviceName.isNotBlank()) {
            volume.deviceName
        } else {
            stringResource(R.string.encrypted_storage_device)
        }
        DisasterRecoveryDialog(
            volumeGuid = volume.guid ?: "",
            devicePath = volume.devicePath,
            partitionOffset = 0L,
            totalVolumeSize = volume.capacity,
            volumeLabel = displayName,
            sessionHandle = 0L,
            isUnlocked = false,
            onDismiss = { disasterDetectedTarget = null }
        )
    }

    lanShareTarget?.let { volume ->
        LanShareDialog(
            volume = volume,
            onDismiss = { lanShareTarget = null }
        )
    }

    repairTarget?.let { volume ->
        RepairConfirmDialog(
            volume = volume,
            onDismiss = { repairTarget = null }
        )
    }

    diagnosticTarget?.let { volume ->
        StandaloneDiagnosticDialog(
            volume = volume,
            onDismiss = { diagnosticTarget = null }
        )
    }

    smartHealthTarget?.let { path ->
        SmartHealthDialog(
            devicePath = path,
            onDismiss = { smartHealthTarget = null }
        )
    }
}
