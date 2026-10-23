package com.bitlockerdroid.service

import android.content.Context
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.bitlockerdroid.R
import com.bitlockerdroid.util.BiometricAuthHelper
import com.bitlockerdroid.util.PreferenceHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The only place that decrypts persisted credentials. Never caches an authentication result. */
object SavedCredentialAccess {
    fun canAutomaticallyUnlock(
        context: Context, volumeGuid: String, manuallyLocked: Boolean, closing: Boolean
    ): Boolean = UnlockAuthorizationPolicy.canUseRememberedCredential(
        vaultProtected = PreferenceHelper.isBiometricVaultEnabled(context),
        globalAutoUnlock = PreferenceHelper.isOtgSilentAutoMount(context),
        volumeAutoUnlock = PreferenceHelper.isAutoUnlockEnabled(context, volumeGuid),
        manuallyLocked = manuallyLocked,
        closing = closing
    )

    fun readForAutomaticUnlock(
        context: Context, volumeGuid: String, manuallyLocked: Boolean, closing: Boolean
    ): String? = UnlockAuthorizationPolicy.forAutomaticUnlock(
        vaultProtected = PreferenceHelper.isBiometricVaultEnabled(context),
        globalAutoUnlock = PreferenceHelper.isOtgSilentAutoMount(context),
        volumeAutoUnlock = PreferenceHelper.isAutoUnlockEnabled(context, volumeGuid),
        manuallyLocked = manuallyLocked,
        closing = closing
    ) {
        decrypt(context, volumeGuid)?.takeIf {
            canAutomaticallyUnlock(context, volumeGuid, manuallyLocked, closing)
        }
    }

    /** Manual password entry may be prefilled only while vault protection is disabled. */
    fun readForPrefill(context: Context, volumeGuid: String?): String? =
        UnlockAuthorizationPolicy.forPrefill(PreferenceHelper.isBiometricVaultEnabled(context)) {
            decrypt(context, volumeGuid)?.takeUnless { PreferenceHelper.isBiometricVaultEnabled(context) }
        }

    /** Foreground unlock, reveal, and copy all follow the current vault protection switch. */
    fun readForUserAction(
        activity: FragmentActivity,
        volumeGuid: String,
        title: String,
        subtitle: String? = null,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit,
        onCancel: () -> Unit = {}
    ) {
        fun authenticate() = authenticateAndRead(activity, volumeGuid, title, subtitle, onSuccess, onError, onCancel)
        if (activity.isFinishing || activity.isDestroyed) return
        if (PreferenceHelper.isBiometricVaultEnabled(activity)) {
            authenticate()
            return
        }
        activity.lifecycleScope.launch {
            val raw = withContext(Dispatchers.IO) { readForPrefill(activity, volumeGuid) }
            if (activity.isFinishing || activity.isDestroyed) return@launch
            // Protection may have been enabled while the Keystore operation was running.
            if (PreferenceHelper.isBiometricVaultEnabled(activity)) authenticate()
            else if (raw != null) onSuccess(raw)
            else onError(activity.getString(R.string.credential_corrupted))
        }
    }

    /** Authentication is tied to this request's GUID; cancellation/error never releases a credential. */
    fun authenticateAndRead(
        activity: FragmentActivity,
        volumeGuid: String,
        title: String,
        subtitle: String? = null,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit,
        onCancel: () -> Unit = {}
    ) {
        BiometricAuthHelper.authenticate(
            activity = activity,
            title = title,
            subtitle = subtitle,
            onSuccess = {
                if (!activity.isFinishing && !activity.isDestroyed) {
                    activity.lifecycleScope.launch {
                        val raw = withContext(Dispatchers.IO) { decrypt(activity, volumeGuid) }
                        if (!activity.isFinishing && !activity.isDestroyed) {
                            if (raw != null) onSuccess(raw)
                            else onError(activity.getString(R.string.credential_corrupted))
                        }
                    }
                }
            },
            onError = onError,
            onCancel = onCancel
        )
    }

    private fun decrypt(context: Context, volumeGuid: String?): String? {
        val blob = PreferenceHelper.getRememberedPassword(context, volumeGuid) ?: return null
        return KeyGuardService.decrypt(blob)
    }
}
