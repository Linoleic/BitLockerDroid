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
            context.startService(
                Intent(context, BitLockerCoreService::class.java)
            )
        }
    }
}
