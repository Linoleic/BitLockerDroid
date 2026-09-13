package com.bitlockerdroid.service

import android.content.Context
import android.util.Log
import com.bitlockerdroid.util.DeviceIdentity
import com.bitlockerdroid.util.DevicePathSecurity
import com.bitlockerdroid.util.LogFile
import com.bitlockerdroid.util.PreferenceHelper
import com.bitlockerdroid.util.RootAccess
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.ConcurrentHashMap

/**
 * VirtualStorageMountManager orchestrates userspace FUSE direct mounting of decrypted
 * BitLocker partitions to /storage/BitLocker_<Label>.
 *
 * Runs the bitlocker_fuse daemon in the PID 1 mount namespace via Root (`su -M`),
 * making the decrypted storage globally accessible to all Android apps (e.g. MT Manager,
 * MX Player, Termux) through real POSIX filesystem paths.
 */
object VirtualStorageMountManager {

    private const val TAG = "VirtualStorageMount"

    /** Mount points we create or accept from daemon cmdlines — strict charset, no shell metacharacters. */
    private val MOUNT_POINT_PATTERN = Regex("^/storage/[A-Za-z0-9_-]+$")

    data class VirtualMountInfo(
        val devicePath: String,
        val volumeGuid: String,
        val mountPoint: String,
        val volumeLabel: String,
        val pid: Int,
        val mountedAt: Long = System.currentTimeMillis()
    )

    private val activeMounts = ConcurrentHashMap<String, VirtualMountInfo>()
    private val _activeMountsFlow = MutableStateFlow<Map<String, VirtualMountInfo>>(emptyMap())
    val activeMountsFlow: StateFlow<Map<String, VirtualMountInfo>> = _activeMountsFlow.asStateFlow()
    private val _mountState = MutableStateFlow(0L)
    val mountState: StateFlow<Long> = _mountState.asStateFlow()

    private fun notifyStateChanged() {
        _activeMountsFlow.value = java.util.HashMap(activeMounts)
        _mountState.value = System.currentTimeMillis()
    }

    fun isSupported(): Boolean = RootAccess.hasSu()

    fun isEnabled(context: Context): Boolean = PreferenceHelper.isVirtualMountEnabled(context)

    /**
     * Inspects /proc/mounts and running bitlocker_fuse daemons to discover mounts
     * that were created by previous sessions or survived an app restart.
     */
    fun syncStateWithSystem() {
        if (!isSupported()) return
        try {
            val output = RootAccess.execTimeout(
                "su -c 'for pid in \$(pidof bitlocker_fuse 2>/dev/null); do tr \"\\0\" \" \" < /proc/\$pid/cmdline 2>/dev/null; echo \" PID=\$pid\"; done'",
                2000
            ) ?: return

            var changed = false
            val runningPids = HashSet<Int>()

            for (line in output.lines()) {
                val trimmed = line.trim()
                if (trimmed.isBlank() || !trimmed.contains("PID=")) continue
                val pidStr = trimmed.substringAfter("PID=").trim()
                val pid = pidStr.toIntOrNull() ?: continue
                runningPids.add(pid)
                val cmdPart = trimmed.substringBefore("PID=").trim()
                val tokens = cmdPart.split(" ")
                // Usage: <daemon> <dev_path> <offset> <key_type> - <mountpoint>
                if (tokens.size >= 6) {
                    val devPath = tokens[1]
                    val mountPoint = tokens[5]
                    // The cmdline comes from the global mount namespace and is
                    // interpolated into root shell commands below: accept only
                    // a valid block node and a strict /storage/<id> point.
                    if (!DevicePathSecurity.isValid(devPath) || !MOUNT_POINT_PATTERN.matches(mountPoint)) continue
                    val isMounted = RootAccess.execTimeout(
                        "su -M -c 'cat /proc/mounts | grep \"$mountPoint\"'",
                        1000
                    )?.contains(mountPoint) == true

                    if (isMounted && !activeMounts.containsKey(devPath)) {
                        val guid = BitLockerDetector.getVolumeGuid(devPath) ?: devPath.takeLast(8)
                        val label = mountPoint.removePrefix("/storage/BitLocker_").removePrefix("/storage/")
                        activeMounts[devPath] = VirtualMountInfo(
                            devicePath = devPath,
                            volumeGuid = guid,
                            mountPoint = mountPoint,
                            volumeLabel = label,
                            pid = pid
                        )
                        changed = true
                        Log.i(TAG, "Discovered active mount from system: $devPath -> $mountPoint (PID=$pid)")
                    }
                }
            }

            // Remove mounts whose daemon PID is dead
            val iter = activeMounts.entries.iterator()
            while (iter.hasNext()) {
                val entry = iter.next()
                if (!runningPids.contains(entry.value.pid)) {
                    iter.remove()
                    changed = true
                    Log.i(TAG, "Pruned dead mount for ${entry.key}")
                }
            }

            if (changed) {
                notifyStateChanged()
            }
        } catch (e: Throwable) {
            Log.w(TAG, "syncStateWithSystem error", e)
        }
    }

