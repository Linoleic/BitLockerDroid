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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.bitlockerdroid.R
import com.bitlockerdroid.provider.BitLockerDocumentsProvider
import com.bitlockerdroid.service.BitLockerDetector
import com.bitlockerdroid.service.DetectedVolume
import com.bitlockerdroid.service.KeyGuardService
import com.bitlockerdroid.service.UnlockManager
import com.bitlockerdroid.service.UnlockedVolume
import com.bitlockerdroid.service.VirtualStorageMountManager
import com.bitlockerdroid.ui.dialogs.AdvancedSettingsDialog
import com.bitlockerdroid.ui.dialogs.SwitchModeConfirmDialog
import com.bitlockerdroid.ui.dialogs.CredentialsManagerDialog
import com.bitlockerdroid.ui.dialogs.LogViewerDialog
import com.bitlockerdroid.ui.dialogs.ShowPasswordDialog
import com.bitlockerdroid.ui.settings.SettingsTabContent
import com.bitlockerdroid.ui.theme.BitLockerTheme
import com.bitlockerdroid.ui.theme.ThemeMode
import com.bitlockerdroid.ui.volumes.VolumesTabContent
import com.bitlockerdroid.util.BiometricAuthHelper
import com.bitlockerdroid.util.LogFile
import com.bitlockerdroid.util.PreferenceHelper
import com.bitlockerdroid.util.RootAccess
import kotlinx.coroutines.Dispatchers
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

    private var unlockedVolumesState = mutableStateListOf<UnlockedVolume>()
    private var detectedVolumesState = mutableStateListOf<DetectedVolume>()
    private var isRefreshingState = mutableStateOf(false)
    private var ejectingPathsState = mutableStateListOf<String>()
    private var showLogDialogState = mutableStateOf(false)
    private var logContentState = mutableStateOf("")
    private var rememberedCredentialsState = mutableStateListOf<PreferenceHelper.SavedCredential>()

    private var mountReadOnlyState = mutableStateOf(false)
    private var useRootAccessState = mutableStateOf(true)
    private var virtualMountState = mutableStateOf(false)
    private var suppressCorruptNotificationState = mutableStateOf(false)
    private var rootSolutionState = mutableStateOf(RootAccess.RootSolutionInfo(hasRoot = false, isDeviceRooted = false, solutionName = "", version = null, summary = ""))
    private var showAdvancedSettingsDialogState = mutableStateOf(false)
    private var pendingRootSwitchTargetState = mutableStateOf<Boolean?>(null)
    private var isSwitchingModeProcessingState = mutableStateOf(false)

    private var lastScanTimestamp = 0L
    private var themeModeState = mutableStateOf(com.bitlockerdroid.ui.theme.ThemeMode.fromString(PreferenceHelper.themeMode))
    private var currentLanguageState = mutableStateOf(PreferenceHelper.appLanguage)

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
            BitLockerTheme(themeMode = currentTheme) {
                MainAppScreen(
                    initialTab = intent?.getIntExtra("tab", 0) ?: 0,
                    unlockedVolumes = unlockedVolumesState,
                    detectedVolumes = detectedVolumesState,
                    isRefreshing = isRefreshingState.value,
                    showLogDialog = showLogDialogState.value,
                    logContent = logContentState.value,
                    rememberedCredentials = rememberedCredentialsState,
                    themeMode = currentTheme,
                    currentLanguage = currentLang,
                    useRootAccess = useRootAccessState.value,
                    rootSolution = rootSolutionState.value,
                    showAdvancedSettingsDialog = showAdvancedSettingsDialogState.value,
                    suppressCorruptNotification = suppressCorruptNotificationState.value,
                    onThemeModeChange = { newMode ->
                        themeModeState.value = newMode
                        PreferenceHelper.themeMode = newMode.name.lowercase()
                    },
                    onLanguageChange = { newLang ->
                        currentLanguageState.value = newLang
                        com.bitlockerdroid.util.LocaleHelper.applyLanguage(newLang)
                        recreate()
                    },
                    onToggleAutoUnlock = { id, enabled ->
                        PreferenceHelper.setAutoUnlockEnabled(this, id, enabled)
                        refreshRememberedCredentials()
                    },
                    onMountReadOnlyChange = { enabled ->
                        refreshData()
                        BitLockerDocumentsProvider.notifyRootsChanged(this)
                    },
                    pendingRootSwitchTarget = pendingRootSwitchTargetState.value,
                    isSwitchingModeProcessing = isSwitchingModeProcessingState.value,
                    onUseRootAccessChange = { target ->
                        if (target != useRootAccessState.value) {
                            pendingRootSwitchTargetState.value = target
                        }
                    },
                    onConfirmSwitchMode = {
                        val target = pendingRootSwitchTargetState.value ?: return@MainAppScreen
                        isSwitchingModeProcessingState.value = true
                        lifecycleScope.launch(Dispatchers.IO) {
                            try {
                                if (unlockedVolumesState.isNotEmpty()) {
                                    UnlockManager.safeEjectAll()
                                }
                                VirtualStorageMountManager.unmountAll()
                            } catch (_: Throwable) {}
                            PreferenceHelper.useRootAccess = target
                            RootAccess.invalidateCache()
                            withContext(Dispatchers.Main) {
                                com.bitlockerdroid.util.AppRestarter.restartApp(this@BitLockerSettingsActivity)
                            }
                        }
                    },
                    onDismissSwitchMode = {
                        if (!isSwitchingModeProcessingState.value) {
                            pendingRootSwitchTargetState.value = null
                        }
                    },
                    onSuppressCorruptNotificationChange = { enabled ->
                        suppressCorruptNotificationState.value = enabled
                        PreferenceHelper.suppressCorruptNotification = enabled
                    },
                    onOpenAdvancedSettings = { showAdvancedSettingsDialogState.value = true },
                    onCloseAdvancedSettings = { showAdvancedSettingsDialogState.value = false },
                    onRefreshAndScan = { refreshAndScan(showToast = true) },
                    onOpenLog = { openLogViewer() },
                    onCloseLog = { showLogDialogState.value = false },
                    onClearLog = { clearLogFile() },
                    onDeleteCredential = { id ->
                        PreferenceHelper.clearRememberedPassword(this, id)
                        refreshRememberedCredentials()
                        Toast.makeText(this, R.string.credential_cleared_toast, Toast.LENGTH_SHORT).show()
                    },
                    onClearAllCredentials = {
                        PreferenceHelper.clearAllRememberedPasswords(this)
                        refreshRememberedCredentials()
                        Toast.makeText(this, R.string.settings_credentials_cleared, Toast.LENGTH_SHORT).show()
                    },
                    onOpenVolume = { path -> openVolumeInFiles(path) },
                    onLockVolume = { path -> lockVolume(path) },
                    onUnlockDetected = { path -> promptUnlock(path) },
                    onBiometricUnlockDetected = { path -> biometricUnlock(path) },
                    ejectingPaths = ejectingPathsState.toSet()
                )
            }
        }
    }

    override fun onDestroy() {
        UnlockManager.removeListener(stateChangeListener)
        super.onDestroy()
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
        mountReadOnlyState.value = PreferenceHelper.mountReadOnly
        useRootAccessState.value = PreferenceHelper.useRootAccess
        virtualMountState.value = PreferenceHelper.virtualMountEnabled
        suppressCorruptNotificationState.value = PreferenceHelper.suppressCorruptNotification
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
                rememberedCredentialsState.clear()
                rememberedCredentialsState.addAll(creds)
            }
        }
    }

    private fun refreshData() {
        lifecycleScope.launch(Dispatchers.IO) {
            val currentUnlocked = UnlockManager.unlockedVolumes
            val currentDetected = UnlockManager.detectedVolumes
            withContext(Dispatchers.Main) {
                unlockedVolumesState.clear()
                unlockedVolumesState.addAll(currentUnlocked)
                detectedVolumesState.clear()
                detectedVolumesState.addAll(currentDetected)
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
        val rememberBlob = PreferenceHelper.getRememberedPassword(this, guid)
        if (rememberBlob == null) {
            promptUnlock(devicePath)
            return
        }
        val friendlyName = detectedVol?.deviceName?.ifBlank { guid } ?: guid

        BiometricAuthHelper.authenticate(
            activity = this,
            title = getString(R.string.biometric_unlock_prompt_title),
            subtitle = getString(R.string.biometric_unlock_prompt_subtitle, friendlyName),
            onSuccess = {
                val raw = KeyGuardService.decrypt(rememberBlob)
                if (raw == null) {
                    Toast.makeText(this, R.string.credential_corrupted, Toast.LENGTH_SHORT).show()
                    promptUnlock(devicePath)
                    return@authenticate
                }
                val isRecovery = raw.startsWith(UnlockManager.RECOVERY_PREFIX)
                val cleanKey = if (isRecovery) raw.removePrefix(UnlockManager.RECOVERY_PREFIX) else raw
                Toast.makeText(this, R.string.biometric_unlocking, Toast.LENGTH_SHORT).show()
                lifecycleScope.launch(Dispatchers.IO) {
                    val result = UnlockManager.unlockWithCredential(
                        this@BitLockerSettingsActivity,
                        devicePath,
                        0L,
                        cleanKey,
                        isRecovery,
                        remember = true,
                        expectedGuid = guid
                    )
                    withContext(Dispatchers.Main) {
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
                Toast.makeText(this, err, Toast.LENGTH_SHORT).show()
            }
        )
    }

    private fun lockVolume(devicePath: String) {
        if (ejectingPathsState.contains(devicePath)) return
        ejectingPathsState.add(devicePath)
        LogFile.write("app", "safe eject requested for $devicePath")
        lifecycleScope.launch(Dispatchers.IO) {
            val result = UnlockManager.safeEject(devicePath)
            withContext(Dispatchers.Main) {
                ejectingPathsState.remove(devicePath)
                if (result.isSuccess) {
                    val label = result.getOrNull() ?: ""
                    Toast.makeText(
                        this@BitLockerSettingsActivity,
                        getString(R.string.safe_eject_success, label),
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
    isRefreshing: Boolean,
    showLogDialog: Boolean,
    logContent: String,
    rememberedCredentials: List<PreferenceHelper.SavedCredential>,
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    currentLanguage: String = PreferenceHelper.LANG_SYSTEM,
    useRootAccess: Boolean = true,
    rootSolution: RootAccess.RootSolutionInfo,
    showAdvancedSettingsDialog: Boolean = false,
    suppressCorruptNotification: Boolean = true,
    onThemeModeChange: (ThemeMode) -> Unit = {},
    onLanguageChange: (String) -> Unit = {},
    onToggleAutoUnlock: (String, Boolean) -> Unit,
    onMountReadOnlyChange: (Boolean) -> Unit = {},
    pendingRootSwitchTarget: Boolean? = null,
    isSwitchingModeProcessing: Boolean = false,
    onUseRootAccessChange: (Boolean) -> Unit = {},
    onConfirmSwitchMode: () -> Unit = {},
    onDismissSwitchMode: () -> Unit = {},
    onSuppressCorruptNotificationChange: (Boolean) -> Unit = {},
    onOpenAdvancedSettings: () -> Unit = {},
    onCloseAdvancedSettings: () -> Unit = {},
    onRefreshAndScan: () -> Unit,
    onOpenLog: () -> Unit,
    onCloseLog: () -> Unit,
    onClearLog: () -> Unit,
    onDeleteCredential: (String) -> Unit,
    onClearAllCredentials: () -> Unit,
    onOpenVolume: (String) -> Unit,
    onLockVolume: (String) -> Unit,
    onUnlockDetected: (String) -> Unit,
    onBiometricUnlockDetected: ((String) -> Unit)? = null,
    ejectingPaths: Set<String> = emptySet(),
    initialTab: Int = 0
) {
    var selectedTab by remember { mutableStateOf(initialTab) }
    var showCredentialsDialog by remember { mutableStateOf(false) }
    var credentialForPasswordDialog by remember { mutableStateOf<PreferenceHelper.SavedCredential?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
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
                        Column {
                            Text(
                                text = if (selectedTab == 0) stringResource(R.string.app_name) else stringResource(R.string.tab_settings),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            if (selectedTab == 0) {
                                Text(
                                    text = stringResource(R.string.app_subtitle),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                },
                actions = {
                    if (selectedTab == 0) {
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
                    onClick = { selectedTab = 0 },
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
                VolumesTabContent(
                    unlockedVolumes = unlockedVolumes,
                    detectedVolumes = detectedVolumes,
                    isRefreshing = isRefreshing,
                    ejectingPaths = ejectingPaths,
                    isVirtualMountSupported = useRootAccess && (rootSolution.isDeviceRooted || RootAccess.cachedHasSu == true),
                    onMountReadOnlyChange = onMountReadOnlyChange,
                    onRefreshAndScan = onRefreshAndScan,
                    onOpenVolume = onOpenVolume,
                    onLockVolume = onLockVolume,
                    onUnlockDetected = onUnlockDetected,
                    onBiometricUnlockDetected = onBiometricUnlockDetected
                )
            } else {
                SettingsTabContent(
                    rememberedCredentials = rememberedCredentials,
                    themeMode = themeMode,
                    currentLanguage = currentLanguage,
                    useRootAccess = useRootAccess,
                    rootSolution = rootSolution,
                    onOpenCredentialsManager = { showCredentialsDialog = true },
                    onOpenAdvancedSettings = onOpenAdvancedSettings,
                    onThemeModeChange = onThemeModeChange,
                    onLanguageChange = onLanguageChange
                )
            }
        }

        // Advanced Settings Dialog
        if (showAdvancedSettingsDialog) {
            AdvancedSettingsDialog(
                rootSolution = rootSolution,
                useRootAccess = useRootAccess,
                onUseRootAccessChange = onUseRootAccessChange,
                suppressCorruptNotification = suppressCorruptNotification,
                onSuppressCorruptNotificationChange = onSuppressCorruptNotificationChange,
                onOpenLog = onOpenLog,
                onDismiss = onCloseAdvancedSettings
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
