package com.bitlockerdroid.ui

import android.content.Intent
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
import com.bitlockerdroid.ui.theme.ThemeMode
import com.bitlockerdroid.util.DevicePathSecurity
import com.bitlockerdroid.util.LocaleHelper
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
        const val EXTRA_RECOVERY_KEY_ID = "recovery_key_id"
        private val RECOVERY_PATTERN = Pattern.compile("^(\\d{6}-){7}\\d{6}$")
    }

    // Compose state so onNewIntent re-renders the dialog for the new volume.
    private var devicePath: String by mutableStateOf("")
    private var offset: Long = 0
    private var initialGuid: String? by mutableStateOf<String?>(null)
    private var initialRecoveryKeyId: String? by mutableStateOf<String?>(null)

    /**
     * Validates and applies a launch intent. Shared by onCreate and
     * onNewIntent: a second launch while the dialog is finishing cancels the
     * finish and delivers the intent here — extras must pass through the same
     * DevicePathSecurity gate, never bypass it.
     */
    private fun applyIntent(intent: Intent?) {
        val rawPath = intent?.getStringExtra(EXTRA_DEVICE_PATH)
        if (!DevicePathSecurity.isValid(rawPath)) {
            LogFile.write("app", "UnlockDialogActivity: rejected invalid or dangerous path $rawPath")
            finish()
            return
        }
        devicePath = rawPath!!
        offset = intent.getLongExtra(EXTRA_OFFSET, 0)
        initialGuid = intent.getStringExtra(EXTRA_GUID)
        initialRecoveryKeyId = intent.getStringExtra(EXTRA_RECOVERY_KEY_ID)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyIntent(intent)
    }

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Password entry must not be captured by screenshots or screen recording.
        window.setFlags(
            android.view.WindowManager.LayoutParams.FLAG_SECURE,
            android.view.WindowManager.LayoutParams.FLAG_SECURE
        )

        applyIntent(intent)

        setContent {
            val themeMode = ThemeMode.fromString(PreferenceHelper.themeMode)
            BitLockerTheme(themeMode = themeMode) {
                UnlockDialogScreen(
                    devicePath = devicePath,
                    initialGuid = initialGuid,
                    initialRecoveryKeyId = initialRecoveryKeyId,
                    onDismiss = { finish() },
                    onUnlock = { value, isRecovery, remember, autoUnlock, onError, onSuccess ->
                        lifecycleScope.launch(Dispatchers.IO) {
                            var errorMsg: String? = null
                            val guid = initialGuid ?: com.bitlockerdroid.service.BitLockerDetector.getVolumeGuid(devicePath)
                            val res = if (isRecovery) {
                                UnlockManager.unlockWithRecoveryKey(
                                    this@UnlockDialogActivity,
                                    devicePath,
                                    offset,
                                    value,
                                    remember,
                                    expectedGuid = guid
                                )
                            } else {
                                UnlockManager.unlockWithPassword(
                                    this@UnlockDialogActivity,
                                    devicePath,
                                    offset,
                                    value,
                                    remember,
                                    expectedGuid = guid
                                )
                            }
                            val success = res.isSuccess
                            if (success) {
                                val effectiveGuid = res.getOrNull()?.volumeGuid ?: guid
                                if (!effectiveGuid.isNullOrBlank()) {
                                    PreferenceHelper.setAutoUnlockEnabled(
                                        this@UnlockDialogActivity,
                                        effectiveGuid,
                                        if (remember) autoUnlock else false
                                    )
                                }
                            } else {
                                errorMsg = res.exceptionOrNull()?.message
                                LogFile.write("app", "unlock failed (isRecovery=$isRecovery): $errorMsg")
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

/** Volume metadata loaded off the main thread before the dialog is interactive. */
private data class VolumePreflight(
    val guid: String?,
    val recoveryKeyId: String?,
    val savedPlain: String?,
    val devInfo: com.bitlockerdroid.util.DeviceIdentity.DeviceInfo
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UnlockDialogScreen(
    devicePath: String,
    initialGuid: String? = null,
    initialRecoveryKeyId: String? = null,
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

    // Resolving the volume GUID reads the device header through root, and the
    // saved-password path decrypts via AndroidKeyStore — both would block the
    // main thread during composition, so load them asynchronously.
    var preflight by remember(devicePath, initialGuid, initialRecoveryKeyId) { mutableStateOf<VolumePreflight?>(null) }
    LaunchedEffect(devicePath, initialGuid, initialRecoveryKeyId) {
        preflight = withContext(Dispatchers.IO) {
            val g = initialGuid ?: BitLockerDetector.getVolumeGuid(devicePath)
            val rkId = initialRecoveryKeyId ?: BitLockerDetector.getRecoveryKeyId(devicePath)
            val saved = if (!g.isNullOrBlank()) {
                PreferenceHelper.getRememberedPassword(context, g)?.let { KeyGuardService.decrypt(it) }
            } else null
            val dev = com.bitlockerdroid.util.DeviceIdentity.queryDeviceInfo(devicePath, forceRefresh = true)
            VolumePreflight(g, rkId, saved, dev)
        }
    }
    val guid = preflight?.guid
    val recoveryKeyId = preflight?.recoveryKeyId
    val savedPlain = preflight?.savedPlain

    var isRecoveryKey by remember { mutableStateOf(false) }
    var inputValue by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var rememberPassword by remember { mutableStateOf(false) }
    var autoUnlockOnScan by remember { mutableStateOf(true) }

    // Fill in the saved credentials once the preflight arrives — but only if
    // the user has not started typing yet.
    LaunchedEffect(preflight) {
        val pf = preflight ?: return@LaunchedEffect
        if (inputValue.isEmpty()) {
            pf.savedPlain?.let { saved ->
                if (saved.startsWith(UnlockManager.RECOVERY_PREFIX)) {
                    isRecoveryKey = true
                    inputValue = saved.removePrefix(UnlockManager.RECOVERY_PREFIX)
                    rememberPassword = true
                } else {
                    isRecoveryKey = false
                    inputValue = saved
                    rememberPassword = true
                }
            }
        }
        if (!pf.guid.isNullOrBlank()) {
            autoUnlockOnScan = PreferenceHelper.isAutoUnlockEnabled(context, pf.guid)
        }
    }
    var isUnlocking by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val recoveryPattern = remember { Pattern.compile("^(\\d{6}-){7}\\d{6}$") }

    val triggerUnlock: () -> Unit = {
        val trimmed = if (isRecoveryKey) normalizeRecoveryKey(inputValue) else inputValue.trim()
        if (trimmed.isEmpty()) {
            errorMessage = context.getString(R.string.password_required)
        } else if (isRecoveryKey && !recoveryPattern.matcher(trimmed).matches()) {
            errorMessage = context.getString(R.string.invalid_recovery_key)
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
                val devInfo = preflight?.devInfo

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Text(
                            text = devInfo?.friendlyName ?: stringResource(R.string.usb_device_default),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    }
                    if (devInfo != null && devInfo.sizeBytes > 0L) {
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
                                    text = stringResource(R.string.volume_guid),
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
                                    Toast.makeText(context, R.string.guid_copied_to_clipboard, Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    painter = androidx.compose.ui.res.painterResource(id = R.drawable.ic_content_copy),
                                    contentDescription = stringResource(R.string.copy_guid),
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
                                inputValue = if (savedPlain?.startsWith(UnlockManager.RECOVERY_PREFIX) == false) savedPlain else ""
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
                                inputValue = if (savedPlain?.startsWith(UnlockManager.RECOVERY_PREFIX) == true) {
                                    savedPlain.removePrefix(UnlockManager.RECOVERY_PREFIX)
                                } else ""
                                errorMessage = null
                            }
                        },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                    ) {
                        Text(text = stringResource(R.string.use_recovery_key), fontSize = 13.sp)
                    }
                }

                // Recovery Key Identifier Card (Shown when recovery key mode is selected)
                AnimatedVisibility(visible = isRecoveryKey && !recoveryKeyId.isNullOrBlank()) {
                    Column {
                        Spacer(modifier = Modifier.height(14.dp))
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = stringResource(R.string.recovery_key_id),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(modifier = Modifier.height(3.dp))
                                    Text(
                                        text = recoveryKeyId ?: "",
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 11.sp
                                        ),
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                                        cm?.setPrimaryClip(android.content.ClipData.newPlainText("Recovery Key ID", recoveryKeyId))
                                        Toast.makeText(context, R.string.recovery_id_copied, Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        painter = androidx.compose.ui.res.painterResource(id = R.drawable.ic_content_copy),
                                        contentDescription = stringResource(R.string.copy_recovery_id),
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
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
                                text = stringResource(R.string.password_autofilled),
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
                                    text = if (passwordVisible) stringResource(R.string.hide) else stringResource(R.string.show),
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
                                    text = stringResource(R.string.paste),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    },
                    shape = RoundedCornerShape(12.dp)
                )

                // Options (Remember credential & Auto unlock for both Password and Recovery Key)
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
                        text = if (isRecoveryKey) stringResource(R.string.remember_recovery_key) else stringResource(R.string.remember_password),
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
                            text = stringResource(R.string.auto_unlock_on_insert),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
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
                            Text(text = stringResource(R.string.unlocking_progress))
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
