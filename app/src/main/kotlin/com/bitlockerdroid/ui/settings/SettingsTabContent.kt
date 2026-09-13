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
import com.bitlockerdroid.ui.theme.SuccessGreen
import com.bitlockerdroid.util.PreferenceHelper
import com.bitlockerdroid.util.RootAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Content for Tab 1: Comprehensive standardized settings */
@Composable
fun SettingsTabContent(
    rememberedCredentials: List<PreferenceHelper.SavedCredential>,
    mountReadOnly: Boolean,
    onOpenCredentialsManager: () -> Unit,
    onMountReadOnlyChange: (Boolean) -> Unit,
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
        SettingsGroup(title = "设备与凭据") {
            val totalCount = rememberedCredentials.size
            val autoUnlockCount = rememberedCredentials.count { it.autoUnlock }
            val subtitleText = when {
                totalCount == 0 -> "暂无已记住密码的设备，解锁时勾选“记住密码”即可配置"
                autoUnlockCount == 0 -> "已保存 $totalCount 个设备凭据 · 均未开启自动解锁"
                autoUnlockCount == totalCount -> "已保存 $totalCount 个设备凭据 · 全部已开启自动解锁"
                else -> "已保存 $totalCount 个设备凭据 · $autoUnlockCount 个已开启自动解锁"
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
                description = "解锁后自动挂载至 /storage，应用可直接通过绝对路径访问",
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
                description = "在通知栏展示挂载状态，并提供一键「安全弹出」快捷操作",
                checked = notificationsEnabled,
                onCheckedChange = {
                    notificationsEnabled = it
                    PreferenceHelper.notificationsEnabled = it
                    BitLockerCoreService.updateForegroundState(context)
                }
            )
        }

        // Group 3: System Environment & Diagnostics with Real Root Solution Info
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

        // Group 4: About Footer
        SettingsGroup(title = "关于") {
            AboutSettingsCard()
        }

        Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
