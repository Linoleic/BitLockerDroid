package com.bitlockerdroid.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import android.widget.Toast
import com.bitlockerdroid.R
import com.bitlockerdroid.service.BitLockerCoreService
import com.bitlockerdroid.service.UnlockManager
import com.bitlockerdroid.ui.dialogs.SingleChoiceDialog
import com.bitlockerdroid.ui.theme.SuccessGreen
import com.bitlockerdroid.ui.theme.ThemeMode
import com.bitlockerdroid.util.BiometricAuthHelper
import com.bitlockerdroid.util.PreferenceHelper
import com.bitlockerdroid.util.RootAccess
import com.bitlockerdroid.util.findFragmentActivity

/** Content for Tab 1: Comprehensive standardized settings */
@Composable
fun SettingsTabContent(
    rememberedCredentials: List<PreferenceHelper.SavedCredential>,
    themeMode: ThemeMode,
    currentLanguage: String,
    useRootAccess: Boolean,
    rootSolution: RootAccess.RootSolutionInfo,
    onOpenCredentialsManager: () -> Unit,
    onOpenAdvancedSettings: () -> Unit,
    onThemeModeChange: (ThemeMode) -> Unit,
    onLanguageChange: (String) -> Unit
) {
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
            // Group 1: Device Credentials & Biometric Protection
            SettingsGroup(title = stringResource(R.string.settings_header_unlock)) {
                val context = LocalContext.current
                val totalCount = rememberedCredentials.size
                val autoUnlockCount = rememberedCredentials.count { it.autoUnlock }
                val subtitleText = when {
                    totalCount == 0 -> stringResource(R.string.settings_creds_desc_none)
                    autoUnlockCount == 0 -> stringResource(R.string.settings_creds_desc_no_auto, totalCount)
                    autoUnlockCount == totalCount -> stringResource(R.string.settings_creds_desc_all_auto, totalCount)
                    else -> stringResource(R.string.settings_creds_desc_partial_auto, totalCount, autoUnlockCount)
                }
                SettingsClickableItem(
                    title = stringResource(R.string.settings_creds_item_title),
                    description = subtitleText,
                    badgeText = if (totalCount > 0) stringResource(R.string.settings_creds_badge_devices, totalCount) else stringResource(R.string.settings_creds_badge_empty),
                    badgeColor = if (totalCount > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    onClick = onOpenCredentialsManager
                )

                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                )

                var biometricVaultEnabled by remember {
                    mutableStateOf(PreferenceHelper.isBiometricVaultEnabled(context))
                }
                SettingsSwitchItem(
                    title = stringResource(R.string.biometric_protection_switch),
                    description = stringResource(R.string.biometric_protection_switch_desc),
                    checked = biometricVaultEnabled,
                    onCheckedChange = { targetChecked ->
                        val activity = context.findFragmentActivity()
                        if (activity != null && BiometricAuthHelper.canAuthenticate(activity)) {
                            BiometricAuthHelper.authenticate(
                                activity = activity,
                                title = context.getString(R.string.auth_title),
                                subtitle = context.getString(R.string.biometric_auth_for_vault_toggle),
                                onSuccess = {
                                    biometricVaultEnabled = targetChecked
                                    PreferenceHelper.setBiometricVaultEnabled(context, targetChecked)
                                    Toast.makeText(
                                        context,
                                        R.string.biometric_vault_settings_changed,
                                        Toast.LENGTH_SHORT
                                    ).show()
                                },
                                onError = { err ->
                                    Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
                                }
                            )
                        } else {
                            Toast.makeText(
                                context,
                                R.string.biometric_not_supported,
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                )
            }

            // Group 2: General & Mount Protection
            SettingsGroup(title = stringResource(R.string.settings_header_mount)) {
                val context = LocalContext.current
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
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                )
                var notificationsEnabled by remember { mutableStateOf(PreferenceHelper.notificationsEnabled) }
                SettingsSwitchItem(
                    title = stringResource(R.string.settings_show_notification),
                    description = stringResource(R.string.settings_show_notification_desc),
                    checked = notificationsEnabled,
                    onCheckedChange = {
                        notificationsEnabled = it
                        PreferenceHelper.notificationsEnabled = it
                        BitLockerCoreService.updateForegroundState(context)
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

            // Group 4: Advanced Options
            SettingsGroup(title = stringResource(R.string.settings_header_advanced)) {
                val rootBadge = if (!useRootAccess) {
                    stringResource(R.string.settings_non_root_mode)
                } else if (rootSolution.hasRoot) {
                    rootSolution.solutionName
                } else {
                    stringResource(R.string.settings_root_not_granted)
                }

                val rootBadgeColor = if (!useRootAccess) {
                    MaterialTheme.colorScheme.outline
                } else if (rootSolution.hasRoot) {
                    SuccessGreen
                } else {
                    MaterialTheme.colorScheme.error
                }

                SettingsClickableItem(
                    title = stringResource(R.string.settings_advanced_title),
                    description = stringResource(R.string.settings_advanced_desc),
                    badgeText = rootBadge,
                    badgeColor = rootBadgeColor,
                    onClick = onOpenAdvancedSettings
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
