package com.bitlockerdroid.util

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
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
    private const val MAX_BYTES = 512 * 1024L

    @Volatile
    private var appLogDir: File? = null

    /** Called once an application context is available (service / receiver). */
    fun init(context: Context) {
        if (appLogDir == null) {
            try {
                appLogDir = File(context.filesDir, "logs")
            } catch (e: Throwable) {
                appLogDir = null
            }
        }
    }

    /** Best-effort path of the module-app log file (for the settings UI). */
    fun appLogFile(): File? {
        val dir = appLogDir ?: return null
        return File(dir, "bitlocker.log")
    }

    /**
     * Appends a line to the log. `scope` identifies the writer, e.g. "app",
     * "provider" or "benchmark".
     */
    fun write(scope: String, message: String) {
        val line = buildLine(scope, message)

        // app files dir (app-private sandbox)
        val appFile = appLogFile()
        if (appFile != null) {
            try {
                appendCapped(appFile, line)
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

    private fun appendCapped(file: File, line: String) {
        file.parentFile?.mkdirs()
        if (!file.exists()) {
            file.createNewFile()
        }
        // Trim if too large: keep the tail.
        if (file.length() + line.length > MAX_BYTES) {
            val keep = (MAX_BYTES / 2).toInt()
            val raf = java.io.RandomAccessFile(file, "rw")
            try {
                val len = raf.length()
                if (len > keep) {
                    raf.seek(len - keep)
                    val tail = ByteArray(keep)
                    raf.readFully(tail)
                    raf.setLength(0)
                    raf.seek(0)
                    raf.write(tail)
                }
            } finally {
                raf.close()
            }
        }
        FileOutputStream(file, true).use { fos ->
            fos.write(line.toByteArray(Charsets.UTF_8))
        }
    }
}
