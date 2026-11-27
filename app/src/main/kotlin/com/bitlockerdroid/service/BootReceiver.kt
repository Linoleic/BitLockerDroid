package com.bitlockerdroid.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.bitlockerdroid.util.ContextProvider

/**
 * Starts the core service after boot so the module can react to storage
 * insertions even before the user opens the app.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val appCtx = context.applicationContext
        ContextProvider.app = appCtx
        val pending = goAsync()
        Thread({
            try {
                try {
                    appCtx.startService(Intent(appCtx, BitLockerCoreService::class.java))
                } catch (e: Exception) {
                    com.bitlockerdroid.util.LogFile.write("app", "BootReceiver: failed to start service: ${e.message}")
                }
                try { Thread.sleep(2000) } catch (_: InterruptedException) {}
                BitLockerDetector.scanAndDetect(appCtx)
            } finally {
                pending.finish()
            }
        }, "BitLocker-BootScan").start()
    }
}
