package com.bitlockerdroid.ui

import android.content.Context
import android.hardware.usb.UsbManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.bitlockerdroid.R
import com.bitlockerdroid.usb.UsbStorageManager
import com.bitlockerdroid.util.RootAccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

internal enum class AuthorizationState { CHECKING, GRANTED, REQUIRED, NO_DEVICE, ERROR }

internal data class AuthorizationStatus(
    val state: AuthorizationState,
    val deviceCount: Int?,
    val grantedCount: Int,
    val requestFailed: Boolean,
    val recheck: () -> Unit,
    val requestUsbPermission: () -> Unit
)

/** Shared by both entry points. Reading permission/device tables never claims a USB interface. */
@Composable
internal fun rememberAuthorizationStatus(useRootAccess: Boolean): AuthorizationStatus {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var state by remember(useRootAccess) { mutableStateOf(AuthorizationState.CHECKING) }
    var deviceCount by remember(useRootAccess) { mutableStateOf<Int?>(null) }
    var grantedCount by remember(useRootAccess) { mutableIntStateOf(0) }
    var checkRequest by remember { mutableIntStateOf(0) }
    var requestFailed by remember(useRootAccess) { mutableStateOf(false) }

    LaunchedEffect(useRootAccess, owner, checkRequest) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            state = AuthorizationState.CHECKING
            if (useRootAccess) {
                state = try {
                    val granted = withContext(Dispatchers.IO) { RootAccess.hasSu(forceRefresh = true) }
                    if (granted) AuthorizationState.GRANTED else AuthorizationState.REQUIRED
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    AuthorizationState.ERROR
                }
            }
            while (true) {
                try {
                    val manager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
                        ?: error("USB service unavailable")
                    val devices = manager.deviceList.values.filter(UsbStorageManager::isMassStorageDevice)
                    deviceCount = devices.size
                    grantedCount = if (useRootAccess) 0 else devices.count(manager::hasPermission)
                    if (!useRootAccess) {
                        state = when {
                            devices.isEmpty() -> AuthorizationState.NO_DEVICE
                            grantedCount == devices.size -> AuthorizationState.GRANTED
                            else -> AuthorizationState.REQUIRED
                        }
                    }
                } catch (_: Exception) {
                    deviceCount = null
                    grantedCount = 0
                    if (!useRootAccess) state = AuthorizationState.ERROR
                }
                // Poll USB tables only. su is checked once per request or resume.
                delay(2000)
            }
        }
    }

    return AuthorizationStatus(
        state = state,
        deviceCount = deviceCount,
        grantedCount = grantedCount,
        requestFailed = requestFailed,
        recheck = { requestFailed = false; checkRequest++ },
        requestUsbPermission = {
            requestFailed = false
            if (!useRootAccess) {
                try {
                    val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
                    manager.deviceList.values.firstOrNull {
                        UsbStorageManager.isMassStorageDevice(it) && !manager.hasPermission(it)
                    }?.let { UsbStorageManager.requestPermission(context, it) }
                    checkRequest++
                } catch (_: Exception) {
                    requestFailed = true
                }
            }
        }
    )
}

@Composable
internal fun authorizationLabel(useRootAccess: Boolean, state: AuthorizationState): String = stringResource(when {
    state == AuthorizationState.CHECKING -> R.string.authorization_checking
    state == AuthorizationState.ERROR -> R.string.authorization_check_failed
    useRootAccess && state == AuthorizationState.GRANTED -> R.string.authorization_su_granted
    useRootAccess -> R.string.authorization_su_required
    state == AuthorizationState.NO_DEVICE -> R.string.authorization_usb_disconnected
    state == AuthorizationState.GRANTED -> R.string.authorization_usb_granted
    else -> R.string.authorization_usb_required
})

@Composable
internal fun connectionModeLabel(useRootAccess: Boolean): String = stringResource(
    if (useRootAccess) R.string.connection_mode_root else R.string.connection_mode_usb
)

/** Mode and permission only: a grant does not imply that a volume is readable or unlocked. */
@Composable
internal fun AuthorizationIndicator(useRootAccess: Boolean, status: AuthorizationStatus, onClick: () -> Unit) {
    val label = authorizationLabel(useRootAccess, status.state)
    val mode = connectionModeLabel(useRootAccess)
    val description = stringResource(R.string.connection_indicator_description, mode, label)
    val color = when (status.state) {
        AuthorizationState.GRANTED -> MaterialTheme.colorScheme.primary
        AuthorizationState.REQUIRED, AuthorizationState.ERROR -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    TextButton(onClick = onClick, modifier = Modifier.semantics { contentDescription = description }) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (status.state == AuthorizationState.CHECKING) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            } else {
                Icon(
                    imageVector = when (status.state) {
                        AuthorizationState.GRANTED -> Icons.Default.CheckCircle
                        AuthorizationState.REQUIRED, AuthorizationState.ERROR -> Icons.Default.Warning
                        else -> Icons.Default.Info
                    },
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(16.dp)
                )
            }
            Column {
                Text(mode, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                Text(label, color = color, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}
