package com.bitlockerdroid.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.bitlockerdroid.util.LogFile

/** Receives notifications; only verified BitLocker public-volume repair warnings are filtered. */
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
