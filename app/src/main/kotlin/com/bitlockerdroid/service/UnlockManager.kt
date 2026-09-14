package com.bitlockerdroid.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.bitlockerdroid.R
import com.bitlockerdroid.ui.BitLockerSettingsActivity
import com.bitlockerdroid.ui.UnlockDialogActivity
import com.bitlockerdroid.util.PreferenceHelper
import com.bitlockerdroid.util.LogFile

/**
 * Orchestrates the unlock flow: holds the currently unlocked volumes, raises
 * the unlock dialog when a new BitLocker device is detected, and notifies the
 * DocumentsProvider of state changes.
 */
object UnlockManager {

    private const val TAG = "UnlockManager"
    const val CHANNEL_ID = "bitlocker_status"

    interface StateChangeListener {
        fun onUnlockManagerStateChanged()
    }

    private val listeners = java.util.concurrent.CopyOnWriteArraySet<StateChangeListener>()

    fun addListener(listener: StateChangeListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: StateChangeListener) {
        listeners.remove(listener)
    }

    fun notifyStateChanged() {
        for (l in listeners) {
            try {
                l.onUnlockManagerStateChanged()
            } catch (e: Throwable) {
                Log.w(TAG, "listener error", e)
            }
        }
    }

    private val inProgressUnlocks = java.util.Collections.synchronizedSet(HashSet<String>())
    private val unlockingLatch = java.util.concurrent.atomic.AtomicInteger(0)
    private val unlockWaitLock = Object()

