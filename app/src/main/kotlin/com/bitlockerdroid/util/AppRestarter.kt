package com.bitlockerdroid.util

import android.app.Activity
import android.content.Context
import android.content.Intent
import kotlin.system.exitProcess

object AppRestarter {
    /**
     * Completely and cleanly cold-restarts the application process.
     */
    fun restartApp(context: Context) {
        try {
            val pm = context.packageManager
            val launchIntent = pm.getLaunchIntentForPackage(context.packageName)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
            if (launchIntent != null) {
                val restartIntent = Intent.makeRestartActivityTask(launchIntent.component)
                context.startActivity(restartIntent)
            }
            if (context is Activity) {
                context.finishAffinity()
            }
        } catch (e: Throwable) {
            LogFile.write("app", "AppRestarter error: ${e.message}")
        }
        exitProcess(0)
    }
}
