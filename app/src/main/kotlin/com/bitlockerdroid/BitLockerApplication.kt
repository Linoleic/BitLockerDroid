package com.bitlockerdroid

import android.app.Application
import android.util.Log
import com.bitlockerdroid.util.ContextProvider
import com.bitlockerdroid.util.LogFile
import java.io.File
import java.io.FileOutputStream

/**
 * Application entry. Registers a default uncaught-exception handler that
 * writes crash stacks to a raw file (so even a failure inside LogFile can be
 * captured), then to LogFile if it is healthy.
 */
class BitLockerApplication : Application() {

    companion object {
        private const val TAG = "BitLockerApp"
    }

    override fun onCreate() {
        super.onCreate()

        // The very first thing: register the crash handler using raw writes so
        // that even if LogFile/class-loading breaks we still capture the stack.
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val stack = Log.getStackTraceString(throwable)
            writeCrashRaw(stack, thread.name)
            prev?.uncaughtException(thread, throwable)
        }

        try {
            ContextProvider.app = applicationContext
            LogFile.init(applicationContext)
            LogFile.write("app", "=== Application onCreate ===")
            com.bitlockerdroid.util.PreferenceHelper.purgeLegacyNodeKeys(applicationContext)
            com.bitlockerdroid.util.RootAccess.ensureDaemonInstalled(applicationContext)
            com.bitlockerdroid.util.RootAccess.ensureFuseDaemonInstalled(applicationContext)
            try {
                val serviceIntent = android.content.Intent(applicationContext, com.bitlockerdroid.service.BitLockerCoreService::class.java)
                startService(serviceIntent)
            } catch (e: Throwable) {
                LogFile.write("app", "Failed to start BitLockerCoreService from App: ${e.message}")
            }
            Thread {
                com.bitlockerdroid.service.VirtualStorageMountManager.syncStateWithSystem()
                com.bitlockerdroid.service.UnlockManager.restoreRemembered(applicationContext)
            }.start()
        } catch (e: Throwable) {
            // Do not crash the app on logging/init issues.
            writeCrashRaw("init error: " + Log.getStackTraceString(e), "init")
        }
    }

    /** Writes crash stacks to the app-private files directory. */
    private fun writeCrashRaw(stack: String, thread: String) {
        val header = "\n=== CRASH on $thread @ ${System.currentTimeMillis()} ===\n$stack\n"
        val bytes = header.toByteArray(Charsets.UTF_8)
        try {
            val logDir = File(filesDir, "logs")
            logDir.mkdirs()
            FileOutputStream(File(logDir, "crash.txt"), true).use { it.write(bytes) }
        } catch (e: Throwable) {
            // ignore
        }
    }
}
