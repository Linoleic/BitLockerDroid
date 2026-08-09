package com.bitlockerdroid.util

import android.content.Context
import android.content.SharedPreferences

/**
 * Thin wrapper over the module's SharedPreferences. All stored values are
 * either non-sensitive preferences or KeyGuardService-encrypted blobs.
 */
object PreferenceHelper {

    private const val PREFS = "bitlocker_prefs"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ------- remembered passwords (encrypted blobs) -------

    fun saveRememberedPassword(context: Context, devicePath: String, encryptedBlob: String) {
        prefs(context).edit()
            .putString("remembered_$devicePath", encryptedBlob)
            .apply()
    }

    fun getRememberedPassword(context: Context, devicePath: String): String? =
        prefs(context).getString("remembered_$devicePath", null)

    fun clearRememberedPassword(context: Context, devicePath: String) {
        prefs(context).edit().remove("remembered_$devicePath").apply()
    }

    /** Returns a copy of all saved preferences (for restoring remembered volumes). */
    fun all(context: Context): Map<String, Any?> =
        prefs(context).all.toMap()

    // ------- settings -------

    var autoUnlockRemembered: Boolean
        get() = prefs(ContextProvider.app).getBoolean("auto_unlock_remembered", true)
        set(v) = prefs(ContextProvider.app).edit().putBoolean("auto_unlock_remembered", v).apply()

    var notifyOnInsert: Boolean
        get() = prefs(ContextProvider.app).getBoolean("notify_on_insert", true)
        set(v) = prefs(ContextProvider.app).edit().putBoolean("notify_on_insert", v).apply()
}

/** Holds an application context once the app/service is running. */
object ContextProvider {
    @Volatile
    lateinit var app: Context
        internal set
}
