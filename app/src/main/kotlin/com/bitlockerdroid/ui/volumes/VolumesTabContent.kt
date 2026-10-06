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
                val deviceGroups = remember(unlockedVolumes, detectedVolumes, unencryptedVolumes) {
                    DeviceGroupBuilder.buildGroups(unlockedVolumes, detectedVolumes, unencryptedVolumes)
                }

                deviceGroups.forEach { group ->
                    androidx.compose.runtime.key(group.deviceKey) {
                        DeviceGroupCard(
                            group = group,
                            isVirtualMountSupported = isVirtualMountSupported,
                            canBiometric = canBiometric,
                            savedCredentialGuids = savedCredentialGuids,
                            ejectingPaths = ejectingPaths,
                            painters = painters,
                            activeMounts = activeMounts,
                            shareStates = shareStates,
                            isCompact = isCompact,
                            onMountReadOnlyChange = onMountReadOnlyChange,
                            onOpenVolume = onOpenVolume,
                            onLockVolume = onLockVolume,
                            onUnlockDetected = onUnlockDetected,
                            onBiometricUnlockDetected = onBiometricUnlockDetected,
                            onOpenUnencrypted = onOpenUnencrypted,
                            onBenchmarkClick = { path -> benchmarkTarget = path },
                            onLanShareClick = { vol -> lanShareTarget = vol },
                            onRepairClick = { vol -> repairTarget = vol },
                            onDiagnosticClick = { vol -> diagnosticTarget = vol },
                            onDisasterUnlockedClick = { vol -> disasterUnlockedTarget = vol },
                            onDisasterDetectedClick = { det -> disasterDetectedTarget = det },
                            onSmartHealthClick = { diskPath -> smartHealthTarget = diskPath },
                            modifier = cardModifier
                        )
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