    fun awaitPendingUnlocks(timeoutMs: Long = 6000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        synchronized(unlockWaitLock) {
            while (unlockingLatch.get() > 0) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) break
                try {
                    (unlockWaitLock as java.lang.Object).wait(remaining)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
    }

    /** devicePath -> live core session */
    private val sessions = HashMap<String, DislockerCore>()

    /** devicePath -> detected-but-not-yet-unlocked volume. */
    private val detected = HashMap<String, DetectedVolume>()

    private val lock = Any()

    /** Tracks volumes that the user explicitly locked while plugged in, suppressing auto-unlock until replug or manual refresh. */
    private val manuallyLockedGuids = java.util.Collections.synchronizedSet(HashSet<String>())

    fun clearManualLockSuppression() {
        manuallyLockedGuids.clear()
        LogFile.write("app", "UnlockManager: cleared manual lock suppression")
    }

    fun isManuallyLocked(guid: String?, devicePath: String): Boolean {
        if (!guid.isNullOrBlank() && manuallyLockedGuids.contains(guid)) return true
        if (manuallyLockedGuids.contains(devicePath)) return true
        return false
    }

    /** Active native handles kept alive while a volume is unlocked. */
    val unlockedVolumes: List<UnlockedVolume>
        get() {
            // Snapshot under the lock; the per-volume queries below do
            // root execs and native IO and must not hold the lock.
            val snapshot = synchronized(lock) { sessions.toList() }
            return snapshot.map { (path, core) ->
                val fsName = when (core.reader) {
                    is com.bitlockerdroid.ntfs.NtfsReader -> "NTFS"
                    is com.bitlockerdroid.ntfs.ExFatReader -> "exFAT"
                    is com.bitlockerdroid.ntfs.Fat32Reader -> "FAT32"
                    else -> "Unknown"
                }
                val devInfo = com.bitlockerdroid.util.DeviceIdentity.queryDeviceInfo(path)
                val space = core.getSpaceInfo()
                val totalBytes = if (space != null && space.first > 0L) space.first else core.info.volumeSize
                val freeBytes = space?.second ?: -1L
                val usedBytes = if (freeBytes >= 0L) (totalBytes - freeBytes).coerceAtLeast(0L) else -1L
                UnlockedVolume(
                    devicePath = path,
                    size = totalBytes,
                    label = core.volumeLabel,
                    fsType = fsName,
                    cipher = core.info.algorithmName,
                    canWrite = (core.writer?.isMounted == true) && !PreferenceHelper.mountReadOnly,
                    guid = core.volumeGuid,
                    recoveryKeyId = core.recoveryKeyId,
                    deviceName = devInfo.friendlyName,
                    freeBytes = freeBytes,
                    usedBytes = usedBytes
                )
            }
        }

    /**
     * Volumes that carry a BitLocker signature but are not unlocked yet.
     * Persists across reformats because the entry is keyed by the block node
     * path, and re-registered on each successful signature scan (the prompt may
     * be suppressed if the system hook's in-memory "confirmed" set survived the
     * reformat). Visible in the management UI so the user can unlock manually.
     */
    val detectedVolumes: List<DetectedVolume>
        get() = synchronized(lock) {
            detected.values.toList()
        }

    val activeSessions: List<DislockerCore>
        get() = synchronized(lock) {
            sessions.values.toList()
        }

    fun isUnlocked(devicePath: String): Boolean = synchronized(lock) {
        sessions.containsKey(devicePath)
    }

    fun closeSessionIfPresent(devicePath: String) {
        val app = com.bitlockerdroid.util.ContextProvider.app
        // Remove under the lock, but close outside it: close() flushes and
        // does native IO, and holding `lock` during that stalls every
        // get()/isUnlocked() caller.
        val removed: DislockerCore? = synchronized(lock) { sessions.remove(devicePath) }
        removed?.close()
        if (removed != null) {
            app?.let {
                BitLockerCoreService.updateForegroundState(it)
                com.bitlockerdroid.provider.BitLockerDocumentsProvider.notifyRootsChanged(it)
            }
            notifyStateChanged()
        }
    }

    /** Marks [devicePath] as a BitLocker volume needing unlock. Idempotent. */
    fun registerDetected(devicePath: String, guid: String? = null, recoveryKeyId: String? = null) {
        var changed = false
        synchronized(lock) {
            if (!sessions.containsKey(devicePath)) {
                val devInfo = com.bitlockerdroid.util.DeviceIdentity.queryDeviceInfo(devicePath, forceRefresh = true)
                val effectiveGuid = guid ?: BitLockerDetector.getVolumeGuid(devicePath)
                val effectiveRecoveryKeyId = recoveryKeyId ?: BitLockerDetector.getRecoveryKeyId(devicePath)
                val prev = detected[devicePath]
                if (prev == null || prev.guid != effectiveGuid || prev.recoveryKeyId != effectiveRecoveryKeyId || prev.deviceName != devInfo.friendlyName) {
                    detected[devicePath] = DetectedVolume(
                        devicePath = devicePath,
                        guid = effectiveGuid,
                        recoveryKeyId = effectiveRecoveryKeyId,
                        deviceName = devInfo.friendlyName,
                        capacity = devInfo.sizeBytes
                    )
                    changed = true
                }
            }
        }
        if (changed) {
            notifyStateChanged()
        }
    }

    /** Clears a volume from the detected (locked) list, e.g. after it is
     *  unlocked or physically removed. */
    fun forgetDetected(devicePath: String) {
        StorageNotificationSuppressor.forgetNode(devicePath)
        var removed = false
        synchronized(lock) {
            removed = (detected.remove(devicePath) != null)
        }
        if (removed) {
            notifyStateChanged()
        }
    }

    /** True if [devicePath] is either unlocked or pending unlock in the list. */
    fun isKnown(devicePath: String): Boolean = synchronized(lock) {
        sessions.containsKey(devicePath) || detected.containsKey(devicePath)
    }

    /** Called by the unlock dialog with the user password. */
    fun unlockWithPassword(
        context: Context,
        devicePath: String,
        offset: Long,
        password: String,
        remember: Boolean,
        expectedGuid: String? = null
    ): Result<DislockerCore> {
        return try {
            val core = DislockerCore.open(devicePath, offset, password)
            val guid = core.volumeGuid ?: expectedGuid ?: BitLockerDetector.getVolumeGuid(devicePath)
            if (!guid.isNullOrBlank()) {
                manuallyLockedGuids.remove(guid)
            }
            manuallyLockedGuids.remove(devicePath)
            // Replace any stale session: take it out under the lock, close it
            // outside so native teardown never runs while `lock` is held.
            val stale = synchronized(lock) {
                val old = sessions.put(devicePath, core)
                detected.remove(devicePath)
                old
            }
            stale?.close()
            // Save the encrypted password only if the user asked to remember it.
            // Keyed strictly by persistent Volume GUID rather than ephemeral device node.
            if (!guid.isNullOrBlank()) {
                if (remember) {
                    KeyGuardService.encrypt(password)?.let { blob ->
                        val friendly = core.volumeLabel.ifBlank {
                            com.bitlockerdroid.util.DeviceIdentity.queryDeviceInfo(devicePath, forceRefresh = true).friendlyName
                        }
                        PreferenceHelper.saveRememberedPassword(context, guid, blob, friendlyName = friendly)
                    }
                } else {
                    PreferenceHelper.clearRememberedPassword(context, guid)
                }
            } else {
                LogFile.write("app", "unlockWithPassword: no volume GUID found for $devicePath, skipping credential persistence")
            }

            // Trigger userspace FUSE virtual mount to /storage/BitLocker_<Label>
            val effectiveGuid = guid ?: ""
            val effectiveLabel = core.volumeLabel.ifBlank {
                com.bitlockerdroid.util.DeviceIdentity.queryDeviceInfo(devicePath).friendlyName
            }
            if (VirtualStorageMountManager.isEnabled(context) && VirtualStorageMountManager.isSupported()) {
                Thread {
                    VirtualStorageMountManager.mount(
                        context = context,
                        devicePath = devicePath,
                        offset = offset,
                        key = password,
                        isRecovery = false,
                        volumeLabel = effectiveLabel,
                        volumeGuid = effectiveGuid
                    )
                }.start()
            }

            BitLockerCoreService.updateForegroundState(context)
            com.bitlockerdroid.provider.BitLockerDocumentsProvider.notifyRootsChanged(context)
            notifyStateChanged()
            Result.success(core)
        } catch (e: UnlockException) {
            Log.w(TAG, "unlock failed for $devicePath", e)
            registerDetected(devicePath, expectedGuid)
            notifyStateChanged()
            Result.failure(e)
        } catch (e: Exception) {
            Log.e(TAG, "unlock error", e)
            registerDetected(devicePath, expectedGuid)
            notifyStateChanged()
            Result.failure(e)
        }
    }

    /**
     * Ensures a session for [devicePath] exists, re-unlocking from the saved
     * encrypted password if this process lost it (e.g. after a kill). Returns
     * the core or null.
     */
    fun ensureUnlocked(context: Context, devicePath: String, offset: Long, guid: String? = null): DislockerCore? {
        synchronized(lock) {
            sessions[devicePath]?.let { return it }
        }
        val volId = guid ?: BitLockerDetector.getVolumeGuid(devicePath) ?: return null
        val blob = PreferenceHelper.getRememberedPassword(context, volId) ?: return null
        val password = KeyGuardService.decrypt(blob) ?: return null
        // Re-unlock with the saved password; keep it remembered.
        val result = unlockWithPassword(context, devicePath, offset, password, true, expectedGuid = volId)
        return result.getOrNull()
    }

    fun lock(devicePath: String) {
        val app = com.bitlockerdroid.util.ContextProvider.app
        VirtualStorageMountManager.unmount(devicePath)
        val devInfo = com.bitlockerdroid.util.DeviceIdentity.queryDeviceInfo(devicePath, forceRefresh = true)
        val stale: DislockerCore?
        synchronized(lock) {
            val core = sessions.remove(devicePath)
            stale = core
            val guid = core?.volumeGuid ?: BitLockerDetector.getVolumeGuid(devicePath)
            val recoveryKeyId = core?.recoveryKeyId ?: BitLockerDetector.getRecoveryKeyId(devicePath)
            if (!guid.isNullOrBlank()) {
                manuallyLockedGuids.add(guid)
            }
            manuallyLockedGuids.add(devicePath)
            LogFile.write("app", "UnlockManager.lock: manually locked $devicePath (guid=$guid, rkId=$recoveryKeyId)")

            detected[devicePath] = DetectedVolume(
                devicePath = devicePath,
                guid = guid,
                recoveryKeyId = recoveryKeyId,
                deviceName = devInfo.friendlyName,
                capacity = devInfo.sizeBytes
            )
        }
        stale?.let { core ->
            // Safe eject: let in-flight SAF pipe writes land, then flush the
            // encrypted block device before tearing the session down.
            com.bitlockerdroid.provider.BitLockerDocumentsProvider.drainActiveWrites()
            core.flush()
            core.close()
        }
        app?.let {
            BitLockerCoreService.updateForegroundState(it)
            com.bitlockerdroid.provider.BitLockerDocumentsProvider.notifyRootsChanged(it)
        }
        notifyStateChanged()
    }

    /**
     * Removes detected-but-not-unlocked entries whose block node is no longer
     * present on the bus (device unplugged). Also closes and drops unlocked
     * sessions whose node disappeared — otherwise a stale session keeps the
     * volume listed and blocks detection of a newly-inserted drive that reuses
     * the same vold node path.
     */
    fun forgetDetectedMissing(presentNodes: List<String>) {
        var removedAny = false
        val toClose = mutableListOf<Pair<String, DislockerCore>>()
        com.bitlockerdroid.util.DeviceIdentity.clearCache()
        StorageNotificationSuppressor.forgetMissing(presentNodes)
        synchronized(lock) {
            val present = presentNodes.toHashSet()
            val missingDetected = detected.keys.filter { !present.contains(it) }
            if (missingDetected.isNotEmpty()) {
                missingDetected.forEach { path ->
                    detected[path]?.guid?.let { manuallyLockedGuids.remove(it) }
                    manuallyLockedGuids.remove(path)
                    detected.remove(path)
                }
                removedAny = true
            }
            val missingSessions = sessions.keys.filter { !present.contains(it) }
            if (missingSessions.isNotEmpty()) {
                missingSessions.forEach { path ->
                    val core = sessions.remove(path)
                    if (core != null) {
                        core.volumeGuid?.let { manuallyLockedGuids.remove(it) }
                        manuallyLockedGuids.remove(path)
                        toClose.add(path to core)
                        Log.i(TAG, "forgetDetectedMissing: closing stale session for $path (node gone)")
                    }
                }
            }
        }
        // Close and unmount outside the lock: both are slow IO.
        toClose.forEach { (path, core) ->
            core.close()
            VirtualStorageMountManager.unmount(path)
        }
        val closedAny = toClose.isNotEmpty()
        com.bitlockerdroid.util.ContextProvider.app?.let { app ->
            BitLockerCoreService.updateForegroundState(app)
            if (closedAny) {
                com.bitlockerdroid.provider.BitLockerDocumentsProvider.notifyRootsChanged(app)
            }
        }
        if (closedAny || removedAny) {
            notifyStateChanged()
        }
    }

    /** Reset state when a USB detachment is reported. */
    fun onUsbDetached() {
        VirtualStorageMountManager.unmountAll()
        com.bitlockerdroid.util.DeviceIdentity.clearCache()
        try {
            val nodes = BitLockerDetector.enumerateVoldNodes()
            forgetDetectedMissing(nodes)
        } catch (_: Exception) {}
    }

    /** Registers an already-opened core session (recovery-key path). */
    fun registerDirect(
        core: DislockerCore,
        context: Context?,
        key: String? = null,
        isRecovery: Boolean = false
    ) {
        val stale: DislockerCore?
        synchronized(lock) {
            val guid = core.volumeGuid ?: BitLockerDetector.getVolumeGuid(core.devicePath)
            if (!guid.isNullOrBlank()) {
                manuallyLockedGuids.remove(guid)
            }
            manuallyLockedGuids.remove(core.devicePath)
            stale = sessions.put(core.devicePath, core)
            detected.remove(core.devicePath)
        }
        stale?.close()

        if (key != null && context != null && VirtualStorageMountManager.isEnabled(context) && VirtualStorageMountManager.isSupported()) {
            val guid = core.volumeGuid ?: ""
            val effectiveLabel = core.volumeLabel.ifBlank {
                com.bitlockerdroid.util.DeviceIdentity.queryDeviceInfo(core.devicePath).friendlyName
            }
            Thread {
                VirtualStorageMountManager.mount(
                    context = context,
                    devicePath = core.devicePath,
                    offset = core.offset,
                    key = key,
                    isRecovery = isRecovery,
                    volumeLabel = effectiveLabel,
                    volumeGuid = guid
                )
            }.start()
        }

        context?.let {
            BitLockerCoreService.updateForegroundState(it)
            com.bitlockerdroid.provider.BitLockerDocumentsProvider.notifyRootsChanged(it)
        }
        notifyStateChanged()
    }

    fun get(devicePath: String): DislockerCore? = synchronized(lock) {
        sessions[devicePath]
    }

    /**
     * Restores all remembered (saved-password) volumes into memory so the
     * DocumentsProvider can serve them even after this process was killed.
     * Matches currently connected block nodes strictly by persistent Volume GUID.
     * Returns the count restored.
     */
    fun restoreRemembered(context: Context): Int {
        var restored = 0

        // Scan currently connected block devices and match by persistent Volume GUID
        try {
            val nodes = BitLockerDetector.enumerateVoldNodes()
            for (node in nodes) {
                val has = synchronized(lock) { sessions.containsKey(node) }
                if (has) continue

                val info = BitLockerDetector.readHeaderInfo(node) ?: continue
                val guid = info.guid ?: continue
                if (isManuallyLocked(guid, node)) {
                    LogFile.write("app", "restoreRemembered: skipping $node ($guid) - manually locked")
                    continue
                }
                if (!PreferenceHelper.isAutoUnlockEnabled(context, guid)) continue
                val blob = PreferenceHelper.getRememberedPassword(context, guid) ?: continue
                val password = KeyGuardService.decrypt(blob) ?: continue

                val r = unlockWithPassword(context, node, 0, password, true, expectedGuid = guid)
                if (r.isSuccess) {
                    restored++
                    Log.i(TAG, "restoreRemembered: unlocked $node via GUID $guid")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "restoreRemembered: bus scan failed", e)
        }

        return restored
    }

    /** Called when a BitLocker volume is found on the bus (from the user's Scan
     *  action). Records the volume so it appears in the management UI, and
     *  auto-unlocks if a saved password exists AND auto-unlock is enabled.
     *  No notification is posted — detection is user-driven via the Scan button. */
    fun onDeviceDetected(context: Context, devicePath: String, offset: Long, guid: String? = null, recoveryKeyId: String? = null) {
        val volumeId = guid ?: BitLockerDetector.getVolumeGuid(devicePath)
        val rkId = recoveryKeyId ?: BitLockerDetector.getRecoveryKeyId(devicePath)

        // Check if an existing session on this node has a DIFFERENT volume GUID (user swapped drive on same USB port)
        val swappedOut: DislockerCore? = synchronized(lock) {
            val existing = sessions[devicePath]
            if (existing != null) {
                val existingGuid = existing.volumeGuid
                if (!volumeId.isNullOrBlank() && !existingGuid.isNullOrBlank() && volumeId != existingGuid) {
                    sessions.remove(devicePath)
                } else null
            } else null
        }
        if (swappedOut != null) {
            LogFile.write("app", "onDeviceDetected: volume swapped at $devicePath, closing stale session")
            swappedOut.close()
            com.bitlockerdroid.provider.BitLockerDocumentsProvider.notifyRootsChanged(context)
            notifyStateChanged()
        }

        // Record the volume so it stays reachable from the management UI.
        registerDetected(devicePath, volumeId, rkId)

        if (isUnlocked(devicePath)) return
        if (volumeId.isNullOrBlank()) return

        if (isManuallyLocked(volumeId, devicePath)) {
            LogFile.write("app", "onDeviceDetected: skipping background auto-unlock for $devicePath (guid=$volumeId) - manually locked")
            return
        }

        // Per-drive auto-unlock check:
        if (!PreferenceHelper.isAutoUnlockEnabled(context, volumeId)) {
            LogFile.write("app", "onDeviceDetected: auto-unlock is disabled for $volumeId")
            return
        }

        val rememberBlob = PreferenceHelper.getRememberedPassword(context, volumeId)
        if (rememberBlob != null) {
            val password = KeyGuardService.decrypt(rememberBlob)
            if (password != null) {
                if (!inProgressUnlocks.add(devicePath)) {
                    LogFile.write("app", "onDeviceDetected: unlock already in progress for $devicePath")
                    return
                }
                unlockingLatch.incrementAndGet()
                Thread {
                    try {
                        val r = unlockWithPassword(context, devicePath, offset, password, true, expectedGuid = volumeId)
                        if (r.isFailure) {
                            LogFile.write(
                                "app",
                                "auto-unlock failed for $devicePath (guid=$volumeId): ${r.exceptionOrNull()?.message}"
                            )
                        } else {
                            LogFile.write("app", "auto-unlock succeeded for $devicePath (guid=$volumeId)")
                        }
                    } finally {
                        inProgressUnlocks.remove(devicePath)
                        unlockingLatch.decrementAndGet()
                        synchronized(unlockWaitLock) {
                            (unlockWaitLock as java.lang.Object).notifyAll()
                        }
                    }
                }.start()
            }
        }
    }

    fun showUnlockDialog(context: Context, devicePath: String, offset: Long, guid: String? = null, recoveryKeyId: String? = null) {
        val intent = Intent(context, UnlockDialogActivity::class.java).apply {
            putExtra(UnlockDialogActivity.EXTRA_DEVICE_PATH, devicePath)
            putExtra(UnlockDialogActivity.EXTRA_OFFSET, offset)
            if (guid != null) putExtra(UnlockDialogActivity.EXTRA_GUID, guid)
            if (recoveryKeyId != null) putExtra(UnlockDialogActivity.EXTRA_RECOVERY_KEY_ID, recoveryKeyId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        try {
            context.startActivity(intent)
            com.bitlockerdroid.util.LogFile.write("app", "unlock dialog launched for $devicePath (guid=$guid, rkId=$recoveryKeyId)")
        } catch (e: Exception) {
            Log.e(TAG, "cannot show unlock dialog", e)
            com.bitlockerdroid.util.LogFile.write("app", "cannot show unlock dialog: ${e.message}")
        }
    }

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "BitLocker status",
            NotificationManager.IMPORTANCE_LOW
        )
        nm.createNotificationChannel(channel)
    }
}

data class UnlockedVolume(
    val devicePath: String,
    val size: Long,
    val label: String,
    val fsType: String = "",
    val cipher: String = "",
    val canWrite: Boolean = true,
    val guid: String? = null,
    val recoveryKeyId: String? = null,
    val deviceName: String = "",
    val freeBytes: Long = 0L,
    val usedBytes: Long = 0L
)

/** A BitLocker volume detected on the bus but not yet unlocked. */
data class DetectedVolume(
    val devicePath: String,
    val guid: String? = null,
    val recoveryKeyId: String? = null,
    val deviceName: String = "",
    val capacity: Long = 0L
)
