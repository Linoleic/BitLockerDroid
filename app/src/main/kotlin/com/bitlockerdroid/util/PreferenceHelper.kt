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

    data class SavedCredential(
        val id: String,
        val displayLabel: String,
        val subtitle: String = "",
        val autoUnlock: Boolean = true
    )

    // ------- remembered passwords (encrypted blobs) -------

    private fun isInvalidGuid(volumeGuid: String?): Boolean {
        if (volumeGuid.isNullOrBlank()) return true
        if (volumeGuid.startsWith("/dev/")) return true
        if (volumeGuid.contains("4967d63b-2e29-4ad8-8399-f6a339e3d00")) return true
        return false
    }

    /** Saves an encrypted password keyed strictly by persistent Volume GUID. */
    fun saveRememberedPassword(
        context: Context,
        volumeGuid: String?,
        encryptedBlob: String,
        friendlyName: String? = null
    ) {
        if (isInvalidGuid(volumeGuid)) {
            return
        }
        val editor = prefs(context).edit()
        editor.putString("remembered_vol_$volumeGuid", encryptedBlob)
        if (!friendlyName.isNullOrBlank()) {
            editor.putString("name_vol_$volumeGuid", friendlyName)
        }
        val autoKey = "autounlock_vol_$volumeGuid"
        if (!prefs(context).contains(autoKey)) {
            editor.putBoolean(autoKey, true)
        }
        editor.apply()
    }

    /** Checks whether auto-unlock is enabled for a specific volume GUID. */
    fun isAutoUnlockEnabled(context: Context, volumeGuid: String?): Boolean {
        if (isInvalidGuid(volumeGuid)) return false
        val p = prefs(context)
        return p.getBoolean("autounlock_vol_$volumeGuid", p.getBoolean("autounlock_$volumeGuid", true))
    }

    /** Enables or disables auto-unlock for a specific volume GUID. */
    fun setAutoUnlockEnabled(context: Context, volumeGuid: String?, enabled: Boolean) {
        if (isInvalidGuid(volumeGuid)) return
        prefs(context).edit().putBoolean("autounlock_vol_$volumeGuid", enabled).apply()
    }

    /**
     * Retrieves remembered password by persistent [volumeGuid].
     * Strict GUID-only lookup. Never matches or migrates from other volumes or node paths.
     */
    fun getRememberedPassword(context: Context, volumeGuid: String?): String? {
        if (isInvalidGuid(volumeGuid)) return null
        val p = prefs(context)
        return p.getString("remembered_vol_$volumeGuid", null)
            ?: p.getString("remembered_$volumeGuid", null)
    }

    /** Clears remembered password and settings for a volume GUID. */
    fun clearRememberedPassword(context: Context, volumeGuid: String?) {
        if (volumeGuid.isNullOrBlank() || volumeGuid.startsWith("/dev/")) return
        val editor = prefs(context).edit()
        editor.remove("remembered_vol_$volumeGuid")
        editor.remove("remembered_$volumeGuid")
        editor.remove("name_vol_$volumeGuid")
        editor.remove("name_$volumeGuid")
        editor.remove("autounlock_vol_$volumeGuid")
        editor.remove("autounlock_$volumeGuid")
        editor.apply()
    }

    /** Purges any legacy keys indexed by volatile block node paths or static BitLocker spec GUIDs. */
    fun purgeLegacyNodeKeys(context: Context) {
        val p = prefs(context)
        val toRemove = p.all.keys.filter {
            it.contains("/dev/") || it.contains("public:") || it.contains("disk:") ||
            it.contains("4967d63b-2e29-4ad8-8399-f6a339e3d00")
        }
        if (toRemove.isNotEmpty()) {
            val editor = p.edit()
            toRemove.forEach { editor.remove(it) }
            editor.apply()
        }
    }

    /** Returns all remembered credential IDs. */
    fun getRememberedPaths(context: Context): List<String> {
        return prefs(context).all.keys
            .filter {
                it.startsWith("remembered_") &&
                !it.contains("/dev/") &&
                !it.contains("4967d63b-2e29-4ad8-8399-f6a339e3d00")
            }
            .map {
                if (it.startsWith("remembered_vol_")) it.removePrefix("remembered_vol_")
                else it.removePrefix("remembered_")
            }
    }

    /** Returns all remembered credentials with human-friendly display labels and per-volume autoUnlock flags. */
    fun getRememberedCredentials(context: Context): List<SavedCredential> {
        val p = prefs(context)
        return p.all.keys
            .filter {
                it.startsWith("remembered_") &&
                !it.contains("/dev/") &&
                !it.contains("4967d63b-2e29-4ad8-8399-f6a339e3d00")
            }
            .map { key ->
                val id = if (key.startsWith("remembered_vol_")) {
                    key.removePrefix("remembered_vol_")
                } else {
                    key.removePrefix("remembered_")
                }

                val savedName = p.getString("name_vol_$id", p.getString("name_$id", null))
                val isGuid = id.length >= 32 && id.contains("-")
                val shortGuid = if (isGuid) "${id.take(8)}...${id.takeLast(4)}" else id

                val displayLabel = when {
                    !savedName.isNullOrBlank() -> savedName
                    isGuid -> "BitLocker 加密卷 (${id.take(8)})"
                    else -> "USB 存储设备"
                }

                val subtitle = if (isGuid) "卷 GUID: $shortGuid" else "设备标识: $shortGuid"
                val autoUnlock = isAutoUnlockEnabled(context, id)

                SavedCredential(
                    id = id,
                    displayLabel = displayLabel,
                    subtitle = subtitle,
                    autoUnlock = autoUnlock
                )
            }
    }

    /** Returns count of devices with saved passwords. */
    fun getRememberedCount(context: Context): Int {
        return prefs(context).all.keys.count { it.startsWith("remembered_") && !it.contains("/dev/") }
    }

    /** Clears all remembered passwords across all devices. */
    fun clearAllRememberedPasswords(context: Context) {
        val editor = prefs(context).edit()
        for (key in prefs(context).all.keys) {
            if (key.startsWith("remembered_") || key.startsWith("name_") || key.startsWith("autounlock_")) {
                editor.remove(key)
            }
        }
        editor.apply()
    }

    /** Returns a copy of all saved preferences (for restoring remembered volumes). */
    fun all(context: Context): Map<String, Any?> =
        prefs(context).all.toMap()

    // ------- settings -------

    fun isDynamicColor(context: Context): Boolean =
        prefs(context).getBoolean("dynamic_color", true)

    fun setDynamicColor(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("dynamic_color", enabled).apply()
    }

    fun isAutoUnlockRemembered(context: Context): Boolean =
        prefs(context).getBoolean("auto_unlock_remembered", true)

    fun setAutoUnlockRemembered(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("auto_unlock_remembered", enabled).apply()
    }

    fun isNotifyOnInsert(context: Context): Boolean =
        prefs(context).getBoolean("notify_on_insert", true)

    fun setNotifyOnInsert(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("notify_on_insert", enabled).apply()
    }

    var dynamicColor: Boolean
        get() = try { prefs(ContextProvider.app).getBoolean("dynamic_color", true) } catch (_: Exception) { true }
        set(v) = try { prefs(ContextProvider.app).edit().putBoolean("dynamic_color", v).apply() } catch (_: Exception) {}

    var autoUnlockRemembered: Boolean
        get() = try { prefs(ContextProvider.app).getBoolean("auto_unlock_remembered", true) } catch (_: Exception) { true }
        set(v) = try { prefs(ContextProvider.app).edit().putBoolean("auto_unlock_remembered", v).apply() } catch (_: Exception) {}

    var notifyOnInsert: Boolean
        get() = try { prefs(ContextProvider.app).getBoolean("notify_on_insert", true) } catch (_: Exception) { true }
        set(v) = try { prefs(ContextProvider.app).edit().putBoolean("notify_on_insert", v).apply() } catch (_: Exception) {}

    /** When true, volumes are served strictly read-only without write permissions. */
    var mountReadOnly: Boolean
        get() = try { prefs(ContextProvider.app).getBoolean("mount_read_only", false) } catch (_: Exception) { false }
        set(v) = try { prefs(ContextProvider.app).edit().putBoolean("mount_read_only", v).apply() } catch (_: Exception) {}

    fun isVirtualMountEnabled(context: Context): Boolean =
        prefs(context).getBoolean("virtual_mount_enabled", true)

    fun setVirtualMountEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("virtual_mount_enabled", enabled).apply()
    }

    var virtualMountEnabled: Boolean
        get() = try { prefs(ContextProvider.app).getBoolean("virtual_mount_enabled", true) } catch (_: Exception) { true }
        set(v) = try { prefs(ContextProvider.app).edit().putBoolean("virtual_mount_enabled", v).apply() } catch (_: Exception) {}

    fun isNotificationsEnabled(context: Context): Boolean =
        prefs(context).getBoolean("notifications_enabled", true)

    fun setNotificationsEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("notifications_enabled", enabled).apply()
    }

    var notificationsEnabled: Boolean
        get() = try { prefs(ContextProvider.app).getBoolean("notifications_enabled", true) } catch (_: Exception) { true }
        set(v) = try { prefs(ContextProvider.app).edit().putBoolean("notifications_enabled", v).apply() } catch (_: Exception) {}
}

/** Holds an application context once the app/service is running. */
object ContextProvider {
    @Volatile
    lateinit var app: Context
        internal set
}
