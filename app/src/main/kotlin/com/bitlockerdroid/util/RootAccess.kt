package com.bitlockerdroid.util

import android.util.Log
import com.bitlockerdroid.R

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
            // exec() reads stdout/stderr concurrently under a hard timeout;
            // a blocking readText() here would hang forever on a grant dialog.
            val out = exec("id", 2000)?.second ?: ""
            out.contains("uid=0") || out.contains("root")
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

        val ctx = try { ContextProvider.app } catch (_: Throwable) { null }
        val granted = ctx?.getString(R.string.root_status_authorized) ?: "Granted"
        val unauth = ctx?.getString(R.string.root_status_unauthorized) ?: "Not granted"
        val unauthDesc = ctx?.getString(R.string.root_status_unauthorized_desc) ?: "Root access not granted"
        val authDesc = ctx?.getString(R.string.root_status_authorized_desc) ?: "Root access granted"

        if (!hasSu(forceRefresh)) {
            val unauthInfo = RootSolutionInfo(
                hasRoot = false,
                solutionName = unauth,
                version = null,
                summary = unauthDesc
            )
            cachedRootSolution = unauthInfo
            return unauthInfo
        }

        var rawVersion = ""
        try {
            // `su -v` is invoked directly (no -c). Read concurrently under a
            // timeout instead of readText(), which blocks indefinitely.
            val p = Runtime.getRuntime().exec(arrayOf("su", "-v"))
            val proc = ProcessThread(p)
            proc.start()
            proc.join(1500)
            if (proc.isAlive) destroyQuietly(p)
            rawVersion = proc.result?.trim() ?: ""
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
                    summary = if (cleanVer != null) "$granted · KernelSU ($cleanVer)" else "$granted · KernelSU"
                )
            }
            rawVersion.contains("MAGISK", ignoreCase = true) -> {
                val ver = rawVersion.substringBefore(":")
                val cleanVer = ver.trim().takeIf { it.isNotBlank() }
                RootSolutionInfo(
                    hasRoot = true,
                    solutionName = "Magisk",
                    version = cleanVer,
                    summary = if (cleanVer != null) "$granted · Magisk ($cleanVer)" else "$granted · Magisk"
                )
            }
            rawVersion.contains("APatch", ignoreCase = true) -> {
                val ver = rawVersion.substringBefore(":")
                val cleanVer = ver.trim().takeIf { it.isNotBlank() }
                RootSolutionInfo(
                    hasRoot = true,
                    solutionName = "APatch",
                    version = cleanVer,
                    summary = if (cleanVer != null) "$granted · APatch ($cleanVer)" else "$granted · APatch"
                )
            }
            rawVersion.isNotBlank() -> {
                RootSolutionInfo(
                    hasRoot = true,
                    solutionName = "SU",
                    version = rawVersion,
                    summary = "$granted ($rawVersion)"
                )
            }
            else -> {
                RootSolutionInfo(
                    hasRoot = true,
                    solutionName = granted,
                    version = null,
                    summary = authDesc
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
                destroyQuietly(p)
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
                destroyQuietly(p)
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
                destroyQuietly(p)
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
            // Drain stdout/stderr so a chatty command can't block on full
            // pipe buffers while we wait for its exit.
            drainQuietly(p)
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
                destroyQuietly(p)
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

    /** Closes streams and destroys the process (best effort, also kills children of su). */
    private fun destroyQuietly(p: Process) {
        try { p.inputStream.close() } catch (_: Throwable) {}
        try { p.outputStream.close() } catch (_: Throwable) {}
        try { p.errorStream.close() } catch (_: Throwable) {}
        p.destroy()
        try { p.destroyForcibly() } catch (_: Throwable) {}
    }

    /** Consumes stdout/stderr on daemon threads, discarding the content. */
    private fun drainQuietly(p: Process) {
        Thread { try { p.inputStream.readBytes() } catch (_: Throwable) {} }.apply { isDaemon = true; start() }
        Thread { try { p.errorStream.readBytes() } catch (_: Throwable) {} }.apply { isDaemon = true; start() }
    }

    private class ByteProcessThread(private val p: Process) : Thread() {
        @Volatile
        var result: ByteArray? = null

        override fun run() {
            try {
                // Drain stderr concurrently: it must never be left unread
                // while we block on stdout, or the pipe fills and wedges su.
                val errDrain = Thread { try { p.errorStream.readBytes() } catch (_: Throwable) {} }
                errDrain.isDaemon = true
                errDrain.start()
                val out = p.inputStream.readBytes()
                p.waitFor()
                try { errDrain.join(1000) } catch (_: Throwable) {}
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
                // Read stdout and stderr concurrently. Reading one fully and
                // then the other deadlocks once the unread pipe buffer (64KB)
                // fills while the process is still running.
                val errBytes = java.io.ByteArrayOutputStream()
                val errDrain = Thread {
                    try {
                        val buf = ByteArray(8192)
                        val err = p.errorStream
                        while (true) {
                            val n = err.read(buf)
                            if (n <= 0) break
                            errBytes.write(buf, 0, n)
                        }
                    } catch (_: Throwable) {}
                }
                errDrain.isDaemon = true
                errDrain.start()

                val out = p.inputStream.readBytes()
                p.waitFor()
                try { errDrain.join(1000) } catch (_: Throwable) {}
                result = (String(out) + "\n" + errBytes.toString()).trim()
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
