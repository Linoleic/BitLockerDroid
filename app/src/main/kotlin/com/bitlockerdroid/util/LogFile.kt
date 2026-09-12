package com.bitlockerdroid.util

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes a rolling debug log to a file on the device so the module can be
 * diagnosed without logcat.
 *
 * Two writers coexist:
 *  - In the module app process, the log goes to the app's files dir
 *    (readable via the file manager / `adb shell run-as`).
 *  - In system_server (where the LSPosed hooks run) there is no app context,
 *    so we fall back to /data/local/tmp/bitlockerdroid.log, which the system
 *    process can write.
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
     * Appends a line to the log. `scope` identifies the process writer,
     * e.g. "hook" (system_server) or "app".
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

        // Also mirror to logcat. Use INFO level: debug (Log.d) is suppressed
        // in the system_server process on user builds, which made the hook
        // activity invisible in logcat.
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
