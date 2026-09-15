package com.bitlockerdroid.ui.dialogs

import android.app.Activity
import android.app.KeyguardManager
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bitlockerdroid.R
import com.bitlockerdroid.util.PreferenceHelper

/** Marks clipboard content as sensitive so clipboard history / password
 * managers (Android 13+) exclude it. */
private fun markClipboardSensitive(clip: ClipData) {
    if (Build.VERSION.SDK_INT >= 33) {
        clip.description.extras = PersistableBundle().apply {
            putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
        }
    }
}

/** Dialog showing saved password and full GUID for a specific credential */
@Composable
fun ShowPasswordDialog(
    credential: PreferenceHelper.SavedCredential,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val decryptedRaw = remember(credential) {
        val blob = PreferenceHelper.getRememberedPassword(context, credential.id)
        blob?.let { com.bitlockerdroid.service.KeyGuardService.decrypt(it) }
    }
    val isRecovery = decryptedRaw?.startsWith("RECOVERY:") == true
    val passwordPlain = remember(decryptedRaw) {
        if (isRecovery) decryptedRaw?.removePrefix("RECOVERY:") else decryptedRaw
    }
    var visible by remember { mutableStateOf(false) }

    // Reveal / copy require proving device ownership: whoever holds the phone
    // must pass the lock-screen credential first.
    val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
    val deviceSecure = keyguard?.isDeviceSecure == true
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    val authLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val action = pendingAction
        pendingAction = null
        if (result.resultCode == Activity.RESULT_OK) {
            action?.invoke()
        } else {
            Toast.makeText(context, "身份验证未通过", Toast.LENGTH_SHORT).show()
        }
    }

    fun requireAuth(action: () -> Unit) {
        if (!deviceSecure) {
            action()
            return
        }
        @Suppress("DEPRECATION")
        val intent = keyguard?.createConfirmDeviceCredentialIntent(
            "验证身份",
            "请验证设备锁屏凭据以查看 BitLocker 凭据"
        )
        if (intent == null) {
            action()
            return
        }
        pendingAction = action
        try {
            authLauncher.launch(intent)
        } catch (e: Exception) {
            pendingAction = null
            Toast.makeText(context, "无法启动身份验证", Toast.LENGTH_SHORT).show()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(
                onClick = onDismiss,
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(text = "关闭")
            }
        },
        dismissButton = {
            if (passwordPlain != null) {
                TextButton(
                    onClick = {
                        requireAuth {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            val clip = ClipData.newPlainText("BitLocker Password", passwordPlain)
                            markClipboardSensitive(clip)
                            cm?.setPrimaryClip(clip)
                            Toast.makeText(context, "密码已复制到剪贴板", Toast.LENGTH_SHORT).show()
                        }
                    }
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_content_copy),
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(text = "复制密码")
                }
            }
        },
        title = {
            Column {
                Text(
                    text = "凭据详情",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = credential.displayLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Full GUID card with copy button
                if (credential.id.isNotBlank()) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (credential.id.contains("-")) "卷完整 GUID" else "设备唯一标识",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(3.dp))
                                Text(
                                    text = credential.id,
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp
                                    ),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            IconButton(
                                onClick = {
                                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                    cm?.setPrimaryClip(ClipData.newPlainText("Volume GUID", credential.id))
                                    Toast.makeText(context, "GUID 已复制", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_content_copy),
                                    contentDescription = "复制 GUID",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = if (isRecovery) "BitLocker 48位恢复密钥" else "BitLocker 解密密码",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                        if (passwordPlain != null) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = if (visible) passwordPlain else "••••••••••••••••",
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = if (isRecovery && visible) 12.sp else 16.sp
                                    ),
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(
                                    onClick = {
                                        requireAuth { visible = !visible }
                                    },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        painter = painterResource(id = R.drawable.ic_visibility),
                                        contentDescription = if (visible) "隐藏" else "显示",
                                        tint = if (visible) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                if (visible) {
                                    IconButton(
                                        onClick = {
                                            requireAuth {
                                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                                val clip = ClipData.newPlainText("BitLocker Credential", passwordPlain)
                                                markClipboardSensitive(clip)
                                                cm?.setPrimaryClip(clip)
                                                Toast.makeText(context, if (isRecovery) "恢复密钥已复制" else "密码已复制", Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            painter = painterResource(id = R.drawable.ic_content_copy),
                                            contentDescription = "复制凭据",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        } else {
                            Text(
                                text = "无法解密或凭据已损坏",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        },
        shape = RoundedCornerShape(20.dp)
    )
}
