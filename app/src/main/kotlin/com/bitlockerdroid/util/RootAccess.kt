package com.bitlockerdroid.util

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Runs shell commands via KernelSU / Magisk `su` so the module app can read
 * block devices that SELinux normally blocks for unprivileged apps.
 *
 * The user must grant root to com.bitlockerdroid in the KernelSU manager.
 */
object RootAccess {

    private const val TAG = "RootAccess"

    /** Whether `su` is available (does not require an active grant). */
    fun hasSu(): Boolean {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val out = p.inputStream.bufferedReader().readText().trim()
            // Give the su prompt some time but don't hang forever.
            if (!p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroy()
                false
            } else {
                out.contains("uid=0") || out.contains("root")
            }
        } catch (e: Throwable) {
            Log.w(TAG, "su check failed", e)
            false
        }
    }

    /**
     * Runs `su -c <command>` and returns (exitCode, stdout).
     * Returns null if su itself fails to start.
     */
    fun exec(command: String): Pair<Int, String>? {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            val out = p.inputStream.bufferedReader().readText()
            val err = p.errorStream.bufferedReader().readText()
            val exit = p.waitFor()
            val combined = (out + "\n" + err).trim()
            exit to combined
        } catch (e: Throwable) {
            Log.w(TAG, "su exec failed: $command", e)
            null
        }
    }

    /** Runs `su -c <command>` with a timeout; returns stdout or null on timeout/error. */
    fun execTimeout(command: String, timeoutMs: Long = 5000): String? {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            val proc = ProcessThread(p)
            proc.start()
            proc.join(timeoutMs)
            if (proc.isAlive) {
                p.destroy()
                Log.w(TAG, "su exec timed out: $command")
                null
            } else {
                proc.result?.trim()
            }
        } catch (e: Throwable) {
            Log.w(TAG, "su exec failed: $command", e)
            null
        }
    }

    /** Runs `su -c <command>` and returns raw stdout bytes, or null on timeout/error. */
    fun execBytes(command: String, timeoutMs: Long = 10000): ByteArray? {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            val proc = ByteProcessThread(p)
            proc.start()
            proc.join(timeoutMs)
            if (proc.isAlive) {
                p.destroy()
                Log.w(TAG, "su exec timed out: $command")
                null
            } else {
                proc.result
            }
        } catch (e: Throwable) {
            Log.w(TAG, "su exec failed: $command", e)
            null
        }
    }

    private class ByteProcessThread(private val p: Process) : Thread() {
        @Volatile
        var result: ByteArray? = null

        override fun run() {
            try {
                val out = p.inputStream.readBytes()
                p.waitFor()
                result = out
            } catch (e: Throwable) {
                result = null
            }
        }
    }

    private class ProcessThread(private val p: Process) : Thread() {
        @Volatile
        var result: String? = null

        override fun run() {
            try {
                val out = BufferedReader(InputStreamReader(p.inputStream))
                val sb = StringBuilder()
                val buf = CharArray(8192)
                while (true) {
                    val n = out.read(buf)
                    if (n <= 0) break
                    sb.append(buf, 0, n)
                }
                val err = BufferedReader(InputStreamReader(p.errorStream))
                val eb = StringBuilder()
                while (true) {
                    val n = err.read(buf)
                    if (n <= 0) break
                    eb.append(buf, 0, n)
                }
                p.waitFor()
                result = (sb.toString() + "\n" + eb.toString()).trim()
            } catch (e: Throwable) {
                result = null
            }
        }
    }
}
