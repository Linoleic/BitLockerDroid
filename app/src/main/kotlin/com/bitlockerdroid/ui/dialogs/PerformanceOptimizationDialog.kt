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
 * Unified Performance Optimization Dialog:
 * Consolidates all performance switches and underlying status indicators:
 * 1. ARMv8 Hardware Cryptography (AES sector crypt & SHA-256 key stretching)
 * 2. Pipeline & Streaming Buffers (SAF double-buffered async pump & Non-Root USB pipelining)
 * 3. Kernel block queue & I/O engine optimizations (transparent read-ahead, F_SETPIPE_SZ, writev)
 */
@Composable
fun PerformanceOptimizationDialog(
    useHardwareAes: Boolean,
    onUseHardwareAesChange: (Boolean) -> Unit,
    useHardwareSha2: Boolean,
    onUseHardwareSha2Change: (Boolean) -> Unit,
    useSafDoubleBuffer: Boolean,
    onUseSafDoubleBufferChange: (Boolean) -> Unit,
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
                if (raw.contains("Passed")) "HWCAP_AES · AESE/AESD/PMULL · AES-XTS/CBC 自检通过 (ARMv8 CE)"
                else if (raw.contains("Failed")) "HWCAP_AES · 硬件指令存在但自检失败 (降级纯软件)"
                else if (raw.contains("Missing")) "HWCAP_AES: 缺失 · CPU 缺少 ARMv8 密码学扩展指令"
                else raw
            } else {
                if (raw.contains("Passed")) "HWCAP_AES · AESE/AESD/PMULL · AES-XTS & CBC Passed (ARMv8 CE)"
                else if (raw.contains("Failed")) "HWCAP_AES · Self-Test Failed (Software fallback)"
                else if (raw.contains("Missing")) "HWCAP_AES: Missing · CPU lacks ARMv8 CE instructions"
                else raw
            }
        } catch (_: Throwable) {
            if (isHwAesSupported) if (isZh) "HWCAP_AES · AESE/AESD/PMULL · 自检通过 (ARMv8 CE)" else "HWCAP_AES · AESE/AESD/PMULL · Passed (ARMv8 CE)"
            else if (isZh) "HWCAP_AES: 缺失 · 不支持硬件加速" else "HWCAP_AES: Missing · Hardware acceleration unsupported"
        }
    }

    val sha2BenchmarkUs = remember {
        try {
            if (isHwSha2Supported) NativeBridge.nativeBenchmarkKeyStretching(1000) else -1L
        } catch (_: Throwable) { -1L }
    }

    val sha2Details = remember(sha2BenchmarkUs) {
        try {
            val raw = NativeBridge.nativeGetHardwareSha2Details()
            val benchmarkText = if (sha2BenchmarkUs > 0) {
                if (isZh) " · 1000轮实测: ${sha2BenchmarkUs}µs" else " · 1000 rounds: ${sha2BenchmarkUs}µs"
            } else ""
            if (isZh) {
                if (raw.contains("Passed")) "HWCAP_SHA2 · SHA256H/SHA256SU · NIST 向量与拉伸自检通过$benchmarkText"
                else if (raw.contains("Failed")) "HWCAP_SHA2 · 硬件指令存在但自检失败 (降级 mbedtls)"
                else if (raw.contains("Missing")) "HWCAP_SHA2: 缺失 · CPU 缺少 ARMv8 密码学扩展指令"
                else raw + benchmarkText
            } else {
                if (raw.contains("Passed")) "HWCAP_SHA2 · SHA256H/SHA256SU · NIST & Stretch Passed$benchmarkText"
                else if (raw.contains("Failed")) "HWCAP_SHA2 · Self-Test Failed (mbedtls fallback)"
                else if (raw.contains("Missing")) "HWCAP_SHA2: Missing · CPU lacks ARMv8 CE instructions"
                else raw + benchmarkText
            }
        } catch (_: Throwable) {
            if (isHwSha2Supported) if (isZh) "HWCAP_SHA2 · SHA256H/SHA256SU · 自检通过 (ARMv8 CE)" else "HWCAP_SHA2 · SHA256H/SHA256SU · Passed (ARMv8 CE)"
            else if (isZh) "HWCAP_SHA2: 缺失 · 不支持硬件加速" else "HWCAP_SHA2: Missing · Hardware acceleration unsupported"
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier
            .fillMaxWidth(0.92f)
            .widthIn(max = 620.dp),
        icon = {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(42.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        },
        title = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = stringResource(R.string.settings_performance_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.settings_performance_dialog_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Section 1: ARMv8 Hardware Cryptography Acceleration
                SettingsGroup(
                    title = stringResource(R.string.settings_performance_sec_hw)
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

                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                    )

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

                // Section 2: Pipeline & Streaming Buffers
                SettingsGroup(
                    title = stringResource(R.string.settings_performance_sec_pipeline)
                ) {
                    SettingsSwitchItem(
                        title = stringResource(R.string.settings_saf_double_buffer_title),
                        description = stringResource(R.string.settings_saf_double_buffer_desc),
                        checked = useSafDoubleBuffer,
                        onCheckedChange = onUseSafDoubleBufferChange
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
