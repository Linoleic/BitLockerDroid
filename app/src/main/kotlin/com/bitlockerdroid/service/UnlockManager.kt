package com.bitlockerdroid.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.bitlockerdroid.R
import com.bitlockerdroid.ui.BitLockerSettingsActivity
import com.bitlockerdroid.ui.UnlockDialogActivity
import com.bitlockerdroid.util.PreferenceHelper
import com.bitlockerdroid.util.LogFile

/**
 * Orchestrates the unlock flow: holds the currently unlocked volumes, raises
 * the unlock dialog when a new BitLocker device is detected, and notifies the
 * DocumentsProvider of state changes.
 */
object UnlockManager {

    private const val TAG = "UnlockManager"
    private const val CHANNEL_ID = "bitlocker_status"

    /** devicePath -> live core session */
    private val sessions = HashMap<String, DislockerCore>()

    /** devicePath -> detected-but-not-yet-unlocked volume. */
    private val detected = HashMap<String, DetectedVolume>()
    private val lock = Any()

    /** Active native handles kept alive while a volume is unlocked. */
    val unlockedVolumes: List<UnlockedVolume>
        get() = synchronized(lock) {
            sessions.map { (path, core) ->
                UnlockedVolume(path, core.info.volumeSize, core.volumeLabel)
            }
        }

    /**
     * Volumes that carry a BitLocker signature but are not unlocked yet.
     * Persists across reformats because the entry is keyed by the block node
     * path, and re-registered on each successful signature scan (the prompt may
     * be suppressed if the system hook's in-memory "confirmed" set survived the
     * reformat). Visible in the management UI so the user can unlock manually.
     */
    val detectedVolumes: List<DetectedVolume>
        get() = synchronized(lock) {
            detected.values.toList()
        }

    fun isUnlocked(devicePath: String): Boolean = synchronized(lock) {
        sessions.containsKey(devicePath)
    }

    /** Marks [devicePath] as a BitLocker volume needing unlock. Idempotent. */
    fun registerDetected(devicePath: String) {
        synchronized(lock) {
            if (!sessions.containsKey(devicePath)) {
                detected[devicePath] = DetectedVolume(devicePath)
            }
        }
    }

    /** Clears a volume from the detected (locked) list, e.g. after it is
     *  unlocked or physically removed. */
    fun forgetDetected(devicePath: String) {
        synchronized(lock) {
            detected.remove(devicePath)
        }
    }

    /** True if [devicePath] is either unlocked or pending unlock in the list. */
    fun isKnown(devicePath: String): Boolean = synchronized(lock) {
        sessions.containsKey(devicePath) || detected.containsKey(devicePath)
    }

    /** Called by the unlock dialog with the user password. */
    fun unlockWithPassword(
        context: Context,
        devicePath: String,
        offset: Long,
        password: String,
        remember: Boolean
    ): Result<DislockerCore> {
        return try {
            val core = DislockerCore.open(devicePath, offset, password)
            synchronized(lock) {
                sessions[devicePath]?.close()
                sessions[devicePath] = core
                detected.remove(devicePath)
            }
            // Save the encrypted password only if the user asked to remember it.
            // The remembered password powers auto-unlock on Scan/detection and
            // lets the DocumentsProvider re-unlock after this process is killed.
            if (remember) {
                KeyGuardService.encrypt(password)?.let { blob ->
                    PreferenceHelper.saveRememberedPassword(context, devicePath, blob)
                }
            } else {
                PreferenceHelper.clearRememberedPassword(context, devicePath)
            }
            Result.success(core)
        } catch (e: UnlockException) {
            Log.w(TAG, "unlock failed for $devicePath", e)
            // Keep it visible in the management UI so the user can retry.
            registerDetected(devicePath)
            Result.failure(e)
        } catch (e: Exception) {
            Log.e(TAG, "unlock error", e)
            registerDetected(devicePath)
            Result.failure(e)
        }
    }

    /**
     * Ensures a session for [devicePath] exists, re-unlocking from the saved
     * encrypted password if this process lost it (e.g. after a kill). Returns
     * the core or null.
     */
    fun ensureUnlocked(context: Context, devicePath: String, offset: Long): DislockerCore? {
        synchronized(lock) {
            sessions[devicePath]?.let { return it }
        }
        val blob = PreferenceHelper.getRememberedPassword(context, devicePath) ?: return null
        val password = KeyGuardService.decrypt(blob) ?: return null
        // Re-unlock with the saved password; keep it remembered.
        val result = unlockWithPassword(context, devicePath, offset, password, true)
        return result.getOrNull()
    }

