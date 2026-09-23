package com.bitlockerdroid.util

import android.content.Context
import android.content.ContextWrapper
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.bitlockerdroid.R

/**
 * Extension to reliably extract the hosting FragmentActivity from Context (even if wrapped).
 */
fun Context.findFragmentActivity(): FragmentActivity? {
    var ctx = this
    while (ctx is ContextWrapper) {
        if (ctx is FragmentActivity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

/**
 * Helper for biometric (fingerprint, face) and device credential authentication.
 * Wraps AndroidX BiometricPrompt with hardware availability checks and fallback to device PIN/Pattern.
 */
object BiometricAuthHelper {

    enum class BiometricStatus {
        AVAILABLE,
        NOT_ENROLLED,
        NO_HARDWARE,
        UNAVAILABLE
    }

    /**
     * Inspects whether biometric hardware (Fingerprint, Face, Iris) is present and enrolled.
     */
    fun checkBiometricStatus(context: Context): BiometricStatus {
        val bm = BiometricManager.from(context)
        return when (bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)) {
            BiometricManager.BIOMETRIC_SUCCESS -> BiometricStatus.AVAILABLE
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> BiometricStatus.NOT_ENROLLED
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> BiometricStatus.NO_HARDWARE
            else -> BiometricStatus.UNAVAILABLE
        }
    }

    /**
     * Checks if biometric hardware is ready and at least one biometric is enrolled.
     */
    fun hasBiometricsEnrolled(context: Context): Boolean {
        return checkBiometricStatus(context) == BiometricStatus.AVAILABLE
    }

    /**
     * Checks if either biometrics or device credentials (PIN / Pattern / Password) are configured.
     */
    fun canAuthenticate(context: Context): Boolean {
        val bm = BiometricManager.from(context)
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
        return bm.canAuthenticate(authenticators) == BiometricManager.BIOMETRIC_SUCCESS
    }

    /**
     * Launches the system biometric authentication dialog.
     * Supports both Biometrics (Fingerprint/Face) and device PIN/pattern fallback.
     */
    fun authenticate(
        activity: FragmentActivity,
        title: String,
        subtitle: String? = null,
        description: String? = null,
        onSuccess: () -> Unit,
        onError: (String) -> Unit = {},
        onCancel: () -> Unit = {}
    ) {
        if (!canAuthenticate(activity)) {
            onError(activity.getString(R.string.biometric_not_supported))
            return
        }

        val executor = ContextCompat.getMainExecutor(activity)
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .apply {
                subtitle?.let { setSubtitle(it) }
                description?.let { setDescription(it) }
            }
            .setAllowedAuthenticators(authenticators)
            .build()

        val prompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    when (errorCode) {
                        BiometricPrompt.ERROR_USER_CANCELED,
                        BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                        BiometricPrompt.ERROR_CANCELED -> onCancel()
                        else -> onError(errString.toString())
                    }
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    // Fingerprint not recognized; system dialog displays prompt retry automatically
                }
            }
        )

        try {
            prompt.authenticate(promptInfo)
        } catch (e: Exception) {
            LogFile.write("app", "BiometricAuthHelper authenticate exception: ${e.message}")
            onError(e.message ?: activity.getString(R.string.biometric_auth_failed))
        }
    }
}
