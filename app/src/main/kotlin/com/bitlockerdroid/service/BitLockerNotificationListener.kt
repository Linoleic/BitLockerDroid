package com.bitlockerdroid.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.bitlockerdroid.util.LogFile

/**
 * Intercepts and suppresses system notifications warning about unmountable
 * BitLocker encrypted volumes (e.g. "USB 驱动器出现问题。点按即可修复").
 *
 * Strictly verified: Only cancels notifications whose tag or volume ID matches
 * a verified BitLocker partition node (e.g. "public:8,97"). Non-BitLocker corrupt drives
 * are NEVER suppressed, preserving Android's warnings for genuinely defective media.
 */
class BitLockerNotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "BitLockerNotifListener"

        @Volatile
        var instance: BitLockerNotificationListener? = null
            private set
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        Log.i(TAG, "BitLockerNotificationListener connected")
        LogFile.write("app", "BitLockerNotificationListener connected")
        StorageNotificationSuppressor.checkAndSuppressActiveNotifications(this)
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        if (instance == this) {
            instance = null
        }
        Log.i(TAG, "BitLockerNotificationListener disconnected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        StorageNotificationSuppressor.handleNotificationPosted(this, sbn)
    }
}
