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
    const val CHANNEL_ID_ALERTS = "bitlocker_alerts"
    const val RECOVERY_PREFIX = "RECOVERY:"

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
            val seenGuids = mutableSetOf<String>()
            val result = mutableListOf<UnlockedVolume>()
            for ((path, core) in snapshot) {
                val g = core.volumeGuid
                if (!g.isNullOrBlank() && !seenGuids.add(g.lowercase())) {
                    continue
                }
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
                val serial = try { core.reader.volumeSerial() } catch (_: Exception) { 0L }
                result.add(
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
                        usedBytes = usedBytes,
                        sectorSize = core.info.sectorSize,
                        volumeSerial = serial,
                        isRecovery = core.isRecovery,
                        isDirty = core.isDirty
                    )
                )
            }
            return result
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
            val activeGuids = sessions.values.mapNotNull { it.volumeGuid?.lowercase() }.toSet()
            val seenGuids = mutableSetOf<String>()
            val result = mutableListOf<DetectedVolume>()
            for (d in detected.values) {
                if (sessions.containsKey(d.devicePath)) continue
                val g = d.guid
                if (!g.isNullOrBlank()) {
                    val lower = g.lowercase()
                    if (activeGuids.contains(lower)) continue
                    if (!seenGuids.add(lower)) continue
                }
                result.add(d)
            }
            result
        }

    val activeSessions: List<DislockerCore>
        get() = synchronized(lock) {
            sessions.values.toList()
        }

    fun isUnlocked(devicePath: String): Boolean = synchronized(lock) {
        if (sessions.containsKey(devicePath)) return true
        val g = detected[devicePath]?.guid
        if (!g.isNullOrBlank() && sessions.values.any { it.volumeGuid.equals(g, ignoreCase = true) }) {
            return true
        }
        return false
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
        // Query heavy info outside the lock so UI queries are never blocked
        val effectiveGuid = guid ?: BitLockerDetector.getVolumeGuid(devicePath)
        val effectiveRecoveryKeyId = recoveryKeyId ?: BitLockerDetector.getRecoveryKeyId(devicePath)
        val devInfo = com.bitlockerdroid.util.DeviceIdentity.queryDeviceInfo(devicePath, forceRefresh = false)

        var changed = false
        synchronized(lock) {
            val isAlreadyUnlocked = sessions.containsKey(devicePath) ||
                (!effectiveGuid.isNullOrBlank() && sessions.values.any { it.volumeGuid.equals(effectiveGuid, ignoreCase = true) })
            if (!isAlreadyUnlocked) {
                if (!effectiveGuid.isNullOrBlank()) {
                    val duplicateEntry = detected.entries.find { it.key != devicePath && it.value.guid.equals(effectiveGuid, ignoreCase = true) }
                    if (duplicateEntry != null) {
                        return
                    }
                }
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
        com.bitlockerdroid.util.ContextProvider.app?.let { app ->
            dismissAllNotificationsForDevice(app, devicePath)
        }
        var removed = false
        synchronized(lock) {
            val entry = detected.remove(devicePath)
            if (entry != null) {
                removed = true
                val g = entry.guid
                if (!g.isNullOrBlank()) {
                    val aliases = detected.entries.filter { it.value.guid.equals(g, ignoreCase = true) }.map { it.key }
                    aliases.forEach { detected.remove(it) }
                }
            }
        }
        if (removed) {
            notifyStateChanged()
        }
    }

    /** True if [devicePath] is either unlocked or pending unlock in the list. */
    fun isKnown(devicePath: String): Boolean = synchronized(lock) {
        sessions.containsKey(devicePath) || detected.containsKey(devicePath)
    }

    /** True if a volume with [guid] is already unlocked or pending unlock. */
    fun isGuidKnown(guid: String?): Boolean {
        if (guid.isNullOrBlank()) return false
        synchronized(lock) {
            if (sessions.values.any { it.volumeGuid == guid }) return true
            if (detected.values.any { it.guid == guid }) return true
        }
        return false
    }

    /** Returns the known Volume GUID associated with [devicePath], if known. */
    fun getGuidForPath(devicePath: String): String? = synchronized(lock) {
        sessions[devicePath]?.volumeGuid ?: detected[devicePath]?.guid
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
        return unlockWithCredential(
            context = context,
            devicePath = devicePath,
            offset = offset,
            credential = password,
            isRecovery = false,
            remember = remember,
            expectedGuid = expectedGuid
        )
    }

    /** Called by the unlock dialog with a 48-digit recovery key. */
    fun unlockWithRecoveryKey(
        context: Context,
        devicePath: String,
        offset: Long,
        recoveryKey: String,
        remember: Boolean,
        expectedGuid: String? = null
    ): Result<DislockerCore> {
        return unlockWithCredential(
            context = context,
            devicePath = devicePath,
            offset = offset,
            credential = recoveryKey,
            isRecovery = true,
            remember = remember,
            expectedGuid = expectedGuid
        )
    }

    /** Unified unlock logic supporting both passwords and 48-digit recovery keys. */
    fun unlockWithCredential(
        context: Context,
        devicePath: String,
        offset: Long,
        credential: String,
        isRecovery: Boolean,
        remember: Boolean,
        expectedGuid: String? = null
    ): Result<DislockerCore> {
        return try {
            val core = if (isRecovery) {
                DislockerCore.openWithRecoveryKey(devicePath, offset, credential)
            } else {
                DislockerCore.open(devicePath, offset, credential)
            }
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
                if (!guid.isNullOrBlank()) {
                    val aliases = detected.entries.filter { it.value.guid.equals(guid, ignoreCase = true) }.map { it.key }
                    aliases.forEach { detected.remove(it) }
                }
                old
            }
            stale?.close()
            // Save the encrypted credential only if the user asked to remember it.
            // Keyed strictly by persistent Volume GUID rather than ephemeral device node.
            if (!guid.isNullOrBlank()) {
                if (remember) {
                    val rawStored = if (isRecovery) "$RECOVERY_PREFIX$credential" else credential
                    KeyGuardService.encrypt(rawStored)?.let { blob ->
                        val friendly = core.volumeLabel.ifBlank {
                            com.bitlockerdroid.util.DeviceIdentity.queryDeviceInfo(devicePath, forceRefresh = true).friendlyName
                        }
                        PreferenceHelper.saveRememberedPassword(context, guid, blob, friendlyName = friendly)
                    }
                } else {
                    PreferenceHelper.clearRememberedPassword(context, guid)
                }
            } else {
                LogFile.write("app", "unlockWithCredential: no volume GUID found for $devicePath, skipping credential persistence")
            }

            // Trigger userspace FUSE virtual mount to /storage/BitLocker_<Label> only for real kernel block devices
            val effectiveGuid = guid ?: ""
            val effectiveLabel = core.volumeLabel.ifBlank {
                com.bitlockerdroid.util.DeviceIdentity.queryDeviceInfo(devicePath).friendlyName
            }
            if (VirtualStorageMountManager.isEnabled(context) && VirtualStorageMountManager.isSupported()) {
                val resolvedBlock = VirtualStorageMountManager.resolveBlockDevice(devicePath, effectiveGuid)
                if (resolvedBlock != null) {
                    Thread {
                        VirtualStorageMountManager.mount(
                            context = context,
                            devicePath = resolvedBlock,
                            offset = offset,
                            key = credential,
                            isRecovery = isRecovery,
                            volumeLabel = effectiveLabel,
                            volumeGuid = effectiveGuid,
                            originalPath = devicePath
                        )
                    }.start()
                }
            }

            dismissLockedNotification(context, devicePath)
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
     * encrypted password or recovery key if this process lost it (e.g. after a kill).
     * Returns the core or null.
     */
    fun ensureUnlocked(context: Context, devicePath: String, offset: Long, guid: String? = null): DislockerCore? {
        synchronized(lock) {
            sessions[devicePath]?.let { return it }
        }
        val volId = guid ?: BitLockerDetector.getVolumeGuid(devicePath) ?: return null
        val blob = PreferenceHelper.getRememberedPassword(context, volId) ?: return null
        val raw = KeyGuardService.decrypt(blob) ?: return null
        val isRecovery = raw.startsWith(RECOVERY_PREFIX)
        val cleanKey = if (isRecovery) raw.removePrefix(RECOVERY_PREFIX) else raw
        // Re-unlock with the saved credential; keep it remembered.
        val result = unlockWithCredential(context, devicePath, offset, cleanKey, isRecovery, true, expectedGuid = volId)
        return result.getOrNull()
    }

    /**
     * Safely ejects an unlocked BitLocker volume:
     * 1. Unmounts FUSE virtual mount and waits for daemon clean exit
     * 2. Drains in-flight SAF stream writes
     * 3. Flushes and closes decrypted session and block device
     * 4. Updates DocumentsProvider roots, foreground state, and posts safe-to-unplug notification
     */
    fun safeEject(devicePath: String): Result<String> {
        val app = com.bitlockerdroid.util.ContextProvider.app
        val devInfo = com.bitlockerdroid.util.DeviceIdentity.queryDeviceInfo(devicePath, forceRefresh = true)
        val stale: DislockerCore?
        val effectiveLabel: String
        synchronized(lock) {
            val core = sessions.remove(devicePath)
            stale = core
            val guid = core?.volumeGuid ?: BitLockerDetector.getVolumeGuid(devicePath)
            val recoveryKeyId = core?.recoveryKeyId ?: BitLockerDetector.getRecoveryKeyId(devicePath)
            if (!guid.isNullOrBlank()) {
                manuallyLockedGuids.add(guid)
            }
            manuallyLockedGuids.add(devicePath)
            val fallbackLabel = app?.getString(R.string.notification_drive_title) ?: "BitLocker"
            effectiveLabel = core?.volumeLabel?.ifBlank { devInfo.friendlyName.ifBlank { fallbackLabel } }
                ?: devInfo.friendlyName.ifBlank { fallbackLabel }

            LogFile.write("app", "UnlockManager.safeEject: ejecting $devicePath ($effectiveLabel, guid=$guid, rkId=$recoveryKeyId)")

            detected[devicePath] = DetectedVolume(
                devicePath = devicePath,
                guid = guid,
                recoveryKeyId = recoveryKeyId,
                deviceName = devInfo.friendlyName,
                capacity = devInfo.sizeBytes
            )
        }

        // 1. Unmount POSIX FUSE mount first so external apps can no longer issue IO
        try {
            VirtualStorageMountManager.unmount(devicePath)
        } catch (e: Throwable) {
            Log.w(TAG, "Virtual mount unmount warning for $devicePath", e)
        }

        // 2. Drain in-flight SAF pipe writes (bounded wait)
        com.bitlockerdroid.provider.BitLockerDocumentsProvider.drainActiveWrites()

        // 3. Flush & close decrypted session
        stale?.let { core ->
            try {
                core.flush()
            } catch (e: Throwable) {
                Log.w(TAG, "core flush warning during eject", e)
            }
            try {
                core.close()
            } catch (e: Throwable) {
                Log.w(TAG, "core close warning during eject", e)
            }
        }

        // 4. Update system framework, notifications and roots
        app?.let {
            dismissAllNotificationsForDevice(it, devicePath)
            BitLockerCoreService.updateForegroundState(it)
            com.bitlockerdroid.provider.BitLockerDocumentsProvider.notifyRootsChanged(it)
            postSafeToUnplugNotification(it, devicePath, effectiveLabel)
        }
        notifyStateChanged()
        return Result.success(effectiveLabel)
    }

    fun lock(devicePath: String) {
        safeEject(devicePath)
    }

    /** Safely ejects all active unlocked BitLocker volumes. */
    fun safeEjectAll(): List<Result<String>> {
        val paths = synchronized(lock) { sessions.keys.toList() }
        val results = paths.map { safeEject(it) }
        try {
            VirtualStorageMountManager.unmountAll()
        } catch (_: Throwable) {}
        return results
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
        com.bitlockerdroid.util.DeviceIdentity.retainOnly(presentNodes)
        StorageNotificationSuppressor.forgetMissing(presentNodes)
        val missingDetectedList = mutableListOf<String>()
        val missingSessionsList = mutableListOf<String>()
        synchronized(lock) {
            val present = presentNodes.toHashSet()
            val missingDetected = detected.keys.filter { !present.contains(it) }
            if (missingDetected.isNotEmpty()) {
                missingDetected.forEach { path ->
                    detected[path]?.guid?.let { manuallyLockedGuids.remove(it) }
                    manuallyLockedGuids.remove(path)
                    detected.remove(path)
                    missingDetectedList.add(path)
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
                        missingSessionsList.add(path)
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
            missingDetectedList.forEach { path -> dismissAllNotificationsForDevice(app, path) }
            missingSessionsList.forEach { path -> dismissAllNotificationsForDevice(app, path) }
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
        com.bitlockerdroid.util.ContextProvider.app?.let { app ->
            val nm = app.getSystemService(NotificationManager::class.java)
            nm?.cancelAll()
        }
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
            val resolvedBlock = VirtualStorageMountManager.resolveBlockDevice(core.devicePath, guid)
            if (resolvedBlock != null) {
                Thread {
                    VirtualStorageMountManager.mount(
                        context = context,
                        devicePath = resolvedBlock,
                        offset = core.offset,
                        key = key,
                        isRecovery = isRecovery,
                        volumeLabel = effectiveLabel,
                        volumeGuid = guid,
                        originalPath = core.devicePath
                    )
                }.start()
            }
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
     * Scans both kernel block devices and USB Host partitions and auto-unlocks remembered drives.
     * Returns the count restored.
     */
    fun restoreRemembered(context: Context): Int {
        return BitLockerDetector.scanAndDetect(context)
    }

    /** Called when a BitLocker volume is found on the bus. Records the volume so it appears in the
     *  management UI, and auto-unlocks if a saved credential exists AND auto-unlock is enabled.
     *  Posts a high-priority notification to open directly if ready, or to prompt unlock if locked. */
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

        val alreadyUnlocked = synchronized(lock) {
            sessions.containsKey(devicePath) ||
            (!volumeId.isNullOrBlank() && sessions.values.any { it.volumeGuid.equals(volumeId, ignoreCase = true) })
        }
        if (alreadyUnlocked) {
            LogFile.write("app", "onDeviceDetected: volume $devicePath (guid=$volumeId) is already unlocked")
            return
        }
        if (volumeId.isNullOrBlank()) {
            notifyVolumeLocked(context, devicePath, offset, volumeId, rkId)
            return
        }

        if (isManuallyLocked(volumeId, devicePath)) {
            LogFile.write("app", "onDeviceDetected: skipping background auto-unlock for $devicePath (guid=$volumeId) - manually locked")
            notifyVolumeLocked(context, devicePath, offset, volumeId, rkId)
            return
        }

        // Per-drive auto-unlock check:
        if (!PreferenceHelper.isAutoUnlockEnabled(context, volumeId)) {
            LogFile.write("app", "onDeviceDetected: auto-unlock is disabled for $volumeId")
            notifyVolumeLocked(context, devicePath, offset, volumeId, rkId)
            return
        }

        val rememberBlob = PreferenceHelper.getRememberedPassword(context, volumeId)
        if (rememberBlob != null) {
            val raw = KeyGuardService.decrypt(rememberBlob)
            if (raw != null) {
                val isRecovery = raw.startsWith(RECOVERY_PREFIX)
                val cleanKey = if (isRecovery) raw.removePrefix(RECOVERY_PREFIX) else raw
                val lockKey = volumeId
                if (!inProgressUnlocks.add(lockKey)) {
                    LogFile.write("app", "onDeviceDetected: unlock already in progress for $lockKey")
                    return
                }
                unlockingLatch.incrementAndGet()
                Thread {
                    try {
                        val r = unlockWithCredential(context, devicePath, offset, cleanKey, isRecovery, true, expectedGuid = volumeId)
                        if (r.isFailure) {
                            LogFile.write(
                                "app",
                                "auto-unlock failed for $devicePath (guid=$volumeId): ${r.exceptionOrNull()?.message}"
                            )
                            notifyVolumeLocked(context, devicePath, offset, volumeId, rkId)
                        } else {
                            LogFile.write("app", "auto-unlock succeeded for $devicePath (guid=$volumeId)")
                        }
                    } finally {
                        inProgressUnlocks.remove(lockKey)
                        unlockingLatch.decrementAndGet()
                        synchronized(unlockWaitLock) {
                            (unlockWaitLock as java.lang.Object).notifyAll()
                        }
                    }
                }.start()
                return
            }
        }

        // No saved credential or auto-unlock disabled: post high-priority unlock prompt notification
        notifyVolumeLocked(context, devicePath, offset, volumeId, rkId)
    }

    fun notifyVolumeReady(context: Context, devicePath: String, core: DislockerCore) {
        // Handled via BitLockerCoreService ongoing foreground notification with file manager & safe eject actions.
    }

    fun notifyVolumeLocked(context: Context, devicePath: String, offset: Long, guid: String? = null, recoveryKeyId: String? = null) {
        if (!PreferenceHelper.isNotificationsEnabled(context)) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        ensureChannels(context)

        val devInfo = com.bitlockerdroid.util.DeviceIdentity.queryDeviceInfo(devicePath)
        val friendly = devInfo.friendlyName.ifBlank { context.getString(R.string.usb_storage_device) }

        val unlockIntent = Intent(context, UnlockDialogActivity::class.java).apply {
            putExtra(UnlockDialogActivity.EXTRA_DEVICE_PATH, devicePath)
            putExtra(UnlockDialogActivity.EXTRA_OFFSET, offset)
            if (guid != null) putExtra(UnlockDialogActivity.EXTRA_GUID, guid)
            if (recoveryKeyId != null) putExtra(UnlockDialogActivity.EXTRA_RECOVERY_KEY_ID, recoveryKeyId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val unlockPendingIntent = PendingIntent.getActivity(
            context,
            (devicePath.hashCode() + 2) and 0x7FFFFFFF,
            unlockIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notif = NotificationCompat.Builder(context, CHANNEL_ID_ALERTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notification_drive_detected_title))
            .setContentText(context.getString(R.string.notification_drive_detected_desc, friendly))
            .setContentIntent(unlockPendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .build()

        nm.notify("bitlocker_locked:$devicePath", 2, notif)
    }

    fun dismissReadyNotification(context: Context, devicePath: String) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.cancel("bitlocker_ready:$devicePath", 1)
    }

    fun dismissLockedNotification(context: Context, devicePath: String) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.cancel("bitlocker_locked:$devicePath", 2)
    }

    fun dismissEjectedNotification(context: Context, devicePath: String) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.cancel("bitlocker_ejected:$devicePath", 3)
    }

    fun dismissAllNotificationsForDevice(context: Context, devicePath: String) {
        dismissReadyNotification(context, devicePath)
        dismissLockedNotification(context, devicePath)
        dismissEjectedNotification(context, devicePath)
    }

    fun postSafeToUnplugNotification(context: Context, devicePath: String, label: String) {
        if (!PreferenceHelper.isNotificationsEnabled(context)) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        ensureChannels(context)

        val title = context.getString(R.string.safe_to_unplug_title, label)
        val text = context.getString(R.string.safe_to_unplug_desc)

        val notif = NotificationCompat.Builder(context, CHANNEL_ID_ALERTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setTimeoutAfter(8000)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        nm.notify("bitlocker_ejected:$devicePath", 3, notif)
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

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_status_channel),
            NotificationManager.IMPORTANCE_LOW
        )
        nm.createNotificationChannel(channel)

        val alertChannel = NotificationChannel(
            CHANNEL_ID_ALERTS,
            context.getString(R.string.notification_alert_channel),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.notification_alert_desc)
            enableVibration(true)
        }
        nm.createNotificationChannel(alertChannel)
    }

    fun ensureChannel(context: Context) = ensureChannels(context)
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
    val usedBytes: Long = 0L,
    val sectorSize: Int = 512,
    val volumeSerial: Long = 0L,
    val isRecovery: Boolean = false,
    val isDirty: Boolean = false
)

/** A BitLocker volume detected on the bus but not yet unlocked. */
data class DetectedVolume(
    val devicePath: String,
    val guid: String? = null,
    val recoveryKeyId: String? = null,
    val deviceName: String = "",
    val capacity: Long = 0L
)
