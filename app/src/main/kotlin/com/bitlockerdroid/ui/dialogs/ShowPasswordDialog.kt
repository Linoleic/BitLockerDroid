package com.bitlockerdroid.ui.dialogs

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bitlockerdroid.R
import com.bitlockerdroid.service.SavedCredentialAccess
import com.bitlockerdroid.util.PreferenceHelper
import com.bitlockerdroid.util.findFragmentActivity
import java.util.concurrent.atomic.AtomicBoolean

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
    var decryptedRaw by remember(credential.id) { mutableStateOf<String?>(null) }
    var visible by remember(credential.id) { mutableStateOf(false) }
    val dialogActive = remember(credential.id) { AtomicBoolean(true) }
    val currentCredentialId by rememberUpdatedState(credential.id)
    DisposableEffect(credential.id) {
        dialogActive.set(true)
        onDispose { dialogActive.set(false) }
    }
    val isRecovery = decryptedRaw?.startsWith("RECOVERY:") == true
    val passwordPlain = decryptedRaw?.let { raw ->
        if (raw.startsWith("RECOVERY:")) raw.removePrefix("RECOVERY:") else raw
    }

    fun requireCredential(action: String) {
        val activity = context.findFragmentActivity()
        if (activity == null || activity.isFinishing || activity.isDestroyed) {
            Toast.makeText(context, R.string.auth_start_failed, Toast.LENGTH_SHORT).show()
            return
        }
        val requestedCredentialId = credential.id
        SavedCredentialAccess.readForUserAction(
            activity = activity,
            volumeGuid = requestedCredentialId,
            title = context.getString(R.string.auth_title),
            subtitle = context.getString(R.string.biometric_auth_for_view),
            onSuccess = { raw ->
                if (!dialogActive.get() || currentCredentialId != requestedCredentialId) return@readForUserAction
                decryptedRaw = raw
                val recovery = raw.startsWith("RECOVERY:")
                val cleanCredential = if (recovery) raw.removePrefix("RECOVERY:") else raw
                when (action) {
                    "show" -> visible = true
                    "copy" -> {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        val clip = ClipData.newPlainText("BitLocker Credential", cleanCredential)
                        markClipboardSensitive(clip)
                        cm?.setPrimaryClip(clip)
                        Toast.makeText(
                            context,
                            if (recovery) R.string.recovery_key_copied else R.string.password_copied,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            },
            onError = { err ->
                if (dialogActive.get() && currentCredentialId == requestedCredentialId) {
                    Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    fun dismissDialog() {
        dialogActive.set(false)
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = { dismissDialog() },
        confirmButton = {
            Button(
                onClick = { dismissDialog() },
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(text = stringResource(R.string.close))
            }
        },
        dismissButton = {
            TextButton(onClick = { requireCredential("copy") }) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_content_copy),
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(text = stringResource(R.string.copy_credential))
            }
        },
        title = {
            Column {
                Text(
                    text = stringResource(R.string.credential_details),
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
                                    text = if (credential.id.contains("-")) stringResource(R.string.volume_full_guid) else stringResource(R.string.device_unique_id),
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
                                    Toast.makeText(context, R.string.guid_copied, Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_content_copy),
                                    contentDescription = stringResource(R.string.copy_guid),
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
                        if (decryptedRaw != null) {
                            Text(
                                text = if (isRecovery) stringResource(R.string.bitlocker_recovery_key_title) else stringResource(R.string.bitlocker_password_title),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = if (visible && passwordPlain != null) passwordPlain else "••••••••••••••••",
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
                                        if (visible) visible = false else requireCredential("show")
                                    },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        painter = painterResource(id = R.drawable.ic_visibility),
                                        contentDescription = if (visible) stringResource(R.string.hide) else stringResource(R.string.show),
                                        tint = if (visible) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                if (visible) {
                                    IconButton(
                                        onClick = { requireCredential("copy") },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            painter = painterResource(id = R.drawable.ic_content_copy),
                                            contentDescription = stringResource(R.string.copy_credential),
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                    }
                }
            }
        },
        shape = RoundedCornerShape(20.dp)
    )
}
