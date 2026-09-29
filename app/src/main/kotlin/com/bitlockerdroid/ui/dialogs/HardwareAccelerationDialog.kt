package com.bitlockerdroid.ui.dialogs

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.bitlockerdroid.R
import com.bitlockerdroid.ui.settings.SettingsGroup
import com.bitlockerdroid.ui.settings.SettingsStatusItem
import com.bitlockerdroid.ui.settings.SettingsSwitchItem
import com.bitlockerdroid.ui.theme.SuccessGreen
import com.bitlockerdroid.util.NativeBridge

/**
 * Dedicated Hardware Acceleration Dialog:
 * Inside the entry, divided into two distinct algorithm acceleration options:
 * 1. ARMv8 Hardware AES (AES-XTS & AES-CBC sector encryption/decryption)
 * 2. ARMv8 Hardware SHA-256 (1,048,576 rounds key stretching & metadata verification)
 * Each with independent capability detection, detailed underlying test results, and toggle switches.
 */
@Composable
fun HardwareAccelerationDialog(
    useHardwareAes: Boolean,
    onUseHardwareAesChange: (Boolean) -> Unit,
    useHardwareSha2: Boolean,
    onUseHardwareSha2Change: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val isZh = remember {
        try {
            context.resources.configuration.locales[0].language.startsWith("zh")
        } catch (_: Throwable) { false }
    }

    val isHwAesSupported = remember {
        try {
            NativeBridge.nativeIsHardwareAesSupported()
        } catch (_: Throwable) {
            false
        }
    }
    val isHwSha2Supported = remember {
        try {
            NativeBridge.nativeIsHardwareSha2Supported()
        } catch (_: Throwable) {
            false
        }
    }

    val aesDetails = remember {
        try {
            val raw = NativeBridge.nativeGetHardwareAesDetails()
            if (isZh) {
                if (raw.contains("Passed")) "HWCAP_AES: 支持 · 自检: XTS/CBC 回环测试全部通过"
                else if (raw.contains("Failed")) "HWCAP_AES: 支持 · 自检: 失败 (已降级纯软件)"
                else if (raw.contains("Missing")) "HWCAP_AES: 不支持 · CPU 缺失 ARMv8 CE 指令集"
                else raw
            } else {
                raw
            }
        } catch (_: Throwable) {
            if (isHwAesSupported) "HWCAP_AES: Supported · Self-test: Passed"
            else "HWCAP_AES: Unsupported"
        }
    }

    val sha2Details = remember {
        try {
            val raw = NativeBridge.nativeGetHardwareSha2Details()
            if (isZh) {
                if (raw.contains("Passed")) "HWCAP_SHA2: 支持 · 自检: NIST 向量与拉伸测试验证通过"
                else if (raw.contains("Failed")) "HWCAP_SHA2: 支持 · 自检: 失败 (已降级纯软件)"
                else if (raw.contains("Missing")) "HWCAP_SHA2: 不支持 · CPU 缺失 ARMv8 CE 指令集"
                else raw
            } else {
                raw
            }
        } catch (_: Throwable) {
            if (isHwSha2Supported) "HWCAP_SHA2: Supported · Self-test: Passed"
            else "HWCAP_SHA2: Unsupported"
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier
            .fillMaxWidth(0.92f)
            .widthIn(max = 520.dp),
        icon = {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(52.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(26.dp)
                    )
                }
            }
        },
        title = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = stringResource(R.string.settings_hw_crypto_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.settings_hw_crypto_dialog_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Section 1: AES Hardware Acceleration
                SettingsGroup(
                    title = stringResource(R.string.settings_hw_aes_title)
                ) {
                    val aesBadgeText = if (isHwAesSupported) {
                        if (useHardwareAes) stringResource(R.string.settings_hw_aes_active)
                        else stringResource(R.string.settings_hw_aes_disabled)
                    } else {
                        stringResource(R.string.settings_hw_aes_unsupported)
                    }
                    val aesBadgeColor = if (isHwAesSupported) {
                        if (useHardwareAes) SuccessGreen else MaterialTheme.colorScheme.tertiary
                    } else {
                        MaterialTheme.colorScheme.outline
                    }

                    SettingsStatusItem(
                        title = stringResource(R.string.settings_hw_aes_status),
                        description = aesDetails,
                        badgeText = aesBadgeText,
                        badgeColor = aesBadgeColor
                    )

                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                    )

                    SettingsSwitchItem(
                        title = stringResource(R.string.settings_hw_aes_title),
                        description = stringResource(R.string.settings_hw_aes_desc),
                        checked = useHardwareAes && isHwAesSupported,
                        enabled = isHwAesSupported,
                        onCheckedChange = onUseHardwareAesChange
                    )
                }

                // Section 2: SHA-256 Hardware Acceleration
                SettingsGroup(
                    title = stringResource(R.string.settings_hw_sha2_title)
                ) {
                    val shaBadgeText = if (isHwSha2Supported) {
                        if (useHardwareSha2) stringResource(R.string.settings_hw_sha2_active)
                        else stringResource(R.string.settings_hw_sha2_disabled)
                    } else {
                        stringResource(R.string.settings_hw_sha2_unsupported)
                    }
                    val shaBadgeColor = if (isHwSha2Supported) {
                        if (useHardwareSha2) SuccessGreen else MaterialTheme.colorScheme.tertiary
                    } else {
                        MaterialTheme.colorScheme.outline
                    }

                    SettingsStatusItem(
                        title = stringResource(R.string.settings_hw_sha2_status),
                        description = sha2Details,
                        badgeText = shaBadgeText,
                        badgeColor = shaBadgeColor
                    )

                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                    )

                    SettingsSwitchItem(
                        title = stringResource(R.string.settings_hw_sha2_title),
                        description = stringResource(R.string.settings_hw_sha2_desc),
                        checked = useHardwareSha2 && isHwSha2Supported,
                        enabled = isHwSha2Supported,
                        onCheckedChange = onUseHardwareSha2Change
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    text = stringResource(R.string.close),
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    )
}
