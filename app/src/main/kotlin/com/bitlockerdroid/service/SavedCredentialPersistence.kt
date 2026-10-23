package com.bitlockerdroid.service

/** Applies an explicit save/forget choice while leaving unspecified persistence untouched. */
internal object SavedCredentialPersistence {
    fun update(remember: Boolean?, save: () -> Unit, forget: () -> Unit) {
        when (remember) {
            null -> Unit
            true -> save()
            false -> forget()
        }
    }
}