    fun getMount(devicePath: String): VirtualMountInfo? = activeMounts[devicePath]

    fun getAllMounts(): List<VirtualMountInfo> = activeMounts.values.toList()

    /**
     * Tells the FUSE daemon serving [devicePath] that the volume changed
     * through the SAF provider's own session: SIGUSR1 makes it rebuild its
     * FatFs/ntfs-3g caches, so the next FUSE request sees the new content.
     * No-op (and no root exec) when nothing is virtually mounted.
     */
    fun notifyDataChanged(devicePath: String) {
        val info = activeMounts[devicePath] ?: return
        if (info.pid <= 1) return
        Thread {
            try {
                RootAccess.exec("su -c 'kill -USR1 ${info.pid}'", 2000)
            } catch (_: Throwable) {}
        }.apply { isDaemon = true; name = "fuse-notify" }.start()
    }

    /** Notifies every active virtual mount (used by the manual refresh). */
    fun notifyAllDataChanged() {
        for (path in activeMounts.keys) {
            notifyDataChanged(path)
        }
    }

    /**
     * Attempts to mount using a remembered password from KeyGuardService.
     */
    fun mountRemembered(context: Context, devicePath: String): Result<VirtualMountInfo> {
        val guid = BitLockerDetector.getVolumeGuid(devicePath)
            ?: return Result.failure(IllegalStateException("无法获取卷 GUID"))
        val blob = PreferenceHelper.getRememberedPassword(context, guid)
            ?: return Result.failure(IllegalStateException("未找到已记住的密码，请重新解锁并保存密码"))
        val password = KeyGuardService.decrypt(blob)
            ?: return Result.failure(IllegalStateException("解密已保存密码失败"))
        val devInfo = DeviceIdentity.queryDeviceInfo(devicePath)
        return mount(
            context = context,
            devicePath = devicePath,
            offset = 0L,
            key = password,
            isRecovery = false,
            volumeLabel = devInfo.friendlyName,
            volumeGuid = guid
        )
    }

    /**
     * Formats a standard USB drive storage ID (e.g. ABCD-1234) mimicking Android's native
     * OTG mount point naming convention, derived from the first 8 hex characters of the volume GUID.
     */
    fun formatStorageId(guid: String?, devicePath: String): String {
        val rawGuid = if (!guid.isNullOrBlank()) {
            guid
        } else {
            BitLockerDetector.getVolumeGuid(devicePath) ?: ""
        }
        val clean = rawGuid.replace("-", "").trim().uppercase().filter { it.isLetterOrDigit() }
        if (clean.length >= 8) {
            val p1 = clean.substring(0, 4)
            val p2 = clean.substring(4, 8)
            return "$p1-$p2"
        }
        val devClean = devicePath.filter { it.isLetterOrDigit() }.takeLast(8).uppercase().padStart(8, '0')
        return "${devClean.substring(0, 4)}-${devClean.substring(4, 8)}"
    }

