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

    override fun onCreate() {
        super.onCreate()
        ContextProvider.app = applicationContext
        LogFile.init(applicationContext)
        LogFile.write("app", "=== BitLockerCoreService started ===")
        PreferenceHelper.notifyOnInsert // initialize access
        UnlockManager.ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
    }
}
