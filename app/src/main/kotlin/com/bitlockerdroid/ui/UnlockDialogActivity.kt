package com.bitlockerdroid.ui

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.bitlockerdroid.R
import com.bitlockerdroid.service.BitLockerDetector
import com.bitlockerdroid.service.DislockerCore
import com.bitlockerdroid.service.KeyGuardService
import com.bitlockerdroid.service.UnlockManager
import com.bitlockerdroid.ui.theme.BitLockerTheme
import com.bitlockerdroid.util.DevicePathSecurity
import com.bitlockerdroid.util.LogFile
import com.bitlockerdroid.util.PreferenceHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.regex.Pattern

/**
 * Modern Jetpack Compose Material 3 Unlock Dialog.
 * Prompts for BitLocker password or 48-digit recovery key with background async unlock.
 */
class UnlockDialogActivity : ComponentActivity() {

    companion object {
        const val EXTRA_DEVICE_PATH = "device_path"
        const val EXTRA_OFFSET = "offset"
        const val EXTRA_GUID = "guid"
        private val RECOVERY_PATTERN = Pattern.compile("^(\\d{6}-){7}\\d{6}$")
    }

    private var devicePath: String = ""
    private var offset: Long = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val rawPath = intent.getStringExtra(EXTRA_DEVICE_PATH)
        if (!DevicePathSecurity.isValid(rawPath)) {
            LogFile.write("app", "UnlockDialogActivity: rejected invalid or dangerous path $rawPath")
            finish()
            return
        }
        devicePath = rawPath!!
        offset = intent.getLongExtra(EXTRA_OFFSET, 0)
        val initialGuid = intent.getStringExtra(EXTRA_GUID)

