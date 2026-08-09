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
        } catch (e: Throwable) {
            // Do not crash the app on logging/init issues.
            writeCrashRaw("init error: " + Log.getStackTraceString(e), "init")
        }
    }

    /** Writes directly to files that any process can write, bypassing LogFile. */
    private fun writeCrashRaw(stack: String, thread: String) {
        val header = "\n=== CRASH on $thread @ ${System.currentTimeMillis()} ===\n$stack\n"
        val bytes = header.toByteArray(Charsets.UTF_8)
        for (path in arrayOf(
            "/data/local/tmp/bitlockerdroid_crash.txt",
            "/sdcard/Download/bitlockerdroid_crash.txt"
        )) {
            try {
                FileOutputStream(File(path), true).use { it.write(bytes) }
            } catch (e: Throwable) {
                // ignore
            }
        }
    }
}