    /**
     * Mounts a BitLocker volume via FUSE daemon directly to /storage/<ID> (e.g. /storage/ABCD-1234).
     */
    @Synchronized
    fun mount(
        context: Context,
        devicePath: String,
        offset: Long,
        key: String,
        isRecovery: Boolean,
        volumeLabel: String,
        volumeGuid: String
    ): Result<VirtualMountInfo> {
        if (!isEnabled(context)) {
            return Result.failure(IllegalStateException("Virtual mount is disabled in settings"))
        }
        if (!isSupported()) {
            return Result.failure(IllegalStateException("Root access is required for /storage virtual mount"))
        }
        // Defense in depth: devicePath, offset-derived strings and the storage
        // id below are all interpolated into `su -c` commands. The mount entry
        // points (remembered / registerDirect / dialog) bypass
        // DislockerCore.open, so validate here, at the choke point.
        DevicePathSecurity.requireValid(devicePath)

        // Check if already mounted and healthy
        activeMounts[devicePath]?.let { existing ->
            val alive = try {
                RootAccess.execTimeout("su -c 'kill -0 ${existing.pid} 2>/dev/null && echo alive'", 500)?.contains("alive") == true
            } catch (_: Exception) { true }
            if (alive) {
                Log.i(TAG, "Already mounted for $devicePath at ${existing.mountPoint}")
                return Result.success(existing)
            } else {
                activeMounts.remove(devicePath)
            }
        }

        val daemonPath = RootAccess.ensureFuseDaemonInstalled(context) ?: "/data/local/tmp/bitlocker_fuse"

        // Mimic real Android USB OTG naming: /storage/ABCD-1234 using the first 8 hex characters of GUID
        val storageId = formatStorageId(volumeGuid, devicePath)
        var mountPoint = "/storage/$storageId"
        var suffix = 1
        while (activeMounts.values.any { it.mountPoint == mountPoint }) {
            suffix++
            mountPoint = "/storage/${storageId}_$suffix"
        }

        // Clean up any stale mount or conflicting bitlocker_fuse daemon for this devicePath
        try {
            RootAccess.exec("su -c 'for pid in \$(pidof bitlocker_fuse 2>/dev/null); do if grep -q \"$devicePath\" /proc/\$pid/cmdline 2>/dev/null; then kill -9 \$pid 2>/dev/null; fi; done'")
            RootAccess.exec("su -M -c 'umount -l \"$mountPoint\" 2>/dev/null; rmdir \"$mountPoint\" 2>/dev/null'")
            // Also cleanup legacy /storage/BitLocker_* if present
            RootAccess.exec("su -M -c 'for m in /storage/BitLocker_*; do if [ -d \"\$m\" ]; then umount -l \"\$m\" 2>/dev/null; rmdir \"\$m\" 2>/dev/null; fi; done'")
        } catch (_: Throwable) {}

        val keyType = if (isRecovery) 2 else 1
        LogFile.write("app", "VirtualStorageMount: starting FUSE mount for $devicePath -> $mountPoint (keyType=$keyType)")

        try {
            val roFlag = if (PreferenceHelper.mountReadOnly) "ro" else "rw"
            val cmd = "'$daemonPath' '$devicePath' $offset $keyType - '$mountPoint' $roFlag"
            val pb = ProcessBuilder("su", "-M", "-c", cmd)
            pb.redirectErrorStream(true)
            val proc = pb.start()

            // Pass key via stdin to bitlocker_fuse
            proc.outputStream.bufferedWriter().use { writer ->
                writer.write(key + "\n")
                writer.flush()
            }

            val reader = BufferedReader(InputStreamReader(proc.inputStream))
            var mountedPid = -1
            val errorOutput = StringBuilder()

            val deadline = System.currentTimeMillis() + 8000L
            while (System.currentTimeMillis() < deadline) {
                if (reader.ready()) {
                    val line = reader.readLine() ?: break
                    if (line.startsWith("MOUNTED_PID=")) {
                        mountedPid = line.substringAfter("MOUNTED_PID=").trim().toIntOrNull() ?: -1
                        break
                    } else {
                        errorOutput.append(line).append("\n")
                    }
                } else {
                    try {
                        proc.exitValue()
                        while (reader.ready()) {
                            reader.readLine()?.let { errorOutput.append(it).append("\n") }
                        }
                        break
                    } catch (_: IllegalThreadStateException) {
                        Thread.sleep(100)
                    }
                }
            }

            if (mountedPid <= 0) {
                // Fallback check: verify if mountpoint is present in /proc/mounts
                val checkOut = RootAccess.execTimeout("su -M -c 'cat /proc/mounts | grep \"$mountPoint\"'", 2000)
                if (!checkOut.isNullOrBlank() && checkOut.contains(mountPoint)) {
                    val pidsOut = RootAccess.execTimeout("su -c 'pidof bitlocker_fuse'", 1000)?.trim()
                    mountedPid = pidsOut?.split("\\s+".toRegex())?.firstOrNull()?.toIntOrNull() ?: 1
                }
            }

            if (mountedPid > 0) {
                val info = VirtualMountInfo(
                    devicePath = devicePath,
                    volumeGuid = volumeGuid,
                    mountPoint = mountPoint,
                    volumeLabel = volumeLabel,
                    pid = mountedPid
                )
                activeMounts[devicePath] = info
                notifyStateChanged()
                LogFile.write("app", "VirtualStorageMount: mounted successfully at $mountPoint (PID=$mountedPid)")
                return Result.success(info)
            } else {
                val err = errorOutput.toString().trim()
                LogFile.write("app", "VirtualStorageMount: mount failed for $devicePath. $err")
                return Result.failure(RuntimeException("Mount failed: $err"))
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Virtual mount failed", e)
            LogFile.write("app", "VirtualStorageMount: exception mounting $devicePath: ${e.message}")
            return Result.failure(e)
        }
    }

    /**
     * Unmounts a virtual storage mount point.
     */
    @Synchronized
    fun unmount(devicePath: String) {
        val info = activeMounts.remove(devicePath)
        val mountPoint = info?.mountPoint
        LogFile.write("app", "VirtualStorageMount: unmounting $devicePath ($mountPoint)")
        notifyStateChanged()
        try {
            if (!mountPoint.isNullOrBlank()) {
                // Lazy detach mountpoint in global mount namespace and cleanup dir
                RootAccess.exec("su -M -c 'umount -l \"$mountPoint\" 2>/dev/null; rmdir \"$mountPoint\" 2>/dev/null'")
            }
            if (info != null && info.pid > 1) {
                RootAccess.exec("su -c 'kill -TERM ${info.pid} 2>/dev/null'")
            }
            // Also cleanup any bitlocker_fuse matching this devicePath
            RootAccess.exec("su -c 'for pid in \$(pidof bitlocker_fuse 2>/dev/null); do if grep -q \"$devicePath\" /proc/\$pid/cmdline 2>/dev/null; then kill -TERM \$pid 2>/dev/null; fi; done'")
        } catch (e: Throwable) {
            Log.w(TAG, "unmount error", e)
        }
    }

    /**
     * Unmounts all active virtual mounts.
     */
    @Synchronized
    fun unmountAll() {
        for (path in activeMounts.keys) {
            unmount(path)
        }
        activeMounts.clear()
        notifyStateChanged()
        try {
            RootAccess.exec("su -M -c 'for m in /storage/BitLocker_*; do if [ -d \"\$m\" ]; then umount -l \"\$m\" 2>/dev/null; rmdir \"\$m\" 2>/dev/null; fi; done'")
            RootAccess.exec("su -c 'pkill -9 bitlocker_fuse 2>/dev/null'")
        } catch (_: Throwable) {}
    }
}