    fun lock(devicePath: String) {
        synchronized(lock) {
            sessions.remove(devicePath)?.close()
            // Keep it on the detected list so the user can re-unlock from the UI.
            detected[devicePath] = DetectedVolume(devicePath)
        }
    }

    /**
     * Removes detected-but-not-unlocked entries whose block node is no longer
     * present on the bus (device unplugged). Sessions currently unlocked are
     * left alone; the DocumentsProvider re-locks them lazily on next access.
     */
    fun forgetDetectedMissing(presentNodes: List<String>) {
        synchronized(lock) {
            val present = presentNodes.toHashSet()
            detected.keys.filter { !present.contains(it) }.forEach { detected.remove(it) }
        }
    }

    /** Registers an already-opened core session (recovery-key path). */
    fun registerDirect(core: DislockerCore, context: Context?) {
        synchronized(lock) {
            sessions[core.devicePath]?.close()
            sessions[core.devicePath] = core
            detected.remove(core.devicePath)
        }
    }

    fun get(devicePath: String): DislockerCore? = synchronized(lock) {
        sessions[devicePath]
    }

    /**
     * Restores all remembered (saved-password) volumes into memory so the
     * DocumentsProvider can serve them even after this process was killed.
     * Reads saved volume paths from preferences (no su enumeration needed, so
     * it works even if the provider process cannot run su). Returns the count
     * restored.
     */
    fun restoreRemembered(context: Context): Int {
        val prefs = PreferenceHelper.all(context)
        var restored = 0
        for ((key, value) in prefs) {
            if (!key.startsWith("remembered_")) continue
            val path = key.removePrefix("remembered_")
            val blob = value as? String ?: continue
            val password = KeyGuardService.decrypt(blob) ?: continue
            val has = synchronized(lock) { sessions.containsKey(path) }
            if (has) continue
            val r = unlockWithPassword(context, path, 0, password, true)
            if (r.isSuccess) {
                restored++
                Log.i(TAG, "restoreRemembered: unlocked $path")
            } else {
                Log.w(TAG, "restoreRemembered: failed $path: ${r.exceptionOrNull()?.message}")
            }
        }
        return restored
    }

    /** Called when a BitLocker volume is found on the bus (from the user's Scan
     *  action). Records the volume so it appears in the management UI, and
     *  auto-unlocks if a saved password exists AND auto-unlock is enabled.
     *  No notification is posted — detection is user-driven via the Scan button. */
    fun onDeviceDetected(context: Context, devicePath: String, offset: Long) {
        Handler(Looper.getMainLooper()).post {
            if (isUnlocked(devicePath)) return@post

            // Record the volume so it stays reachable from the management UI.
            registerDetected(devicePath)

            // Auto-unlock only if the user enabled it and a saved password exists.
            if (!PreferenceHelper.autoUnlockRemembered) return@post
            val rememberBlob = PreferenceHelper.getRememberedPassword(context, devicePath)
            if (rememberBlob != null) {
                val password = KeyGuardService.decrypt(rememberBlob)
                if (password != null) {
                    val r = unlockWithPassword(context, devicePath, offset, password, true)
                    if (r.isFailure) {
                        // Stale password (drive was reformatted/re-encrypted):
                        // keep it on the detected list; the user unlocks from the UI.
                        LogFile.write(
                            "app",
                            "auto-unlock failed for $devicePath: ${r.exceptionOrNull()?.message}"
                        )
                    }
                }
            }
        }
    }

    fun showUnlockDialog(context: Context, devicePath: String, offset: Long) {
        val intent = Intent(context, UnlockDialogActivity::class.java).apply {
            putExtra(UnlockDialogActivity.EXTRA_DEVICE_PATH, devicePath)
            putExtra(UnlockDialogActivity.EXTRA_OFFSET, offset)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        try {
            context.startActivity(intent)
            com.bitlockerdroid.util.LogFile.write("app", "unlock dialog launched for $devicePath")
        } catch (e: Exception) {
            Log.e(TAG, "cannot show unlock dialog", e)
            com.bitlockerdroid.util.LogFile.write("app", "cannot show unlock dialog: ${e.message}")
        }
    }

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "BitLocker status",
            NotificationManager.IMPORTANCE_LOW
        )
        nm.createNotificationChannel(channel)
    }
}

data class UnlockedVolume(
    val devicePath: String,
    val size: Long,
    val label: String
)

/** A BitLocker volume detected on the bus but not yet unlocked. */
data class DetectedVolume(
    val devicePath: String
)