        setContent {
            BitLockerTheme {
                UnlockDialogScreen(
                    devicePath = devicePath,
                    initialGuid = initialGuid,
                    onDismiss = { finish() },
                    onUnlock = { value, isRecovery, remember, autoUnlock, onError, onSuccess ->
                        lifecycleScope.launch(Dispatchers.IO) {
                            var errorMsg: String? = null
                            val success = if (isRecovery) {
                                try {
                                    val core = DislockerCore.openWithRecoveryKey(devicePath, offset, value)
                                    UnlockManager.registerDirect(core, this@UnlockDialogActivity, key = value, isRecovery = true)
                                    true
                                } catch (e: Exception) {
                                    errorMsg = e.message
                                    LogFile.write("app", "unlock recovery failed: $errorMsg")
                                    false
                                }
                            } else {
                                val guid = initialGuid ?: com.bitlockerdroid.service.BitLockerDetector.getVolumeGuid(devicePath)
                                val res = UnlockManager.unlockWithPassword(
                                    this@UnlockDialogActivity,
                                    devicePath,
                                    offset,
                                    value,
                                    remember,
                                    expectedGuid = guid
                                )
                                if (res.isSuccess) {
                                    val effectiveGuid = res.getOrNull()?.volumeGuid ?: guid
                                    if (!effectiveGuid.isNullOrBlank()) {
                                        PreferenceHelper.setAutoUnlockEnabled(
                                            this@UnlockDialogActivity,
                                            effectiveGuid,
                                            if (remember) autoUnlock else false
                                        )
                                    }
                                }
                                if (res.isFailure) {
                                    errorMsg = res.exceptionOrNull()?.message
                                    LogFile.write("app", "unlock password failed: $errorMsg")
                                }
                                res.isSuccess
                            }

                            withContext(Dispatchers.Main) {
                                if (success) {
                                    Toast.makeText(this@UnlockDialogActivity, R.string.unlock_success, Toast.LENGTH_SHORT).show()
                                    onSuccess()
                                    finish()
                                } else {
                                    onError(errorMsg ?: getString(R.string.unlock_failed))
                                }
                            }
                        }
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UnlockDialogScreen(
    devicePath: String,
    initialGuid: String? = null,
    onDismiss: () -> Unit,
    onUnlock: (
        value: String,
        isRecovery: Boolean,
        remember: Boolean,
        autoUnlock: Boolean,
        onError: (String) -> Unit,
        onSuccess: () -> Unit
    ) -> Unit
) {
    val context = LocalContext.current
    val guid = remember(devicePath, initialGuid) { initialGuid ?: BitLockerDetector.getVolumeGuid(devicePath) }
    val savedPlain = remember(guid) {
        if (!guid.isNullOrBlank()) {
            PreferenceHelper.getRememberedPassword(context, guid)?.let {
                KeyGuardService.decrypt(it)
            }
        } else null
    }

    var isRecoveryKey by remember { mutableStateOf(false) }
    var inputValue by remember { mutableStateOf(savedPlain ?: "") }
    var passwordVisible by remember { mutableStateOf(false) }
    var rememberPassword by remember { mutableStateOf(savedPlain != null) }
    var autoUnlockOnScan by remember {
        mutableStateOf(if (!guid.isNullOrBlank()) PreferenceHelper.isAutoUnlockEnabled(context, guid) else true)
    }
    var isUnlocking by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val recoveryPattern = remember { Pattern.compile("^(\\d{6}-){7}\\d{6}$") }

    val triggerUnlock: () -> Unit = {
        val trimmed = if (isRecoveryKey) normalizeRecoveryKey(inputValue) else inputValue.trim()
        if (trimmed.isEmpty()) {
            errorMessage = "请输入密码或恢复密钥"
        } else if (isRecoveryKey && !recoveryPattern.matcher(trimmed).matches()) {
            errorMessage = "恢复密钥必须为 8 组各 6 位的有效格式 (XXXXXX-XXXXXX-...)"
        } else {
            isUnlocking = true
            errorMessage = null
            onUnlock(
                trimmed,
                isRecoveryKey,
                rememberPassword,
                autoUnlockOnScan,
                { err ->
                    isUnlocking = false
                    errorMessage = err
                },
                {
                    isUnlocking = false
                }
            )
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { if (!isUnlocking) onDismiss() }
            ),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .widthIn(max = 460.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { /* prevent click-through */ }
                ),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Header Icon
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(56.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Title
                Text(
                    text = stringResource(R.string.unlock_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(6.dp))

                // Friendly Device Badge (Never raw kernel node)
                val devInfo = remember(devicePath, guid) { com.bitlockerdroid.util.DeviceIdentity.queryDeviceInfo(devicePath, forceRefresh = true) }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Text(
                            text = devInfo.friendlyName,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    }
                    if (devInfo.sizeBytes > 0L) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Text(
                                text = com.bitlockerdroid.util.DeviceIdentity.formatSize(devInfo.sizeBytes),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                            )
                        }
                    }
                }

                if (!guid.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "卷 GUID",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.outline
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = guid,
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            IconButton(
                                onClick = {
                                    val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                                    cm?.setPrimaryClip(android.content.ClipData.newPlainText("Volume GUID", guid))
                                    Toast.makeText(context, "GUID 已复制到剪切板", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    painter = androidx.compose.ui.res.painterResource(id = R.drawable.ic_content_copy),
                                    contentDescription = "复制 GUID",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                // Toggle between Password and Recovery Key
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = !isRecoveryKey,
                        onClick = {
                            if (!isUnlocking) {
                                isRecoveryKey = false
                                inputValue = savedPlain ?: ""
                                errorMessage = null
                            }
                        },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                    ) {
                        Text(text = stringResource(R.string.use_password), fontSize = 13.sp)
                    }
                    SegmentedButton(
                        selected = isRecoveryKey,
                        onClick = {
                            if (!isUnlocking) {
                                isRecoveryKey = true
                                inputValue = ""
                                errorMessage = null
                            }
                        },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                    ) {
                        Text(text = stringResource(R.string.use_recovery_key), fontSize = 13.sp)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Input Field
                OutlinedTextField(
                    value = inputValue,
                    onValueChange = {
                        inputValue = it
                        errorMessage = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = {
                        Text(
                            text = if (isRecoveryKey) stringResource(R.string.recovery_key_hint)
                            else stringResource(R.string.password_hint)
                        )
                    },
                    placeholder = {
                        Text(
                            text = if (isRecoveryKey) "XXXXXX-XXXXXX-..."
                            else stringResource(R.string.password_hint)
                        )
                    },
                    supportingText = if (!isRecoveryKey && savedPlain != null && inputValue == savedPlain) {
                        {
                            Text(
                                text = "已自动填充已保存密码",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    } else null,
                    singleLine = true,
                    enabled = !isUnlocking,
                    visualTransformation = if (isRecoveryKey || passwordVisible) VisualTransformation.None
                    else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (isRecoveryKey) KeyboardType.Ascii else KeyboardType.Password,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            triggerUnlock()
                        }
                    ),
                    trailingIcon = {
                        if (!isRecoveryKey) {
                            TextButton(onClick = { passwordVisible = !passwordVisible }) {
                                Text(
                                    text = if (passwordVisible) "隐藏" else "显示",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        } else {
                            val context = LocalContext.current
                            TextButton(onClick = {
                                val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                                val clip = cm?.primaryClip
                                if (clip != null && clip.itemCount > 0) {
                                    val text = clip.getItemAt(0).coerceToText(context)?.toString() ?: ""
                                    inputValue = normalizeRecoveryKey(text)
                                    errorMessage = null
                                }
                            }) {
                                Text(
                                    text = "粘贴",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    },
                    shape = RoundedCornerShape(12.dp)
                )

                // Options (Only for password mode)
                if (!isRecoveryKey) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(enabled = !isUnlocking) {
                                rememberPassword = !rememberPassword
                                if (rememberPassword && !guid.isNullOrBlank()) {
                                    autoUnlockOnScan = PreferenceHelper.isAutoUnlockEnabled(context, guid)
                                }
                            }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = rememberPassword,
                            onCheckedChange = null,
                            enabled = !isUnlocking
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.remember_password),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    AnimatedVisibility(visible = rememberPassword) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(enabled = !isUnlocking) { autoUnlockOnScan = !autoUnlockOnScan }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = autoUnlockOnScan,
                                onCheckedChange = null,
                                enabled = !isUnlocking
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "插入此盘时自动解锁",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }

                // Error Message Card
                AnimatedVisibility(visible = errorMessage != null) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = errorMessage ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = onDismiss,
                        enabled = !isUnlocking,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(text = stringResource(android.R.string.cancel))
                    }

                    Button(
                        onClick = triggerUnlock,
                        enabled = !isUnlocking && inputValue.isNotBlank(),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp)
                    ) {
                        if (isUnlocking) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = "正在解锁…")
                        } else {
                            Text(text = stringResource(R.string.unlock_action))
                        }
                    }
                }
            }
        }
    }
}

private fun normalizeRecoveryKey(input: String): String {
    val clean = input.trim()
        .replace(" ", "-")
        .replace("－", "-")
        .replace("—", "-")
        .replace("–", "-")
    val digitsOnly = clean.filter { it.isDigit() }
    if (digitsOnly.length == 48 && !clean.contains("-")) {
        return digitsOnly.chunked(6).joinToString("-")
    }
    return clean
}
