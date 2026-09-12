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

    @Volatile
    var cachedHasSu: Boolean? = null
        private set
    @Volatile
    private var lastSuCheckTime = 0L

    data class RootSolutionInfo(
        val hasRoot: Boolean,
        val solutionName: String,
        val version: String? = null,
        val summary: String
    )

    @Volatile
    var cachedRootSolution: RootSolutionInfo? = null
        private set

    /** Whether `su` is available (does not require an active grant). Results are cached for 10s. */
    fun hasSu(forceRefresh: Boolean = false): Boolean {
        val now = System.currentTimeMillis()
        if (!forceRefresh && cachedHasSu != null && (now - lastSuCheckTime < 10000L)) {
            return cachedHasSu!!
        }
        val result = try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val out = p.inputStream.bufferedReader().readText().trim()
            if (!p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroy()
                false
            } else {
                out.contains("uid=0") || out.contains("root")
            }
        } catch (e: Throwable) {
            Log.w(TAG, "su check failed", e)
            false
        }
        cachedHasSu = result
        lastSuCheckTime = now
        return result
    }

    /**
     * Inspects the environment to dynamically detect the root solution (KernelSU, Magisk, APatch, etc.)
     * and its version string.
     */
    fun getRootSolution(forceRefresh: Boolean = false): RootSolutionInfo {
        if (!forceRefresh && cachedRootSolution != null) {
            return cachedRootSolution!!
        }

        if (!hasSu(forceRefresh)) {
            val unauth = RootSolutionInfo(
                hasRoot = false,
                solutionName = "未授权",
                version = null,
                summary = "未获取 Root 权限"
            )
            cachedRootSolution = unauth
            return unauth
        }

        var rawVersion = ""
        try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-v"))
            rawVersion = p.inputStream.bufferedReader().readText().trim()
            p.waitFor(1, java.util.concurrent.TimeUnit.SECONDS)
        } catch (_: Throwable) {}

        if (rawVersion.isBlank()) {
            rawVersion = execTimeout("su -v", 1500)?.trim() ?: ""
        }

        var ksudVer = ""
        if (rawVersion.contains("KernelSU", ignoreCase = true) || rawVersion.isBlank()) {
            ksudVer = execTimeout("ksud -V", 1000)?.trim() ?: ""
        }

        val info = when {
            rawVersion.contains("KernelSU", ignoreCase = true) || ksudVer.isNotBlank() -> {
                val ver = if (rawVersion.contains(":KernelSU")) {
                    rawVersion.substringBefore(":KernelSU")
                } else if (rawVersion.contains("KernelSU")) {
                    rawVersion.substringAfter("KernelSU").trim().removePrefix("v")
                } else {
                    ksudVer
                }
                val cleanVer = ver.trim().takeIf { it.isNotBlank() }
                RootSolutionInfo(
                    hasRoot = true,
                    solutionName = "KernelSU",
                    version = cleanVer,
                    summary = if (cleanVer != null) "已授权 · KernelSU ($cleanVer)" else "已授权 · KernelSU"
                )
            }
            rawVersion.contains("MAGISK", ignoreCase = true) -> {
                val ver = rawVersion.substringBefore(":")
                val cleanVer = ver.trim().takeIf { it.isNotBlank() }
                RootSolutionInfo(
                    hasRoot = true,
                    solutionName = "Magisk",
                    version = cleanVer,
                    summary = if (cleanVer != null) "已授权 · Magisk ($cleanVer)" else "已授权 · Magisk"
                )
            }
            rawVersion.contains("APatch", ignoreCase = true) -> {
                val ver = rawVersion.substringBefore(":")
                val cleanVer = ver.trim().takeIf { it.isNotBlank() }
                RootSolutionInfo(
                    hasRoot = true,
                    solutionName = "APatch",
                    version = cleanVer,
                    summary = if (cleanVer != null) "已授权 · APatch ($cleanVer)" else "已授权 · APatch"
                )
            }
            rawVersion.isNotBlank() -> {
                RootSolutionInfo(
                    hasRoot = true,
                    solutionName = "SU",
                    version = rawVersion,
                    summary = "已正常授权 ($rawVersion)"
                )
            }
            else -> {
                RootSolutionInfo(
                    hasRoot = true,
                    solutionName = "正常",
                    version = null,
                    summary = "已正常获取 Root 权限"
                )
            }
        }

        cachedRootSolution = info
        return info
    }

    /**
     * Runs `su -c <command>` and returns (exitCode, stdout).
     * Returns null if su itself fails to start or times out (default 5s).
     */
    fun exec(command: String, timeoutMs: Long = 5000): Pair<Int, String>? {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            val proc = ProcessThread(p)
            proc.start()
            proc.join(timeoutMs)
            if (proc.isAlive) {
                try { p.inputStream.close() } catch (_: Throwable) {}
                try { p.outputStream.close() } catch (_: Throwable) {}
                try { p.errorStream.close() } catch (_: Throwable) {}
                p.destroy()
                try { p.destroyForcibly() } catch (_: Throwable) {}
                Log.w(TAG, "su exec timed out ($timeoutMs ms): $command")
                null
            } else {
                val exit = try { p.exitValue() } catch (_: Exception) { 0 }
                exit to (proc.result ?: "")
            }
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
                try { p.inputStream.close() } catch (_: Throwable) {}
                try { p.outputStream.close() } catch (_: Throwable) {}
                try { p.errorStream.close() } catch (_: Throwable) {}
                p.destroy()
                try { p.destroyForcibly() } catch (_: Throwable) {}
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
                try { p.inputStream.close() } catch (_: Throwable) {}
                try { p.outputStream.close() } catch (_: Throwable) {}
                try { p.errorStream.close() } catch (_: Throwable) {}
                p.destroy()
                try { p.destroyForcibly() } catch (_: Throwable) {}
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

    /**
     * Runs `su -c <command>` and streams [inputBytes] into process stdin.
     * Returns true if the process exits with status 0 within [timeoutMs].
     */
    fun execWriteBytes(command: String, inputBytes: ByteArray, timeoutMs: Long = 15000): Boolean {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            val thread = Thread {
                try {
                    p.outputStream.use { out ->
                        out.write(inputBytes)
                        out.flush()
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "failed writing bytes to su stdin", e)
                }
            }
            thread.start()
            val finished = p.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (!finished) {
                p.destroy()
                Log.w(TAG, "su execWriteBytes timed out: $command")
                false
            } else {
                p.exitValue() == 0
            }
        } catch (e: Throwable) {
            Log.w(TAG, "su execWriteBytes failed: $command", e)
            false
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

    /**
     * Ensures the high-performance bitlocker_io root helper binary is installed
     * to /data/local/tmp and app files directory with executable permissions.
     */
    fun ensureDaemonInstalled(context: android.content.Context) {
        try {
            val libDir = context.applicationInfo.nativeLibraryDir
            val nativeBin = java.io.File(libDir, "libbitlocker_io.so")
            if (nativeBin.exists()) {
                val appFilesBin = java.io.File(context.filesDir, "bitlocker_io")
                if (!appFilesBin.exists() || appFilesBin.length() != nativeBin.length()) {
                    nativeBin.copyTo(appFilesBin, overwrite = true)
                    appFilesBin.setExecutable(true, false)
                }
                val tmpBin = java.io.File("/data/local/tmp/bitlocker_io")
                if (!tmpBin.exists() || tmpBin.length() != nativeBin.length()) {
                    exec("cp '${nativeBin.absolutePath}' /data/local/tmp/bitlocker_io && chmod 755 /data/local/tmp/bitlocker_io")
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "ensureDaemonInstalled failed", e)
        }
    }

    /**
     * Ensures the userspace FUSE mount daemon binary (libbitlocker_fuse.so) is installed
     * to /data/local/tmp/bitlocker_fuse with executable permissions.
     */
    fun ensureFuseDaemonInstalled(context: android.content.Context): String? {
        try {
            val libDir = context.applicationInfo.nativeLibraryDir
            val nativeBin = java.io.File(libDir, "libbitlocker_fuse.so")
            val targetBin = java.io.File("/data/local/tmp/bitlocker_fuse")
            if (nativeBin.exists()) {
                if (!targetBin.exists() || targetBin.length() != nativeBin.length()) {
                    exec("cp '${nativeBin.absolutePath}' /data/local/tmp/bitlocker_fuse && chmod 755 /data/local/tmp/bitlocker_fuse")
                }
                return targetBin.absolutePath
            } else if (targetBin.exists()) {
                return targetBin.absolutePath
            }
        } catch (e: Throwable) {
            Log.w(TAG, "ensureFuseDaemonInstalled failed", e)
        }
        return null
    }
}
