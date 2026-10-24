package com.bitlockerdroid.util

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes a rolling debug log to a file on the device so the app can be
 * diagnosed without logcat.
 *
 * Every line is appended to `<filesDir>/logs/bitlocker.log` (app-private,
 * readable via the in-app viewer, `adb shell run-as` or root) and mirrored to
 * logcat under the [TAG] tag.
 *
 * The file is capped (old lines are truncated from the top) to avoid unbounded
 * growth.
 */
object LogFile {

    private const val TAG = "BitLockerLog"

    @Volatile
    private var store: LogSnapshotStore? = null

    /** Called once an application context is available (service / receiver). */
    @Synchronized
    fun init(context: Context) {
        if (store == null) {
            try {
                store = LogSnapshotStore(File(context.filesDir, "logs"))
            } catch (e: Throwable) {
                store = null
            }
        }
    }

    /** Best-effort path of the module-app log file (for the settings UI). */
    fun appLogFile(): File? {
        return store?.applicationFile
    }

    fun readApplication(): String? = store?.readApplication()
    fun clear() { store?.clear() }
    fun snapshot(): LogSnapshot = store?.snapshot()
        ?: LogSnapshot(null, null, listOf("Application log storage is unavailable."))

    /**
     * Appends a line to the log. `scope` identifies the writer, e.g. "app",
     * "provider" or "benchmark".
     */
    fun write(scope: String, message: String) {
        val line = buildLine(scope, message)

        // app files dir (app-private sandbox)
        val currentStore = store
        if (currentStore != null) {
            try {
                currentStore.append(line)
            } catch (e: Throwable) {
                // ignore
            }
        }

        // Also mirror to logcat. INFO (rather than DEBUG) keeps the entries
        // visible on user builds and lets `adb logcat -s BitLockerLog` capture
        // them without root.
        Log.i(TAG, "[$scope] $message")
    }

    private fun buildLine(scope: String, message: String): String {
        val ts = try {
            SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        } catch (e: Throwable) {
            "??-?? ??:??:??.???"
        }
        return "$ts [$scope] $message\n"
    }

}
