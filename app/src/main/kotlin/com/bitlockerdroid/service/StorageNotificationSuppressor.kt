package com.bitlockerdroid.service

import android.content.Context
import android.service.notification.StatusBarNotification
import android.util.Log
import com.bitlockerdroid.util.PreferenceHelper
import com.bitlockerdroid.util.RootAccess
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** Cancel current verified BitLocker repair prompts without changing storage state or channels. */
object StorageNotificationSuppressor {
    private const val TAG = "StorageNotifSuppressor"
    private const val LISTENER_COMPONENT = "com.bitlockerdroid/.service.BitLockerNotificationListener"
    private val lastEnsureListenerTime = AtomicLong(0L)
    private val worker = Executors.newSingleThreadExecutor()

    private fun enabled(context: Context): Boolean =
        PreferenceHelper.isUseRootAccess(context) && PreferenceHelper.isSuppressCorruptNotification(context)

    private fun isCandidate(sbn: StatusBarNotification): Boolean = StorageWarningPolicy.isCandidate(
        sbn.packageName, sbn.id, sbn.notification.channelId, sbn.notification.category, sbn.tag
    )

    fun suppressForVolume(context: Context, nodePath: String) {
        if (!enabled(context)) return
        val tag = nodePath.substringAfterLast('/')
        if (!tag.matches(Regex("public:[0-9]+,[0-9]+"))) return
        worker.execute {
            ensureListenerEnabled()
            BitLockerNotificationListener.instance?.let { suppressActive(it, tag) }
        }
    }

    fun handleNotificationPosted(listener: BitLockerNotificationListener, sbn: StatusBarNotification) {
        if (!enabled(listener) || !isCandidate(sbn)) return
        worker.execute { suppressActive(listener, sbn.tag) }
    }

    fun checkAndSuppressActiveNotifications(listener: BitLockerNotificationListener) {
        if (enabled(listener)) worker.execute { suppressActive(listener) }
    }

    private fun suppressActive(listener: BitLockerNotificationListener, onlyTag: String? = null) {
        try {
            if (!enabled(listener) || !RootAccess.hasSu()) return
            val states = RootAccess.exec("sm list-volumes public 2>/dev/null")
                ?.takeIf { it.first == 0 }?.second?.let(StorageWarningPolicy::parseVolumeStates) ?: return
            val active = listener.activeNotifications ?: return
            for (sbn in active) {
                if (!isCandidate(sbn) || (onlyTag != null && sbn.tag != onlyTag)) continue
                val tag = sbn.tag ?: continue
                if (states[tag] != "unmountable") continue
                // Re-read metadata: reused vold tags cannot inherit another drive's identity.
                val verified = BitLockerDetector.readHeaderInfo("/dev/block/vold/$tag")?.verifiedMetadata == true
                if (StorageWarningPolicy.shouldSuppress(enabled(listener), RootAccess.hasSu(),
                        isCandidate(sbn), verified, states[tag])) {
                    listener.cancelNotification(sbn.key)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Verification failed; leaving storage notifications visible", e)
        }
    }

    fun ensureListenerEnabled() {
        val context = com.bitlockerdroid.util.ContextProvider.app
        if (!enabled(context) || !RootAccess.hasSu()) return
        val now = System.currentTimeMillis()
        if (now - lastEnsureListenerTime.get() < 30_000L) return
        lastEnsureListenerTime.set(now)
        RootAccess.exec("cmd notification allow_listener $LISTENER_COMPONENT 2>/dev/null")
    }
}
