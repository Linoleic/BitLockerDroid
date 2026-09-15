package com.bitlockerdroid.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.bitlockerdroid.R
import com.bitlockerdroid.service.BitLockerCoreService
import com.bitlockerdroid.service.UnlockManager
import com.bitlockerdroid.ui.dialogs.SingleChoiceDialog
import com.bitlockerdroid.ui.theme.SuccessGreen
import com.bitlockerdroid.ui.theme.ThemeMode
import com.bitlockerdroid.util.PreferenceHelper
import com.bitlockerdroid.util.RootAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Content for Tab 1: Comprehensive standardized settings */
@Composable
fun SettingsTabContent(
    rememberedCredentials: List<PreferenceHelper.SavedCredential>,
    mountReadOnly: Boolean,
    themeMode: ThemeMode,
    currentLanguage: String,
    onOpenCredentialsManager: () -> Unit,
    onMountReadOnlyChange: (Boolean) -> Unit,
    onThemeModeChange: (ThemeMode) -> Unit,
    onLanguageChange: (String) -> Unit,
    onOpenLog: () -> Unit
) {
    val rootSolution by produceState(
        initialValue = RootAccess.cachedRootSolution ?: RootAccess.RootSolutionInfo(
            hasRoot = RootAccess.cachedHasSu ?: false,
            solutionName = if (RootAccess.cachedHasSu == true) "已授权" else "未授权",
            version = null,
            summary = if (RootAccess.cachedHasSu == true) "已获取 Root 权限" else "未获取 Root 权限"
        )
    ) {
        value = withContext(Dispatchers.IO) { RootAccess.getRootSolution() }
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .widthIn(max = 720.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
        // Group 1: Device Credentials & Auto-Unlock Summary Entry
        SettingsGroup(title = stringResource(R.string.settings_header_unlock)) {
            val totalCount = rememberedCredentials.size
            val autoUnlockCount = rememberedCredentials.count { it.autoUnlock }
            val subtitleText = when {
                totalCount == 0 -> "暂无已记住密码的设备"
                autoUnlockCount == 0 -> "已保存 $totalCount 个设备凭据 · 未开启自动解锁"
                autoUnlockCount == totalCount -> "已保存 $totalCount 个设备凭据 · 全部开启自动解锁"
                else -> "已保存 $totalCount 个设备凭据 · $autoUnlockCount 个开启自动解锁"
            }
            SettingsClickableItem(
                title = "已记住的驱动器凭据",
                description = subtitleText,
                badgeText = if (totalCount > 0) "${totalCount}个设备" else "无凭据",
                badgeColor = if (totalCount > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                onClick = onOpenCredentialsManager
            )
        }

        // Group 2: Mount & Access Control
        SettingsGroup(title = stringResource(R.string.settings_header_mount)) {
            SettingsSwitchItem(
                title = stringResource(R.string.settings_mount_readonly),
                description = stringResource(R.string.settings_mount_readonly_desc),
                checked = mountReadOnly,
                onCheckedChange = onMountReadOnlyChange
            )
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
            )
            var virtualMount by remember { mutableStateOf(PreferenceHelper.virtualMountEnabled) }
            SettingsSwitchItem(
                title = "全局 POSIX 虚拟挂载",
                description = "挂载至 /storage，应用可直接通过绝对路径访问",
                checked = virtualMount,
                onCheckedChange = {
                    virtualMount = it
                    PreferenceHelper.virtualMountEnabled = it
                }
            )
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
            )
            var notificationsEnabled by remember { mutableStateOf(PreferenceHelper.notificationsEnabled) }
            val context = LocalContext.current
            SettingsSwitchItem(
                title = "显示挂载常驻通知",
                description = "在通知栏展示挂载状态及快捷操作",
                checked = notificationsEnabled,
                onCheckedChange = {
                    notificationsEnabled = it
                    PreferenceHelper.notificationsEnabled = it
                    BitLockerCoreService.updateForegroundState(context)
                }
            )
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
            )
            var suppressCorrupt by remember { mutableStateOf(PreferenceHelper.suppressCorruptNotification) }
            SettingsSwitchItem(
                title = stringResource(R.string.settings_suppress_corrupt_notification),
                description = stringResource(R.string.settings_suppress_corrupt_notification_desc),
                checked = suppressCorrupt,
                onCheckedChange = {
                    suppressCorrupt = it
                    PreferenceHelper.suppressCorruptNotification = it
                    if (it) {
                        Thread {
                            com.bitlockerdroid.service.StorageNotificationSuppressor.ensureListenerEnabled()
                            UnlockManager.detectedVolumes.forEach { v ->
                                com.bitlockerdroid.service.StorageNotificationSuppressor.suppressForVolume(context, v.devicePath)
                            }
                        }.start()
                    }
                }
            )
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
            )
            var hideSvi by remember { mutableStateOf(PreferenceHelper.hideSviFolder) }
            SettingsSwitchItem(
                title = stringResource(R.string.settings_hide_svi),
                description = stringResource(R.string.settings_hide_svi_desc),
                checked = hideSvi,
                onCheckedChange = {
                    hideSvi = it
                    PreferenceHelper.hideSviFolder = it
                    UnlockManager.activeSessions.forEach { s ->
                        s.invalidateCache()
                    }
                    com.bitlockerdroid.provider.BitLockerDocumentsProvider.notifyRootsChanged(context)
                }
            )
        }

        // Group 3: Personalization (Theme & Language)
        SettingsGroup(title = stringResource(R.string.settings_header_appearance)) {
            var showThemeDialog by remember { mutableStateOf(false) }
            var showLanguageDialog by remember { mutableStateOf(false) }

            val themeBadge = when (themeMode) {
                ThemeMode.SYSTEM -> stringResource(R.string.settings_theme_system)
                ThemeMode.LIGHT -> stringResource(R.string.settings_theme_light)
                ThemeMode.DARK -> stringResource(R.string.settings_theme_dark)
            }
            SettingsClickableItem(
                title = stringResource(R.string.settings_theme),
                description = stringResource(R.string.settings_theme_desc),
                badgeText = themeBadge,
                badgeColor = MaterialTheme.colorScheme.primary,
                onClick = { showThemeDialog = true }
            )

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
            )

            val langBadge = when (currentLanguage) {
                PreferenceHelper.LANG_ZH -> stringResource(R.string.settings_lang_zh)
                PreferenceHelper.LANG_EN -> stringResource(R.string.settings_lang_en)
                else -> stringResource(R.string.settings_lang_system)
            }
            SettingsClickableItem(
                title = stringResource(R.string.settings_language),
                description = stringResource(R.string.settings_language_desc),
                badgeText = langBadge,
                badgeColor = MaterialTheme.colorScheme.primary,
                onClick = { showLanguageDialog = true }
            )

            if (showThemeDialog) {
                SingleChoiceDialog(
                    title = stringResource(R.string.settings_theme),
                    options = listOf(
                        ThemeMode.SYSTEM to stringResource(R.string.settings_theme_system),
                        ThemeMode.LIGHT to stringResource(R.string.settings_theme_light),
                        ThemeMode.DARK to stringResource(R.string.settings_theme_dark)
                    ),
                    selected = themeMode,
                    onSelect = onThemeModeChange,
                    onDismiss = { showThemeDialog = false }
                )
            }

            if (showLanguageDialog) {
                SingleChoiceDialog(
                    title = stringResource(R.string.settings_language),
                    options = listOf(
                        PreferenceHelper.LANG_SYSTEM to stringResource(R.string.settings_lang_system),
                        PreferenceHelper.LANG_ZH to stringResource(R.string.settings_lang_zh),
                        PreferenceHelper.LANG_EN to stringResource(R.string.settings_lang_en)
                    ),
                    selected = currentLanguage,
                    onSelect = onLanguageChange,
                    onDismiss = { showLanguageDialog = false }
                )
            }
        }

        // Group 4: System Environment & Diagnostics with Real Root Solution Info
        SettingsGroup(title = stringResource(R.string.settings_header_diag)) {
            SettingsStatusItem(
                title = stringResource(R.string.settings_root_status),
                description = rootSolution.summary,
                badgeText = if (rootSolution.hasRoot) rootSolution.solutionName else "未授权",
                badgeColor = if (rootSolution.hasRoot) SuccessGreen else MaterialTheme.colorScheme.error
            )
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
            )
            SettingsClickableItem(
                title = stringResource(R.string.log_title),
                description = stringResource(R.string.settings_view_log_desc),
                onClick = onOpenLog
            )
        }

        // Group 5: About Footer
        SettingsGroup(title = stringResource(R.string.about_title)) {
            AboutSettingsCard()
        }

        Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
