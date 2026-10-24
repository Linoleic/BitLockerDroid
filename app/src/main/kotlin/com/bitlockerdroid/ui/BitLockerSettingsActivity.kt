package com.bitlockerdroid.ui

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.bitlockerdroid.R
import com.bitlockerdroid.provider.BitLockerDocumentsProvider
import com.bitlockerdroid.service.BitLockerDetector
import com.bitlockerdroid.service.DetectedVolume
import com.bitlockerdroid.service.SavedCredentialAccess
import com.bitlockerdroid.service.EjectStatus
import com.bitlockerdroid.service.UnencryptedVolume
import com.bitlockerdroid.service.UnlockManager
import com.bitlockerdroid.service.UnlockedVolume
import com.bitlockerdroid.service.VirtualStorageMountManager
import com.bitlockerdroid.ui.dialogs.ConnectionAuthorizationDialog
import com.bitlockerdroid.ui.dialogs.HardwareAccelerationDialog
import com.bitlockerdroid.ui.dialogs.PerformanceOptimizationDialog
import com.bitlockerdroid.ui.dialogs.SystemDiagnosticsDialog
import com.bitlockerdroid.ui.dialogs.SwitchModeConfirmDialog
import com.bitlockerdroid.ui.dialogs.CredentialsManagerDialog
import com.bitlockerdroid.ui.dialogs.LogViewerDialog
import com.bitlockerdroid.ui.dialogs.ShowPasswordDialog
import com.bitlockerdroid.ui.settings.SettingsTabContent
import com.bitlockerdroid.ui.theme.BitLockerTheme
import com.bitlockerdroid.ui.theme.ThemeMode
import com.bitlockerdroid.ui.volumes.VolumesTabContent
import com.bitlockerdroid.util.LogFile
import com.bitlockerdroid.util.DeviceIdentity
import com.bitlockerdroid.util.PreferenceHelper
import com.bitlockerdroid.util.RootAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Modern Jetpack Compose Material 3 Management and Settings Center.
 * Features:
 * - Unified Drive Management & Scanning (merged Refresh & Scan).
 * - Comprehensive Standardized Settings Tab (auto-unlock, credentials, read-only mode, system diagnostics).
 * - Live reactivity to device plug/unplug events.
 */
class BitLockerSettingsActivity : FragmentActivity() {

    private var unlockedVolumesState = mutableStateOf<List<UnlockedVolume>>(emptyList())
    private var detectedVolumesState = mutableStateOf<List<DetectedVolume>>(emptyList())
    private var unencryptedVolumesState = mutableStateOf<List<UnencryptedVolume>>(emptyList())
    private var isRefreshingState = mutableStateOf(false)
    private var ejectingPathsState = mutableStateOf<Set<String>>(emptySet())
    private var showLogDialogState = mutableStateOf(false)
    private var logContentState = mutableStateOf("")
    private var rememberedCredentialsState = mutableStateOf<List<PreferenceHelper.SavedCredential>>(emptyList())
    private var vaultProtectedState = mutableStateOf(false)

    private var mountReadOnlyState = mutableStateOf(false)
    private var useRootAccessState = mutableStateOf(true)
    private var useHardwareAesState = mutableStateOf(PreferenceHelper.useHardwareAes)
    private var useHardwareSha2State = mutableStateOf(PreferenceHelper.useHardwareSha2)
    private var useSafDoubleBufferState = mutableStateOf(PreferenceHelper.useSafDoubleBuffer)
    private var showHardwareAccelerationDialogState = mutableStateOf(false)
    private var virtualMountState = mutableStateOf(false)
    private var rootSolutionState = mutableStateOf(RootAccess.RootSolutionInfo(hasRoot = false, isDeviceRooted = false, solutionName = "", version = null, summary = ""))
    private var showSystemDiagnosticsDialogState = mutableStateOf(false)
    private var pendingRootSwitchTargetState = mutableStateOf<Boolean?>(null)
    private var isSwitchingModeProcessingState = mutableStateOf(false)

    private var lastScanTimestamp = 0L
    private var themeModeState = mutableStateOf(com.bitlockerdroid.ui.theme.ThemeMode.fromString(PreferenceHelper.themeMode))
    private var currentLanguageState = mutableStateOf(PreferenceHelper.appLanguage)

