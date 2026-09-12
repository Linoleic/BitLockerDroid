package com.bitlockerdroid.service

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.bitlockerdroid.util.ContextProvider
import com.bitlockerdroid.util.LogFile
import com.bitlockerdroid.util.PreferenceHelper

/**
 * Lifecycle service that holds the application context used by the singletons.
 *
 * Detection is user-driven: the Settings screen's Scan button runs the block
 * scan; there is no periodic scan or insertion notification.
 */
class BitLockerCoreService : Service() {

    private val usbReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            val action = intent?.action ?: return
            LogFile.write("app", "usbReceiver triggered: action=$action")
            if (action == android.hardware.usb.UsbManager.ACTION_USB_DEVICE_DETACHED) {
                UnlockManager.onUsbDetached()
            }
            Thread {
                try { Thread.sleep(800) } catch (_: Exception) {}
                context?.let {
                    BitLockerDetector.scanAndDetect(it)
                }
            }.start()
        }
    }

    override fun onCreate() {
        super.onCreate()
        ContextProvider.app = applicationContext
        LogFile.init(applicationContext)
        LogFile.write("app", "=== BitLockerCoreService started ===")
        PreferenceHelper.notifyOnInsert // initialize access
        UnlockManager.ensureChannel(this)

        val filter = android.content.IntentFilter().apply {
            addAction(android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(android.hardware.usb.UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        try {
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                registerReceiver(usbReceiver, filter, android.content.Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(usbReceiver, filter)
            }
        } catch (e: Exception) {
            LogFile.write("app", "failed to register usbReceiver: ${e.message}")
        }
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(usbReceiver)
        } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val volumes = UnlockManager.unlockedVolumes
        val fromFgs = intent?.getBooleanExtra(EXTRA_FROM_FGS, false) == true
        if (volumes.isNotEmpty()) {
            updateForegroundNotification(volumes)
        } else {
            if (fromFgs) {
                // Must satisfy startForeground contract if launched via startForegroundService
                val notification = androidx.core.app.NotificationCompat.Builder(this, UnlockManager.CHANNEL_ID)
                    .setSmallIcon(com.bitlockerdroid.R.drawable.ic_notification)
                    .setContentTitle(getString(com.bitlockerdroid.R.string.app_name))
                    .setContentText("BitLocker 存储服务")
                    .setPriority(androidx.core.app.NotificationCompat.PRIORITY_MIN)
                    .build()
                try {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        startForeground(
                            NOTIFICATION_ID,
                            notification,
                            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                        )
                    } else {
                        startForeground(NOTIFICATION_ID, notification)
                    }
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } catch (_: Exception) {}
            } else {
                try {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } catch (_: Exception) {}
            }
        }
        return START_NOT_STICKY
    }

    private fun updateForegroundNotification(volumes: List<UnlockedVolume> = UnlockManager.unlockedVolumes) {
        if (volumes.isNotEmpty()) {
            val label = volumes.first().label.ifBlank { volumes.first().deviceName.ifBlank { "BitLocker 加密卷" } }
            val title = getString(com.bitlockerdroid.R.string.app_name)
            val text = "$label 已挂载 (${volumes.size} 个活动卷)"

            val openIntent = Intent(this, com.bitlockerdroid.ui.BitLockerSettingsActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = android.app.PendingIntent.getActivity(
                this, 0, openIntent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )

            val notification = androidx.core.app.NotificationCompat.Builder(this, UnlockManager.CHANNEL_ID)
                .setSmallIcon(com.bitlockerdroid.R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_LOW)
                .build()

            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                    )
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
            } catch (e: Exception) {
                LogFile.write("app", "startForeground failed: ${e.message}")
            }
        } else {
            try {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val NOTIFICATION_ID = 2001
        private const val EXTRA_FROM_FGS = "extra_from_fgs"

        fun updateForegroundState(context: android.content.Context) {
            val hasVolumes = UnlockManager.unlockedVolumes.isNotEmpty()
            val intent = Intent(context, BitLockerCoreService::class.java).apply {
                action = "com.bitlockerdroid.action.UPDATE_FOREGROUND"
                putExtra(EXTRA_FROM_FGS, hasVolumes)
            }
            try {
                if (hasVolumes) {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        context.startForegroundService(intent)
                    } else {
                        context.startService(intent)
                    }
                } else {
                    // When no volumes are unlocked, use startService to avoid FGS timeout crash
                    context.startService(intent)
                }
            } catch (e: Exception) {
                LogFile.write("app", "updateForegroundState failed: ${e.message}")
            }
        }
    }
}
