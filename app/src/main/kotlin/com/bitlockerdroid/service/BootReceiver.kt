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
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_LOCKED_BOOT_COMPLETED
        ) {
            ContextProvider.app = context.applicationContext
            val serviceIntent = Intent(context, BitLockerCoreService::class.java).apply {
                putExtra("extra_from_fgs", true)
            }
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            } catch (e: Exception) {
                com.bitlockerdroid.util.LogFile.write("app", "BootReceiver: failed to start service: ${e.message}")
            }
            Thread {
                try { Thread.sleep(2000) } catch (_: Exception) {}
                BitLockerDetector.scanAndDetect(context)
            }.start()
        }
    }
}