    // Stable action references to prevent recomposition leaks
    private val onThemeModeChangeAction: (ThemeMode) -> Unit = { newMode ->
        themeModeState.value = newMode
        PreferenceHelper.themeMode = newMode.name.lowercase()
    }
    private val onUseHardwareAesChangeAction: (Boolean) -> Unit = { enabled ->
        useHardwareAesState.value = enabled
        PreferenceHelper.useHardwareAes = enabled
        PreferenceHelper.useHardwareCrypto = enabled && useHardwareSha2State.value
    }
    private val onUseHardwareSha2ChangeAction: (Boolean) -> Unit = { enabled ->
        useHardwareSha2State.value = enabled
        PreferenceHelper.useHardwareSha2 = enabled
        PreferenceHelper.useHardwareCrypto = useHardwareAesState.value && enabled
    }
    private val onUseSafDoubleBufferChangeAction: (Boolean) -> Unit = { enabled ->
        useSafDoubleBufferState.value = enabled
        PreferenceHelper.useSafDoubleBuffer = enabled
    }
    private val onOpenHardwareAccelerationAction: () -> Unit = {
        showHardwareAccelerationDialogState.value = true
    }
    private val onCloseHardwareAccelerationAction: () -> Unit = {
        showHardwareAccelerationDialogState.value = false
    }
    private val onLanguageChangeAction: (String) -> Unit = { newLang ->
        currentLanguageState.value = newLang
        com.bitlockerdroid.util.LocaleHelper.applyLanguage(newLang)
        recreate()
    }
    private val onToggleAutoUnlockAction: (String, Boolean) -> Unit = { id, enabled ->
        PreferenceHelper.setAutoUnlockEnabled(this, id, enabled)
        refreshRememberedCredentials()
    }
    private val onMountReadOnlyChangeAction: (Boolean) -> Unit = { _ ->
        refreshData()
        BitLockerDocumentsProvider.notifyRootsChanged(this)
    }
    private val onUseRootAccessChangeAction: (Boolean) -> Unit = { target ->
        if (target != useRootAccessState.value) {
            pendingRootSwitchTargetState.value = target
        }
    }
    private val onConfirmSwitchModeAction: () -> Unit = {
        val target = pendingRootSwitchTargetState.value
        if (target != null) {
            isSwitchingModeProcessingState.value = true
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    UnlockManager.prepareForModeSwitch(target).getOrThrow()
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        isSwitchingModeProcessingState.value = false
                        Toast.makeText(this@BitLockerSettingsActivity,
                            getString(R.string.safe_eject_failed, e.message ?: ""), Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }
                PreferenceHelper.useRootAccess = target
                RootAccess.invalidateCache()
                withContext(Dispatchers.Main) {
                    com.bitlockerdroid.util.AppRestarter.restartApp(this@BitLockerSettingsActivity)
                }
            }
        }
    }
    private val onDismissSwitchModeAction: () -> Unit = {
        if (!isSwitchingModeProcessingState.value) {
            pendingRootSwitchTargetState.value = null
        }
    }
    private val onOpenSystemDiagnosticsAction: () -> Unit = { showSystemDiagnosticsDialogState.value = true }
    private val onCloseSystemDiagnosticsAction: () -> Unit = { showSystemDiagnosticsDialogState.value = false }
    private val onRefreshAndScanAction: () -> Unit = { refreshAndScan(showToast = true) }
    private val onOpenLogAction: () -> Unit = { openLogViewer() }
    private val onCloseLogAction: () -> Unit = { showLogDialogState.value = false }
    private val onClearLogAction: () -> Unit = { clearLogFile() }
    private val onDeleteCredentialAction: (String) -> Unit = { id ->
        PreferenceHelper.clearRememberedPassword(this, id)
        refreshRememberedCredentials()
        Toast.makeText(this, R.string.credential_cleared_toast, Toast.LENGTH_SHORT).show()
    }
    private val onClearAllCredentialsAction: () -> Unit = {
        PreferenceHelper.clearAllRememberedPasswords(this)
        refreshRememberedCredentials()
        Toast.makeText(this, R.string.settings_credentials_cleared, Toast.LENGTH_SHORT).show()
    }
    private val onOpenVolumeAction: (String) -> Unit = { path -> openVolumeInFiles(path) }
    private val onOpenUnencryptedAction: (UnencryptedVolume) -> Unit = { vol -> openUnencryptedVolumeInFiles(vol) }
    private val onLockVolumeAction: (String) -> Unit = { path -> lockVolume(path) }
    private val onUnlockDetectedAction: (String) -> Unit = { path -> promptUnlock(path) }
    private val onBiometricUnlockDetectedAction: (String) -> Unit = { path -> biometricUnlock(path) }
    private val onEjectVolumesAction: (List<String>) -> Unit = { paths -> ejectVolumes(paths) }
    private val onCloseVolumesAction: (String, List<String>) -> Unit = { path, volumePaths ->
        lifecycleScope.launch {
            val result = closeDeviceVolumesTracked(path, volumePaths)
            withContext(Dispatchers.Main) {
                if (result.isSuccess) {
                    val closed = result.getOrNull() ?: 0
                    Toast.makeText(
                        this@BitLockerSettingsActivity,
                        if (closed == 0) getString(R.string.volume_close_none) else getString(R.string.volume_close_success, closed),
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    Toast.makeText(
                        this@BitLockerSettingsActivity,
                        getString(R.string.volume_close_failed, result.exceptionOrNull()?.message ?: ""),
                        Toast.LENGTH_LONG
                    ).show()
                }
                refreshData()
            }
        }
    }
    private val onSmartCloseVolumesAction: suspend (String) -> Result<Int> = { path ->
        val result = closeDeviceVolumesTracked(path, listOf(path))
        withContext(Dispatchers.Main) { refreshData() }
        result
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(com.bitlockerdroid.util.LocaleHelper.wrapContext(newBase))
    }

    private val stateChangeListener = object : UnlockManager.StateChangeListener {
        override fun onUnlockManagerStateChanged() {
            refreshData()
            refreshRememberedCredentials()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Saved passwords are shown here — block screenshots / screen recording in release builds.
        val isDebuggable = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (!isDebuggable) {
            window.setFlags(
                android.view.WindowManager.LayoutParams.FLAG_SECURE,
                android.view.WindowManager.LayoutParams.FLAG_SECURE
            )
        }
        UnlockManager.addListener(stateChangeListener)

        // Request notification permission (Android 13+)
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1001)
            }
        }

        // Ensure Core Service is running
        try {
            startService(Intent(this, com.bitlockerdroid.service.BitLockerCoreService::class.java))
        } catch (_: Exception) {}

        PreferenceHelper.purgeLegacyNodeKeys(this)
        syncPreferences()

        setContent {
            val currentTheme by themeModeState
            val currentLang by currentLanguageState
            val unlockedVolumes by unlockedVolumesState
            val detectedVolumes by detectedVolumesState
            val unencryptedVolumes by unencryptedVolumesState
            val isRefreshing by isRefreshingState
            val showLogDialog by showLogDialogState
            val logContent by logContentState
            val rememberedCredentials by rememberedCredentialsState
            val vaultProtected by vaultProtectedState
            val ejectingPaths by ejectingPathsState
            val useRootAccess by useRootAccessState
            val useHardwareAes by useHardwareAesState
            val useHardwareSha2 by useHardwareSha2State
            val useSafDoubleBuffer by useSafDoubleBufferState
            val showHardwareAccelerationDialog by showHardwareAccelerationDialogState
            val rootSolution by rootSolutionState
            val showSystemDiagnosticsDialog by showSystemDiagnosticsDialogState
            val pendingRootSwitchTarget by pendingRootSwitchTargetState
            val isSwitchingModeProcessing by isSwitchingModeProcessingState

            BitLockerTheme(themeMode = currentTheme) {
                MainAppScreen(
                    initialTab = intent?.getIntExtra("tab", 0) ?: 0,
                    unlockedVolumes = unlockedVolumes,
                    detectedVolumes = detectedVolumes,
                    unencryptedVolumes = unencryptedVolumes,
                    isRefreshing = isRefreshing,
                    showLogDialog = showLogDialog,
                    logContent = logContent,
                    rememberedCredentials = rememberedCredentials,
                    vaultProtected = vaultProtected,
                    onHomeTabSelected = { vaultProtectedState.value = PreferenceHelper.isBiometricVaultEnabled(this@BitLockerSettingsActivity) },
                    themeMode = currentTheme,
                    currentLanguage = currentLang,
                    useRootAccess = useRootAccess,
                    useHardwareAes = useHardwareAes,
                    useHardwareSha2 = useHardwareSha2,
                    useSafDoubleBuffer = useSafDoubleBuffer,
                    showHardwareAccelerationDialog = showHardwareAccelerationDialog,
                    rootSolution = rootSolution,
                    onRefreshRootEnvironment = { refreshRootSolution(force = true) },
                    showSystemDiagnosticsDialog = showSystemDiagnosticsDialog,
                    onThemeModeChange = onThemeModeChangeAction,
                    onLanguageChange = onLanguageChangeAction,
                    onToggleAutoUnlock = onToggleAutoUnlockAction,
                    onMountReadOnlyChange = onMountReadOnlyChangeAction,
                    pendingRootSwitchTarget = pendingRootSwitchTarget,
                    isSwitchingModeProcessing = isSwitchingModeProcessing,
                    onUseRootAccessChange = onUseRootAccessChangeAction,
                    onUseHardwareAesChange = onUseHardwareAesChangeAction,
                    onUseHardwareSha2Change = onUseHardwareSha2ChangeAction,
                    onUseSafDoubleBufferChange = onUseSafDoubleBufferChangeAction,
                    onOpenHardwareAcceleration = onOpenHardwareAccelerationAction,
                    onCloseHardwareAcceleration = onCloseHardwareAccelerationAction,
                    onOpenSystemDiagnostics = onOpenSystemDiagnosticsAction,
                    onCloseSystemDiagnostics = onCloseSystemDiagnosticsAction,
                    onConfirmSwitchMode = onConfirmSwitchModeAction,
                    onDismissSwitchMode = onDismissSwitchModeAction,
                    onRefreshAndScan = onRefreshAndScanAction,
                    onOpenLog = onOpenLogAction,
                    onCloseLog = onCloseLogAction,
                    onClearLog = onClearLogAction,
                    onDeleteCredential = onDeleteCredentialAction,
                    onClearAllCredentials = onClearAllCredentialsAction,
                    onOpenVolume = onOpenVolumeAction,
                    onOpenUnencrypted = onOpenUnencryptedAction,
                    onLockVolume = onLockVolumeAction,
                    onUnlockDetected = onUnlockDetectedAction,
                    onBiometricUnlockDetected = onBiometricUnlockDetectedAction,
                    onEjectVolumes = onEjectVolumesAction,
                    onCloseVolumes = onCloseVolumesAction,
                    onSmartCloseVolumes = onSmartCloseVolumesAction,
                    ejectingPaths = ejectingPaths
                )
            }
        }
    }

    override fun onDestroy() {
        UnlockManager.removeListener(stateChangeListener)
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // Handled smoothly by Compose without Activity recreation or page reload
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            refreshAndScan(showToast = false)
        }
    }

    override fun onResume() {
        super.onResume()
        syncPreferences()
        refreshData()
        val now = System.currentTimeMillis()
        if (now - lastScanTimestamp >= RESUME_SCAN_THROTTLE_MS) {
            lastScanTimestamp = now
            refreshAndScan(showToast = false)
        }
    }

    private fun syncPreferences() {
        vaultProtectedState.value = PreferenceHelper.isBiometricVaultEnabled(this)
        mountReadOnlyState.value = PreferenceHelper.mountReadOnly
        useRootAccessState.value = PreferenceHelper.useRootAccess
        useHardwareAesState.value = PreferenceHelper.isUseHardwareAes(this)
        useHardwareSha2State.value = PreferenceHelper.isUseHardwareSha2(this)
        useSafDoubleBufferState.value = PreferenceHelper.isUseSafDoubleBuffer(this)
        virtualMountState.value = PreferenceHelper.virtualMountEnabled
        refreshRememberedCredentials()
        refreshRootSolution()
    }

    private fun refreshRootSolution(force: Boolean = false) {
        lifecycleScope.launch(Dispatchers.IO) {
            val sol = RootAccess.getRootSolution(force)
            withContext(Dispatchers.Main) {
                rootSolutionState.value = sol
            }
        }
    }

    private fun refreshRememberedCredentials() {
        lifecycleScope.launch(Dispatchers.IO) {
            val creds = PreferenceHelper.getRememberedCredentials(this@BitLockerSettingsActivity)
            withContext(Dispatchers.Main) {
                if (rememberedCredentialsState.value != creds) {
                    rememberedCredentialsState.value = creds
                }
            }
        }
    }

    private var refreshJob: kotlinx.coroutines.Job? = null
    private fun refreshData() {
        refreshJob?.cancel()
        refreshJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                if (!UnlockManager.isEmpty) {
                    kotlinx.coroutines.delay(60)
                }
                val currentUnlocked = UnlockManager.unlockedVolumes
                val currentDetected = UnlockManager.detectedVolumes
                val currentUnencrypted = UnlockManager.unencryptedVolumes
                withContext(Dispatchers.Main) {
                    if (unlockedVolumesState.value != currentUnlocked) {
                        unlockedVolumesState.value = currentUnlocked
                    }
                    if (detectedVolumesState.value != currentDetected) {
                        detectedVolumesState.value = currentDetected
                    }
                    if (unencryptedVolumesState.value != currentUnencrypted) {
                        unencryptedVolumesState.value = currentUnencrypted
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                LogFile.write("app", "refreshData: could not refresh volume snapshot: ${e.message}")
                withContext(Dispatchers.Main) {
                    // Fail closed: do not keep presenting a stale snapshot as
                    // a currently readable unlocked volume.
                    unlockedVolumesState.value = emptyList()
                }
            }
        }
    }

    /** Unified Refresh and Scan */
    private fun refreshAndScan(showToast: Boolean) {
        if (isRefreshingState.value) return
        isRefreshingState.value = true
        lastScanTimestamp = System.currentTimeMillis()
        if (showToast) {
            Toast.makeText(this, R.string.scanning_and_refreshing, Toast.LENGTH_SHORT).show()
        }

        lifecycleScope.launch(Dispatchers.IO) {
            var found = 0
            try {
                if (showToast) {
                    UnlockManager.clearManualLockSuppression()
                }
                com.bitlockerdroid.util.DeviceIdentity.clearCache()
                com.bitlockerdroid.smart.SmartHealthManager.clearCache()
                // FUSE daemon may have written to the volume behind our back:
                // drop every active session's block/FS caches so the refreshed
                // listing reflects the on-volume truth.
                UnlockManager.activeSessions.forEach { core ->
                    try { core.invalidateCache() } catch (_: Throwable) {}
                }
                // And tell the daemons to rebuild their view in turn (SAF→FUSE).
                VirtualStorageMountManager.notifyAllDataChanged()
                found = BitLockerDetector.scanAndDetect(this@BitLockerSettingsActivity)
                // If auto-unlock was triggered for any volume, wait up to 6s so UI immediately shows it
                UnlockManager.awaitPendingUnlocks(6000)
                LogFile.write("app", "manual refresh & scan result: found=$found")
            } catch (e: Throwable) {
                LogFile.write("app", "refreshAndScan error: ${e.message}")
            } finally {
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) {
                    isRefreshingState.value = false
                    refreshData()
                    refreshRememberedCredentials()
                    if (showToast) {
                        Toast.makeText(
                            this@BitLockerSettingsActivity,
                            getString(R.string.scan_and_refresh_done, found),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }
    }

    private fun promptUnlock(devicePath: String) {
        val detectedVol = UnlockManager.detectedVolumes.firstOrNull { it.devicePath == devicePath }
        val guid = detectedVol?.guid ?: BitLockerDetector.getVolumeGuid(devicePath)
        val recoveryKeyId = detectedVol?.recoveryKeyId ?: BitLockerDetector.getRecoveryKeyId(devicePath)
        LogFile.write("app", "manual unlock requested for $devicePath (guid=$guid, rkId=$recoveryKeyId)")
        UnlockManager.showUnlockDialog(this, devicePath, 0, guid, recoveryKeyId)
    }

    private fun biometricUnlock(devicePath: String) {
        val detectedVol = UnlockManager.detectedVolumes.firstOrNull { it.devicePath == devicePath }
        val guid = detectedVol?.guid ?: BitLockerDetector.getVolumeGuid(devicePath)
        if (guid.isNullOrBlank()) {
            promptUnlock(devicePath)
            return
        }
        val friendlyName = detectedVol?.deviceName?.ifBlank { guid } ?: guid

        val targetPath = devicePath
        val targetGuid = guid
        SavedCredentialAccess.readForUserAction(
            activity = this,
            volumeGuid = targetGuid,
            title = getString(R.string.biometric_unlock_prompt_title),
            subtitle = getString(R.string.biometric_unlock_prompt_subtitle, friendlyName),
            onSuccess = { raw ->
                val stillDetected = UnlockManager.detectedVolumes.any {
                    it.devicePath == targetPath && it.guid.equals(targetGuid, ignoreCase = true)
                }
                if (!stillDetected) return@readForUserAction
                val isRecovery = raw.startsWith(UnlockManager.RECOVERY_PREFIX)
                val cleanKey = if (isRecovery) raw.removePrefix(UnlockManager.RECOVERY_PREFIX) else raw
                Toast.makeText(this, R.string.biometric_unlocking, Toast.LENGTH_SHORT).show()
                lifecycleScope.launch(Dispatchers.IO) {
                    val result = UnlockManager.unlockWithCredential(
                        this@BitLockerSettingsActivity,
                        targetPath,
                        0L,
                        cleanKey,
                        isRecovery,
                        remember = null,
                        expectedGuid = targetGuid
                    )
                    withContext(Dispatchers.Main) {
                        // Successful publication removes the target from detectedVolumes.
                        // Validate the published session instead of suppressing success feedback.
                        val stillTarget = if (result.isSuccess) {
                            UnlockManager.get(targetPath) === result.getOrNull()
                        } else {
                            UnlockManager.detectedVolumes.any {
                                it.devicePath == targetPath && it.guid.equals(targetGuid, ignoreCase = true)
                            }
                        }
                        if (!stillTarget) return@withContext
                        if (result.isSuccess) {
                            Toast.makeText(this@BitLockerSettingsActivity, R.string.unlock_success, Toast.LENGTH_SHORT).show()
                            refreshData()
                        } else {
                            val err = result.exceptionOrNull()?.message ?: getString(R.string.unlock_failed)
                            Toast.makeText(this@BitLockerSettingsActivity, err, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            },
            onError = { err ->
                if (UnlockManager.detectedVolumes.any {
                        it.devicePath == targetPath && it.guid.equals(targetGuid, ignoreCase = true)
                    }) Toast.makeText(this, err, Toast.LENGTH_SHORT).show()
            }
        )
    }

    private fun lockVolume(devicePath: String) {
        if (ejectingPathsState.value.contains(devicePath)) return
        ejectingPathsState.value = ejectingPathsState.value + devicePath
        LogFile.write("app", "safe eject requested for $devicePath")
        lifecycleScope.launch(Dispatchers.IO) {
            val result = UnlockManager.safeEject(devicePath)
            withContext(Dispatchers.Main) {
                ejectingPathsState.value = ejectingPathsState.value - devicePath
                if (result.isSuccess) {
                    val ejectRes = result.getOrNull()
                    val label = ejectRes?.label ?: ""
                    val msg = when (ejectRes?.status) {
                        EjectStatus.DISK_SPUN_DOWN -> getString(R.string.safe_eject_success, label)
                        EjectStatus.VOLUME_CLOSED_DISK_BUSY -> getString(R.string.safe_eject_part_success, label)
                        EjectStatus.VOLUME_CLOSED_SPINDOWN_FAILED, null -> getString(R.string.safe_eject_spindown_warning, label)
                    }
                    Toast.makeText(
                        this@BitLockerSettingsActivity,
                        msg,
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    val err = result.exceptionOrNull()?.message ?: ""
                    Toast.makeText(
                        this@BitLockerSettingsActivity,
                        getString(R.string.safe_eject_failed, err),
                        Toast.LENGTH_LONG
                    ).show()
                }
                refreshData()
            }
        }
    }

    private suspend fun closeDeviceVolumesTracked(devicePath: String, affectedPaths: List<String>): Result<Int> {
        val parentDisk = runCatching { DeviceIdentity.resolveParentDiskNode(devicePath) }.getOrDefault(devicePath)
        val sameDiskVolumePaths = unlockedVolumesState.value.filter { volume ->
            runCatching { DeviceIdentity.resolveParentDiskNode(volume.devicePath) }.getOrDefault(volume.devicePath) == parentDisk
        }.map { it.devicePath }
        val paths = (affectedPaths + sameDiskVolumePaths + devicePath).filter { it.isNotBlank() }.toSet()
        val claimed = withContext(Dispatchers.Main) {
            if (paths.any { it in ejectingPathsState.value }) false
            else {
                ejectingPathsState.value = ejectingPathsState.value + paths
                true
            }
        }
        if (!claimed) return Result.failure(IllegalStateException(getString(R.string.volume_close_in_progress)))
        return try {
            withContext(Dispatchers.IO) { UnlockManager.closeDeviceVolumes(devicePath) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        } finally {
            withContext(NonCancellable + Dispatchers.Main) {
                ejectingPathsState.value = ejectingPathsState.value - paths
            }
        }
    }

    private fun ejectVolumes(paths: List<String>) {
        val targets = paths.filter { !ejectingPathsState.value.contains(it) }
        if (targets.isEmpty()) return
        ejectingPathsState.value = ejectingPathsState.value + targets
        LogFile.write("app", "safe eject requested for group: $targets")
        lifecycleScope.launch(Dispatchers.IO) {
            val results = mutableListOf<Pair<String, Result<com.bitlockerdroid.service.EjectResult>>>()
            for (path in targets) {
                val result = UnlockManager.safeEject(path)
                results.add(path to result)
                if (result.isFailure) break
            }
            withContext(Dispatchers.Main) {
                ejectingPathsState.value = ejectingPathsState.value - targets.toSet()
                val failures = results.filter { !it.second.isSuccess }
                if (failures.isEmpty()) {
                    if (targets.size == 1) {
                        val ejectRes = results.first().second.getOrNull()
                        val label = ejectRes?.label ?: ""
                        val msg = when (ejectRes?.status) {
                            EjectStatus.DISK_SPUN_DOWN -> getString(R.string.safe_eject_success, label)
                            EjectStatus.VOLUME_CLOSED_DISK_BUSY -> getString(R.string.safe_eject_part_success, label)
                            EjectStatus.VOLUME_CLOSED_SPINDOWN_FAILED, null -> getString(R.string.safe_eject_spindown_warning, label)
                        }
                        Toast.makeText(
                            this@BitLockerSettingsActivity,
                            msg,
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        Toast.makeText(
                            this@BitLockerSettingsActivity,
                            getString(R.string.notification_eject_finished),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                } else {
                    val err = failures.first().second.exceptionOrNull()?.message ?: ""
                    Toast.makeText(
                        this@BitLockerSettingsActivity,
                        getString(R.string.safe_eject_failed, err),
                        Toast.LENGTH_LONG
                    ).show()
                }
                refreshData()
            }
        }
    }

    private fun openVolumeInFiles(devicePath: String) {
        LogFile.write("app", "open requested for $devicePath")
        val core = UnlockManager.get(devicePath)
        if (core == null) {
            Toast.makeText(this, R.string.open_failed, Toast.LENGTH_SHORT).show()
            return
        }
        val serial = try { core.reader.volumeSerial() } catch (_: Exception) { 0L }
        try {
            val intent = BitLockerDocumentsProvider.createOpenVolumeIntent(this, devicePath, serial)
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(this, R.string.open_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun openUnencryptedVolumeInFiles(volume: UnencryptedVolume) {
        LogFile.write("app", "open unencrypted volume requested for ${volume.mountPath} (uuid=${volume.uuid})")
        val sm = getSystemService(Context.STORAGE_SERVICE) as? android.os.storage.StorageManager
        val vol = sm?.storageVolumes?.firstOrNull { it.uuid == volume.uuid }
        var launched = false
        if (vol != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val intent = vol.createOpenDocumentTreeIntent()
                startActivity(intent)
                launched = true
            } catch (e: Exception) {
                LogFile.write("app", "createOpenDocumentTreeIntent failed: ${e.message}")
            }
        }
        if (!launched && volume.uuid != null) {
            val rootUri = DocumentsContract.buildRootUri("com.android.externalstorage.documents", volume.uuid)
            val knownPackages = listOf("com.google.android.documentsui", "com.android.documentsui")
            for (pkg in knownPackages) {
                val candidateIntent = Intent(Intent.ACTION_VIEW).apply {
                    setPackage(pkg)
                    setDataAndType(rootUri, DocumentsContract.Root.MIME_TYPE_ITEM)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (candidateIntent.resolveActivity(packageManager) != null) {
                    startActivity(candidateIntent)
                    launched = true
                    break
                }
            }
            if (!launched) {
                try {
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(rootUri, DocumentsContract.Root.MIME_TYPE_ITEM)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(intent)
                    launched = true
                } catch (e: Exception) {
                    LogFile.write("app", "open Root URI failed: ${e.message}")
                }
            }
        }
        if (!launched) {
            Toast.makeText(this, R.string.open_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun formatLogForDisplay(rawText: String): String {
        if (rawText.isBlank()) return ""
        val lines = rawText.lines()
        val entries = ArrayList<String>()
        var currentEntry = StringBuilder()
        val headerRegex = Regex("""^\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}""")

        for (line in lines) {
            if (line.isEmpty()) continue
            if (headerRegex.containsMatchIn(line)) {
                if (currentEntry.isNotEmpty()) {
                    entries.add(currentEntry.toString().trimEnd())
                    currentEntry = StringBuilder()
                }
                currentEntry.append(line)
            } else {
                if (currentEntry.isNotEmpty()) {
                    currentEntry.append("\n").append(line)
                } else {
                    currentEntry.append(line)
                }
            }
        }
        if (currentEntry.isNotEmpty()) {
            entries.add(currentEntry.toString().trimEnd())
        }

        return entries.asReversed().joinToString("\n")
    }

    private fun openLogViewer() {
        lifecycleScope.launch(Dispatchers.IO) {
            val appFile = LogFile.appLogFile()
            val text = if (appFile != null && appFile.exists()) {
                try {
                    val raw = appFile.readText(Charsets.UTF_8)
                    formatLogForDisplay(raw)
                } catch (e: Exception) {
                    getString(R.string.read_log_failed, e.message ?: "")
                }
            } else {
                getString(R.string.no_app_log)
            }
            withContext(Dispatchers.Main) {
                logContentState.value = text
                showLogDialogState.value = true
            }
        }
    }

    private fun clearLogFile() {
        lifecycleScope.launch(Dispatchers.IO) {
            val appFile = LogFile.appLogFile()
            if (appFile != null && appFile.exists()) {
                try {
                    appFile.writeText("")
                } catch (_: Exception) {}
            }
            withContext(Dispatchers.Main) {
                logContentState.value = ""
                Toast.makeText(this@BitLockerSettingsActivity, R.string.log_cleared, Toast.LENGTH_SHORT).show()
            }
        }
    }

    companion object {
        private const val RESUME_SCAN_THROTTLE_MS = 3500L
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAppScreen(
    unlockedVolumes: List<UnlockedVolume>,
    detectedVolumes: List<DetectedVolume>,
    unencryptedVolumes: List<UnencryptedVolume> = emptyList(),
    isRefreshing: Boolean,
    showLogDialog: Boolean,
    logContent: String,
    rememberedCredentials: List<PreferenceHelper.SavedCredential>,
    vaultProtected: Boolean = false,
    onHomeTabSelected: () -> Unit = {},
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    currentLanguage: String = PreferenceHelper.LANG_SYSTEM,
    useRootAccess: Boolean = true,
    useHardwareAes: Boolean = true,
    useHardwareSha2: Boolean = true,
    useSafDoubleBuffer: Boolean = true,
    showHardwareAccelerationDialog: Boolean = false,
    showSystemDiagnosticsDialog: Boolean = false,
    rootSolution: RootAccess.RootSolutionInfo,
    onRefreshRootEnvironment: () -> Unit = {},
    onThemeModeChange: (ThemeMode) -> Unit = {},
    onLanguageChange: (String) -> Unit = {},
    onToggleAutoUnlock: (String, Boolean) -> Unit,
    onMountReadOnlyChange: (Boolean) -> Unit = {},
    pendingRootSwitchTarget: Boolean? = null,
    isSwitchingModeProcessing: Boolean = false,
    onUseRootAccessChange: (Boolean) -> Unit = {},
    onUseHardwareAesChange: (Boolean) -> Unit = {},
    onUseHardwareSha2Change: (Boolean) -> Unit = {},
    onUseSafDoubleBufferChange: (Boolean) -> Unit = {},
    onOpenHardwareAcceleration: () -> Unit = {},
    onCloseHardwareAcceleration: () -> Unit = {},
    onOpenSystemDiagnostics: () -> Unit = {},
    onCloseSystemDiagnostics: () -> Unit = {},
    onConfirmSwitchMode: () -> Unit = {},
    onDismissSwitchMode: () -> Unit = {},
    onRefreshAndScan: () -> Unit,
    onOpenLog: () -> Unit,
    onCloseLog: () -> Unit,
    onClearLog: () -> Unit,
    onDeleteCredential: (String) -> Unit,
    onClearAllCredentials: () -> Unit,
    onOpenVolume: (String) -> Unit,
    onOpenUnencrypted: (UnencryptedVolume) -> Unit = {},
    onLockVolume: (String) -> Unit,
    onUnlockDetected: (String) -> Unit,
    onBiometricUnlockDetected: ((String) -> Unit)? = null,
    onEjectVolumes: ((List<String>) -> Unit)? = null,
    onCloseVolumes: ((String, List<String>) -> Unit)? = null,
    onSmartCloseVolumes: suspend (String) -> Result<Int> = { Result.failure(IllegalStateException("Not available")) },
    ejectingPaths: Set<String> = emptySet(),
    initialTab: Int = 0
) {
    var selectedTab by rememberSaveable { mutableStateOf(initialTab) }
    var showCredentialsDialog by rememberSaveable { mutableStateOf(false) }
    var credentialForPasswordDialog by remember { mutableStateOf<PreferenceHelper.SavedCredential?>(null) }
    var showConnectionAuthorizationDialog by rememberSaveable { mutableStateOf(false) }
    val authorization = rememberAuthorizationStatus(useRootAccess)
    val compactHomeHeader = selectedTab == 0 && LocalConfiguration.current.screenWidthDp < 360
    val openConnectionAuthorization = {
        authorization.recheck()
        if (useRootAccess) onRefreshRootEnvironment()
        showConnectionAuthorizationDialog = true
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (!compactHomeHeader) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.primaryContainer,
                                modifier = Modifier.size(38.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = if (selectedTab == 0) Icons.Default.Lock else Icons.Default.Settings,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                        }
                        Column {
                            Text(
                                text = if (selectedTab == 0) stringResource(R.string.app_name) else stringResource(R.string.tab_settings),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (selectedTab == 0) {
                                Text(
                                    text = stringResource(R.string.app_subtitle),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                },
                actions = {
                    if (selectedTab == 0) {
                        AuthorizationIndicator(useRootAccess = useRootAccess, status = authorization,
                            onClick = openConnectionAuthorization)
                        // Unified Refresh & Scan Button
                        IconButton(onClick = onRefreshAndScan, enabled = !isRefreshing) {
                            if (isRefreshing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = stringResource(R.string.scan_and_refresh)
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface
            ) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = {
                        selectedTab = 0
                        onHomeTabSelected()
                    },
                    icon = {
                        Icon(
                            imageVector = Icons.Default.Home,
                            contentDescription = stringResource(R.string.tab_drives)
                        )
                    },
                    label = { Text(stringResource(R.string.tab_drives)) }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = stringResource(R.string.tab_settings)
                        )
                    },
                    label = { Text(stringResource(R.string.tab_settings)) }
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (selectedTab == 0) {
                val savedCredentialGuids = remember(rememberedCredentials) {
                    rememberedCredentials.mapNotNull { it.id.takeIf { id -> id.isNotBlank() }?.lowercase() }.toSet()
                }
                VolumesTabContent(
                    unlockedVolumes = unlockedVolumes,
                    detectedVolumes = detectedVolumes,
                    unencryptedVolumes = unencryptedVolumes,
                    isRefreshing = isRefreshing,
                    ejectingPaths = ejectingPaths,
                    isVirtualMountSupported = useRootAccess && (rootSolution.isDeviceRooted || RootAccess.cachedHasSu == true),
                    canBiometric = vaultProtected,
                    savedCredentialGuids = savedCredentialGuids,
                    onMountReadOnlyChange = onMountReadOnlyChange,
                    onRefreshAndScan = onRefreshAndScan,
                    onOpenVolume = onOpenVolume,
                    onOpenUnencrypted = onOpenUnencrypted,
                    onLockVolume = onLockVolume,
                    onUnlockDetected = onUnlockDetected,
                    onBiometricUnlockDetected = onBiometricUnlockDetected,
                    onEjectGroup = onEjectVolumes,
                    onCloseGroup = onCloseVolumes,
                    onSmartCloseVolumes = onSmartCloseVolumes
                )
            } else {
                SettingsTabContent(
                    rememberedCredentials = rememberedCredentials,
                    themeMode = themeMode,
                    currentLanguage = currentLanguage,
                    useHardwareAes = useHardwareAes,
                    useHardwareSha2 = useHardwareSha2,
                    useSafDoubleBuffer = useSafDoubleBuffer,
                    onOpenCredentialsManager = remember { { showCredentialsDialog = true } },
                    onOpenPerformanceOptimization = onOpenHardwareAcceleration,
                    onOpenHardwareAcceleration = onOpenHardwareAcceleration,
                    onOpenSystemDiagnostics = onOpenSystemDiagnostics,
                    onUseSafDoubleBufferChange = onUseSafDoubleBufferChange,
                    onThemeModeChange = onThemeModeChange,
                    onLanguageChange = onLanguageChange
                )
            }
        }

        // Canceling the safe-switch confirmation returns to the same panel.
        if (showConnectionAuthorizationDialog && pendingRootSwitchTarget == null) {
            ConnectionAuthorizationDialog(
                useRootAccess = useRootAccess,
                status = authorization,
                rootSolution = rootSolution,
                onRecheck = {
                    authorization.recheck()
                    if (useRootAccess) onRefreshRootEnvironment()
                },
                onUseRootAccessChange = onUseRootAccessChange,
                onDismiss = { showConnectionAuthorizationDialog = false }
            )
        }

        // Performance Optimization Dialog (Consolidated Hardware Acceleration, SAF Double-Buffer, USB Pipeline, Kernel I/O)
        if (showHardwareAccelerationDialog) {
            PerformanceOptimizationDialog(
                useHardwareAes = useHardwareAes,
                onUseHardwareAesChange = onUseHardwareAesChange,
                useHardwareSha2 = useHardwareSha2,
                onUseHardwareSha2Change = onUseHardwareSha2Change,
                useSafDoubleBuffer = useSafDoubleBuffer,
                onUseSafDoubleBufferChange = onUseSafDoubleBufferChange,
                onDismiss = onCloseHardwareAcceleration
            )
        }

        // System Diagnostics Dialog
        if (showSystemDiagnosticsDialog) {
            SystemDiagnosticsDialog(
                onOpenLog = onOpenLog,
                onDismiss = onCloseSystemDiagnostics
            )
        }

        // Mode Switch Confirmation & Restart Dialog
        pendingRootSwitchTarget?.let {
            SwitchModeConfirmDialog(
                activeVolumeCount = unlockedVolumes.size,
                isProcessing = isSwitchingModeProcessing,
                onConfirm = onConfirmSwitchMode,
                onDismiss = onDismissSwitchMode
            )
        }

        // Diagnostic Log Dialog
        if (showLogDialog) {
            LogViewerDialog(
                logContent = logContent,
                onClose = onCloseLog,
                onClear = onClearLog,
                onRefresh = onOpenLog
            )
        }

        // Credentials Management Dialog
        if (showCredentialsDialog) {
            CredentialsManagerDialog(
                credentials = rememberedCredentials,
                onToggleAutoUnlock = onToggleAutoUnlock,
                onShowPassword = { credentialForPasswordDialog = it },
                onDeleteCredential = onDeleteCredential,
                onClearAllCredentials = onClearAllCredentials,
                onDismiss = { showCredentialsDialog = false }
            )
        }

        // Show Saved Password Dialog
        credentialForPasswordDialog?.let { cred ->
            ShowPasswordDialog(
                credential = cred,
                onDismiss = { credentialForPasswordDialog = null }
            )
        }
    }
}
