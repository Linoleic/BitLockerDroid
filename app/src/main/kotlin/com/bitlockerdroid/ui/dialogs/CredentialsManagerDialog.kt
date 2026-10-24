package com.bitlockerdroid.ui.dialogs

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import com.bitlockerdroid.R
import com.bitlockerdroid.ui.theme.SuccessGreen
import com.bitlockerdroid.util.BiometricAuthHelper
import com.bitlockerdroid.util.PreferenceHelper
import com.bitlockerdroid.util.findFragmentActivity
import java.util.concurrent.atomic.AtomicBoolean

/** Credentials Management Dialog (Biometric KeyStore Vault) */
@Composable
fun CredentialsManagerDialog(
    credentials: List<PreferenceHelper.SavedCredential>,
    onToggleAutoUnlock: (String, Boolean) -> Unit,
    onShowPassword: (PreferenceHelper.SavedCredential) -> Unit,
    onDeleteCredential: (String) -> Unit,
    onClearAllCredentials: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val activity = (context as? FragmentActivity) ?: context.findFragmentActivity()
    val dialogActive = remember { AtomicBoolean(true) }
    DisposableEffect(Unit) {
        dialogActive.set(true)
        onDispose { dialogActive.set(false) }
    }

    fun runCredentialAction(subtitle: String, action: () -> Unit) {
        if (!PreferenceHelper.isBiometricVaultEnabled(context)) {
            if (dialogActive.get()) action()
            return
        }
        val authActivity = activity
        if (authActivity == null || authActivity.isFinishing || authActivity.isDestroyed ||
            !BiometricAuthHelper.canAuthenticate(authActivity)
        ) {
            Toast.makeText(context, R.string.auth_failed, Toast.LENGTH_SHORT).show()
            return
        }
        BiometricAuthHelper.authenticate(
            activity = authActivity,
            title = context.getString(R.string.auth_title),
            subtitle = subtitle,
            onSuccess = { if (dialogActive.get()) action() },
            onError = { err ->
                if (dialogActive.get()) Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
            }
        )
    }

    fun safeDelete(credId: String) = runCredentialAction(context.getString(R.string.creds_delete)) {
        onDeleteCredential(credId)
    }

    fun safeClearAll() = runCredentialAction(context.getString(R.string.settings_credentials_clear)) {
        onClearAllCredentials()
    }

    fun dismissDialog() {
        dialogActive.set(false)
        onDismiss()
    }

    val compactHeight = LocalConfiguration.current.screenHeightDp < 400
    if (compactHeight) {
        // A short window needs room for the list, rather than a large icon/title.
        Dialog(
            onDismissRequest = { dismissDialog() },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(0.95f).fillMaxHeight(0.95f),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.creds_dialog_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = if (credentials.isEmpty()) stringResource(R.string.creds_empty)
                                else stringResource(R.string.creds_total_count, credentials.size),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    CredentialList(
                        credentials = credentials,
                        onToggleAutoUnlock = onToggleAutoUnlock,
                        onShowPassword = onShowPassword,
                        onDeleteCredential = { safeDelete(it) },
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (credentials.size > 1) {
                            TextButton(
                                onClick = { safeClearAll() },
                                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                            ) {
                                Text(stringResource(R.string.settings_credentials_clear))
                            }
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(onClick = { dismissDialog() }) {
                            Text(stringResource(R.string.done))
                        }
                    }
                }
            }
        }
        return
    }

    AlertDialog(
        onDismissRequest = { dismissDialog() },
        confirmButton = {
            Button(
                onClick = { dismissDialog() },
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(text = stringResource(R.string.done))
            }
        },
        dismissButton = {
            if (credentials.size > 1) {
                TextButton(
                    onClick = { safeClearAll() },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(text = stringResource(R.string.settings_credentials_clear))
                }
            }
        },
        icon = {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(48.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        },
        title = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = stringResource(R.string.creds_dialog_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = if (credentials.isEmpty()) stringResource(R.string.creds_empty) else stringResource(R.string.creds_total_count, credentials.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            CredentialList(
                credentials = credentials,
                onToggleAutoUnlock = onToggleAutoUnlock,
                onShowPassword = onShowPassword,
                onDeleteCredential = { safeDelete(it) },
                modifier = Modifier.heightIn(max = 440.dp)
            )
        },
        shape = RoundedCornerShape(24.dp)
    )
}


@Composable
private fun CredentialList(
    credentials: List<PreferenceHelper.SavedCredential>,
    onToggleAutoUnlock: (String, Boolean) -> Unit,
    onShowPassword: (PreferenceHelper.SavedCredential) -> Unit,
    onDeleteCredential: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (credentials.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.settings_credentials_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            for (cred in credentials) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    border = BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = (if (cred.autoUnlock) SuccessGreen else MaterialTheme.colorScheme.surfaceVariant).copy(alpha = 0.25f),
                                modifier = Modifier.size(36.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = if (cred.autoUnlock) Icons.Default.Check else Icons.Default.Lock,
                                        contentDescription = null,
                                        tint = if (cred.autoUnlock) SuccessGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = cred.displayLabel,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                                if (cred.subtitle.isNotBlank()) {
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = cred.subtitle,
                                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                            IconButton(
                                onClick = { onDeleteCredential(cred.id) },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = stringResource(R.string.creds_delete),
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = stringResource(R.string.creds_auto_unlock_enabled),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Medium,
                                color = if (cred.autoUnlock) SuccessGreen else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Switch(
                                checked = cred.autoUnlock,
                                onCheckedChange = { onToggleAutoUnlock(cred.id, it) },
                                modifier = Modifier.height(28.dp)
                            )
                            Spacer(modifier = Modifier.weight(1f))
                            FilledTonalButton(
                                onClick = { onShowPassword(cred) },
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                modifier = Modifier.height(34.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.creds_view_password),
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
