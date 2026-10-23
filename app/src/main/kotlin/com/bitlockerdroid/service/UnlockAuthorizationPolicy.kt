package com.bitlockerdroid.service

/** Centralizes the conditions for using a remembered credential. */
object UnlockAuthorizationPolicy {
    fun canUseRememberedCredential(
        vaultProtected: Boolean,
        globalAutoUnlock: Boolean,
        volumeAutoUnlock: Boolean,
        manuallyLocked: Boolean,
        closing: Boolean,
    ): Boolean =
        !vaultProtected &&
            globalAutoUnlock &&
            volumeAutoUnlock &&
            !manuallyLocked &&
            !closing

    fun canPrefillRememberedCredential(vaultProtected: Boolean): Boolean = !vaultProtected

    /** Reads a remembered credential only when every automatic-unlock condition allows it. */
    fun <T> forAutomaticUnlock(
        vaultProtected: Boolean,
        globalAutoUnlock: Boolean,
        volumeAutoUnlock: Boolean,
        manuallyLocked: Boolean,
        closing: Boolean,
        read: () -> T,
    ): T? {
        if (!canUseRememberedCredential(
                vaultProtected = vaultProtected,
                globalAutoUnlock = globalAutoUnlock,
                volumeAutoUnlock = volumeAutoUnlock,
                manuallyLocked = manuallyLocked,
                closing = closing,
            )
        ) {
            return null
        }
        return read()
    }

    /** Reads a remembered credential for prefill only when the vault is not protected. */
    fun <T> forPrefill(vaultProtected: Boolean, read: () -> T): T? {
        if (!canPrefillRememberedCredential(vaultProtected)) return null
        return read()
    }
}
