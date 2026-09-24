package com.bitlockerdroid.share

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.bitlockerdroid.R
import com.bitlockerdroid.ui.BitLockerSettingsActivity

class LanShareService : Service() {

    companion object {
        private const val TAG = "LanShareService"
        const val CHANNEL_ID = "lan_share_channel"
        const val NOTIFICATION_ID = 1003

        const val ACTION_START_SHARING = "com.bitlockerdroid.action.START_SHARING"
        const val ACTION_STOP_SHARING = "com.bitlockerdroid.action.STOP_SHARING"
        const val ACTION_STOP_ALL = "com.bitlockerdroid.action.STOP_ALL_SHARING"
        const val ACTION_UPDATE_NOTIFICATION = "com.bitlockerdroid.action.UPDATE_SHARE_NOTIFICATION"

        const val EXTRA_VOLUME_GUID = "extra_volume_guid"
        const val EXTRA_VOLUME_LABEL = "extra_volume_label"
        const val EXTRA_PORT = "extra_port"
        const val EXTRA_PRIMARY_URL = "extra_primary_url"
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private var currentLabel = ""
    private var currentUrl = ""

    private val detachReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            android.util.Log.i(TAG, "Hardware detachment broadcast received: $action")
            @Suppress("DEPRECATION")
            val dev = intent.getParcelableExtra<android.hardware.usb.UsbDevice>(android.hardware.usb.UsbManager.EXTRA_DEVICE)
            if (dev != null) {
                com.bitlockerdroid.usb.UsbStorageManager.onDeviceDetached(dev)
            }
            com.bitlockerdroid.service.UnlockManager.onUsbDetached()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        val usbFilter = android.content.IntentFilter(android.hardware.usb.UsbManager.ACTION_USB_DEVICE_DETACHED)
        val mediaFilter = android.content.IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_BAD_REMOVAL)
            addAction(Intent.ACTION_MEDIA_UNMOUNTED)
            addAction(Intent.ACTION_MEDIA_REMOVED)
            addAction(Intent.ACTION_MEDIA_EJECT)
            addDataScheme("file")
        }
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(detachReceiver, usbFilter, Context.RECEIVER_EXPORTED)
                registerReceiver(detachReceiver, mediaFilter, Context.RECEIVER_EXPORTED)
            } else {
                registerReceiver(detachReceiver, usbFilter)
                registerReceiver(detachReceiver, mediaFilter)
            }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Failed to register detachReceiver: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: return START_NOT_STICKY

        when (action) {
            ACTION_START_SHARING -> {
                acquireLocks()
                currentLabel = intent.getStringExtra(EXTRA_VOLUME_LABEL) ?: getString(R.string.app_name)
                currentUrl = intent.getStringExtra(EXTRA_PRIMARY_URL) ?: ""
                val notification = buildNotification(currentLabel, currentUrl)
                startForeground(NOTIFICATION_ID, notification)
            }
            ACTION_UPDATE_NOTIFICATION -> {
                currentLabel = intent.getStringExtra(EXTRA_VOLUME_LABEL) ?: currentLabel
                currentUrl = intent.getStringExtra(EXTRA_PRIMARY_URL) ?: currentUrl
                val notification = buildNotification(currentLabel, currentUrl)
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(NOTIFICATION_ID, notification)
            }
            ACTION_STOP_SHARING -> {
                val guid = intent.getStringExtra(EXTRA_VOLUME_GUID)
                if (guid != null) {
                    LanShareManager.stopSharing(this, guid)
                }
                if (!LanShareManager.hasActiveServers()) {
                    dismissNotificationAndStop()
                }
            }
            ACTION_STOP_ALL -> {
                LanShareManager.stopAllServersDirectly()
                dismissNotificationAndStop()
            }
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(detachReceiver)
        } catch (_: Exception) {}
        releaseLocks()
        super.onDestroy()
    }

    private fun dismissNotificationAndStop() {
        releaseLocks()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        nm?.cancel(NOTIFICATION_ID)
        stopSelf()
    }

    private fun acquireLocks() {
        try {
            if (wakeLock == null || !wakeLock!!.isHeld) {
                val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
                wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BitLockerDroid:LanShareWakeLock")?.apply {
                    acquire(12 * 60 * 60 * 1000L) // 12 hours max
                }
            }

            if (wifiLock == null || !wifiLock!!.isHeld) {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                @Suppress("DEPRECATION")
                val lockType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF
                } else {
                    WifiManager.WIFI_MODE_FULL
                }
                wifiLock = wm?.createWifiLock(lockType, "BitLockerDroid:LanShareWifiLock")?.apply {
                    acquire()
                }
            }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Failed to acquire WakeLock / WifiLock: ${e.message}")
        }
    }

    private fun releaseLocks() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {}
        try {
            if (wifiLock?.isHeld == true) wifiLock?.release()
        } catch (_: Exception) {}
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = getString(R.string.lan_share_channel_name)
            val desc = getString(R.string.lan_share_channel_desc)
            val channel = NotificationChannel(CHANNEL_ID, name, NotificationManager.IMPORTANCE_LOW).apply {
                description = desc
                setShowBadge(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(label: String, url: String): Notification {
        val appIntent = Intent(this, BitLockerSettingsActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val appPendingIntent = PendingIntent.getActivity(
            this, 0, appIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, LanShareService::class.java).apply {
            action = ACTION_STOP_ALL
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shield_check)
            .setContentTitle(getString(R.string.lan_share_notif_title, label))
            .setContentText(if (url.isNotEmpty()) url else getString(R.string.lan_share_notif_running))
            .setSubText(getString(R.string.lan_share_title))
            .setOngoing(true)
            .setContentIntent(appPendingIntent)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.lan_share_stop_action),
                stopPendingIntent
            )
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
