package com.bitlockerdroid.service

import android.content.Context
import android.service.notification.StatusBarNotification
import android.util.Log
import com.bitlockerdroid.util.LogFile
import com.bitlockerdroid.util.PreferenceHelper
import com.bitlockerdroid.util.RootAccess
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Suppresses Android system notifications warning about unmountable USB drives
 * (e.g., "xxx USB 驱动器出现问题。点按即可修复" / "Issue with USB drive. Tap to fix").
 *
 * Strict safety guarantee:
 * Only suppresses notifications for volumes verified to have a valid BitLocker
 * signature (`-FVE-FS-` or `MSWIN4.1`). Genuine hardware errors or corrupt non-BitLocker
 * drives are NEVER suppressed.
 */
object StorageNotificationSuppressor {

    private const val TAG = "StorageNotifSuppressor"
    private const val LISTENER_COMPONENT = "com.bitlockerdroid/.service.BitLockerNotificationListener"

    /** Set of verified BitLocker volume tags (e.g. "public:8,97"). */
    private val confirmedBitLockerTags = ConcurrentHashMap.newKeySet<String>()

    private val lastEnsureListenerTime = AtomicLong(0L)

    /**
     * Checks whether [tag] corresponds to a verified BitLocker partition.
     * If not already cached, checks the block node directly for the BitLocker header signature.
     */
    fun isTagBitLocker(tag: String?): Boolean {
        if (tag.isNullOrBlank()) return false
        if (confirmedBitLockerTags.contains(tag)) return true

        if (tag.startsWith("public:") || tag.startsWith("disk:")) {
            val nodePath = "/dev/block/vold/$tag"
            val sig = BitLockerDetector.readSignature(nodePath)
            if (sig == "-FVE-FS-" || sig == "MSWIN4.1") {
                confirmedBitLockerTags.add(tag)
                return true
            }
        }
        return false
    }

    /**
     * Registers a verified BitLocker partition and triggers immediate suppression
     * of any system corrupt/unmountable notifications for it.
     */
    fun registerConfirmedBitLockerVolume(context: Context, nodePath: String) {
        val tag = nodePath.substringAfterLast('/')
        if (tag.isNotEmpty()) {
            confirmedBitLockerTags.add(tag)
        }
        suppressForVolume(context, nodePath)
    }

    /**
     * Suppresses any system notifications for [nodePath] using a dual strategy:
     * 1. NotificationListenerService auto-dismiss if connected.
     * 2. Root-level `cmd notification snooze` and `sm unmount` fallback.
     */
    fun suppressForVolume(context: Context, nodePath: String) {
        if (!PreferenceHelper.isSuppressCorruptNotification(context)) return

        val tag = nodePath.substringAfterLast('/')
        if (tag.isBlank()) return
        confirmedBitLockerTags.add(tag)

        Thread {
            try {
                ensureListenerEnabled()

                // 1. Dismiss via active NotificationListener instance
                BitLockerNotificationListener.instance?.let { listener ->
                    val active = try { listener.activeNotifications } catch (_: Exception) { null }
                    active?.forEach { sbn ->
                        if ((sbn.packageName == "com.android.systemui" || sbn.packageName == "android") &&
                            (sbn.tag == tag || sbn.key.contains("|$tag|"))
                        ) {
                            Log.i(TAG, "Cancelling notification via listener for $tag (key=${sbn.key})")
                            LogFile.write("app", "StorageNotificationSuppressor: cancelled notification via listener for $tag")
                            try { listener.cancelNotification(sbn.key) } catch (_: Exception) {}
                        }
                    }
                }

                // 2. Root snooze fallback: query active keys from cmd notification list
                val listOutput = RootAccess.exec("cmd notification list 2>/dev/null")?.second
                listOutput?.lines()?.forEach { line ->
                    val key = line.trim()
                    if (key.contains("|$tag|") &&
                        (key.contains("com.android.systemui") || key.contains("android"))
                    ) {
                        LogFile.write("app", "StorageNotificationSuppressor: snoozing system corrupt warning for $tag: $key")
                        RootAccess.exec("cmd notification snooze --for 31536000000000 '$key' 2>/dev/null")
                    }
                }

                // 3. Inform vold to unmount (harmless if already unmounted, clears transient state)
                RootAccess.exec("sm unmount '$tag' 2>/dev/null")
            } catch (e: Exception) {
                Log.w(TAG, "suppressForVolume failed for $nodePath", e)
            }
        }.start()
    }

    /**
     * Intercepts incoming notifications posted by SystemUI or the Android system.
     * Dismisses the notification immediately if and only if the tag is verified BitLocker.
     */
    fun handleNotificationPosted(listener: BitLockerNotificationListener, sbn: StatusBarNotification) {
        if (!PreferenceHelper.isSuppressCorruptNotification(listener)) return
        val pkg = sbn.packageName
        if (pkg != "com.android.systemui" && pkg != "android") return

        val tag = sbn.tag ?: return
        if (isTagBitLocker(tag)) {
            LogFile.write("app", "StorageNotificationSuppressor: suppressing incoming corrupt warning for $tag (key=${sbn.key})")
            try {
                listener.cancelNotification(sbn.key)
            } catch (e: Exception) {
                Log.w(TAG, "cancelNotification failed for ${sbn.key}", e)
            }
            RootAccess.exec("cmd notification snooze --for 31536000000000 '${sbn.key}' 2>/dev/null")
        }
    }

    /**
     * Checks all currently active notifications when the listener connects and suppresses
     * any existing corrupt/format warnings for verified BitLocker volumes.
     */
    fun checkAndSuppressActiveNotifications(listener: BitLockerNotificationListener) {
        if (!PreferenceHelper.isSuppressCorruptNotification(listener)) return

        val active = try { listener.activeNotifications } catch (_: Exception) { null } ?: return
        for (sbn in active) {
            if (sbn.packageName == "com.android.systemui" || sbn.packageName == "android") {
                val tag = sbn.tag
                if (tag != null && isTagBitLocker(tag)) {
                    LogFile.write("app", "StorageNotificationSuppressor: cancelling active notification for $tag (key=${sbn.key})")
                    try {
                        listener.cancelNotification(sbn.key)
                    } catch (_: Exception) {}
                    RootAccess.exec("cmd notification snooze --for 31536000000000 '${sbn.key}' 2>/dev/null")
                }
            }
        }
    }

    /** Clears tracked tags for missing nodes when drives are unplugged. */
    fun forgetMissing(presentNodes: Collection<String>) {
        val presentTags = presentNodes.map { it.substringAfterLast('/') }.toSet()
        confirmedBitLockerTags.retainAll(presentTags)
    }

    /** Clears a specific node tag. */
    fun forgetNode(nodePath: String) {
        val tag = nodePath.substringAfterLast('/')
        confirmedBitLockerTags.remove(tag)
    }

    /**
     * Grants BIND_NOTIFICATION_LISTENER_SERVICE permission to BitUnlocker via root
     * command without needing user interaction in system settings.
     */
    fun ensureListenerEnabled() {
        val now = System.currentTimeMillis()
        if (now - lastEnsureListenerTime.get() < 30_000L) return
        lastEnsureListenerTime.set(now)

        if (RootAccess.hasSu()) {
            RootAccess.exec("cmd notification allow_listener $LISTENER_COMPONENT 2>/dev/null")
        }
    }
}
