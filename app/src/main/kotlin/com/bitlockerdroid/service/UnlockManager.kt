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
    private val sessionCredentials = java.util.concurrent.ConcurrentHashMap<String, Pair<String, Boolean>>()

    fun getSessionCredential(devicePath: String, guid: String? = null): Pair<String, Boolean>? {
        return synchronized(lock) {
            fun isUsable(path: String, core: DislockerCore): Boolean =
                !core.isClosed && !closingSessions.contains(path) &&
                    (guid.isNullOrBlank() || core.volumeGuid.equals(guid, ignoreCase = true))
            sessions[devicePath]?.takeIf { isUsable(devicePath, it) }?.let {
                sessionCredentials[devicePath]?.let { credential -> return@synchronized credential }
            }
            if (!guid.isNullOrBlank()) {
                sessions.entries.firstOrNull { isUsable(it.key, it.value) }
                    ?.let { sessionCredentials[it.key] }
            } else null
        }
    }

    /** devicePath -> detected-but-not-yet-unlocked volume. */
    private val detected = HashMap<String, DetectedVolume>()

    private val lock = Any()
    private val closingSessions = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val parentDiskLocks = java.util.concurrent.ConcurrentHashMap<String, Any>()

    private fun getParentDiskLock(parentDisk: String): Any {
        return parentDiskLocks.computeIfAbsent(parentDisk) { Any() }
    }

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
            val snapshot = synchronized(lock) {
                sessions.filterKeys { !closingSessions.contains(it) }.toList()
            }
            val seenGuids = mutableSetOf<String>()
            val result = mutableListOf<UnlockedVolume>()
            for ((path, core) in snapshot) {
                var volumeGuid: String? = null
                val volume = try {
                    synchronized(core) {
                        val stillCurrent = synchronized(lock) {
                            sessions[path] === core && !closingSessions.contains(path)
                        }
                        if (!stillCurrent || core.isClosed) return@synchronized null
                        val currentGuid = core.volumeGuid
                        volumeGuid = currentGuid
                        if (!currentGuid.isNullOrBlank() && currentGuid.lowercase() in seenGuids) {
                            return@synchronized null
                        }

                        // Reader validation is read-only. Do not initialize a
                        // writer just to populate this management snapshot.
                        val reader = core.reader
                        val fsName = when (reader) {
                            is com.bitlockerdroid.ntfs.NtfsReader -> "NTFS"
                            is com.bitlockerdroid.ntfs.ExFatReader -> "exFAT"
                            is com.bitlockerdroid.ntfs.Fat32Reader -> "FAT32"
                            else -> "Unknown"
                        }
                        val devInfo = com.bitlockerdroid.util.DeviceIdentity.queryDeviceInfo(path)
                        val partInfo = com.bitlockerdroid.util.DeviceIdentity.resolvePartitionInfo(path)
                        val space = core.getSpaceInfoIfWriterInitialized()
                        val totalBytes = if (space != null && space.first > 0L) space.first else core.info.volumeSize
                        val freeBytes = space?.second ?: -1L
                        val usedBytes = if (freeBytes >= 0L) (totalBytes - freeBytes).coerceAtLeast(0L) else -1L
                        val serial = reader.volumeSerial()
                        val isRo = com.bitlockerdroid.util.ContextProvider.app?.let { app ->
                            PreferenceHelper.isVolumeReadOnly(app, volumeGuid, path)
                        } ?: false
                        val readerSupportsWrite =
                            reader is com.bitlockerdroid.ntfs.NtfsReader ||
                                reader is com.bitlockerdroid.ntfs.ExFatReader ||
                                reader is com.bitlockerdroid.ntfs.Fat32Reader
                        val canWrite = core.canWriteWithoutInitializingWriter(readerSupportsWrite) && !isRo
                        UnlockedVolume(
                            devicePath = path,
                            size = totalBytes,
                            label = core.volumeLabel,
                            fsType = fsName,
                            cipher = core.info.algorithmName,
                            canWrite = canWrite,
                            guid = volumeGuid,
                            recoveryKeyId = core.recoveryKeyId,
                            deviceName = devInfo.friendlyName,
                            freeBytes = freeBytes,
                            usedBytes = usedBytes,
                            sectorSize = core.info.sectorSize,
                            volumeSerial = serial,
                            isRecovery = core.isRecovery,
                            isDirty = reader.isDirty,
                            partitionIndex = partInfo.partitionIndex,
                            partitionName = partInfo.partitionNodeName
                        )
                    }
                } catch (e: Exception) {
                    LogFile.write("app", "unlockedVolumes: omitting unreadable session $path: ${e.message}")
                    null
                }
                if (volume != null) {
                    volumeGuid?.lowercase()?.let { seenGuids.add(it) }
                    result.add(volume)
                }
            }
            return result
        }

    /**
     * Volumes that carry a BitLocker signature but are not unlocked yet.
     * Persists across reformats because the entry is keyed by the block node
     * path and re-registered on each successful signature scan, so a stale
     * entry may keep a reformatted drive listed. Visible in the management UI
     * so the user can unlock manually.
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

    private val unencrypted = mutableListOf<UnencryptedVolume>()

    val unencryptedVolumes: List<UnencryptedVolume>
        get() = synchronized(lock) {
            unencrypted.toList()
        }

    fun updateUnencryptedVolumes(volumes: List<UnencryptedVolume>) {
        synchronized(lock) {
            unencrypted.clear()
            unencrypted.addAll(volumes)
        }
    }

    val activeSessions: List<DislockerCore>
        get() = synchronized(lock) {
            sessions.values.toList()
        }

    val isEmpty: Boolean
        get() = synchronized(lock) {
            sessions.isEmpty() && detected.isEmpty() && unencrypted.isEmpty()
        }

    fun isUnlocked(devicePath: String): Boolean = synchronized(lock) {
        if (sessions.containsKey(devicePath)) return true
        val g = detected[devicePath]?.guid
        if (!g.isNullOrBlank() && sessions.values.any { it.volumeGuid.equals(g, ignoreCase = true) }) {
            return true
        }
        return false
    }

    /** True if an active decrypted session exists for this volume GUID or device path and hardware is still connected. */
    fun isVolumeSessionActive(guidOrDevicePath: String?): Boolean {
        if (guidOrDevicePath.isNullOrBlank()) return false
        val session: DislockerCore? = synchronized(lock) {
            sessions[guidOrDevicePath] ?: sessions.values.firstOrNull {
                it.volumeGuid.equals(guidOrDevicePath, ignoreCase = true) ||
                it.devicePath == guidOrDevicePath
            }
        }
        if (session == null) return false

        // For non-root USB Host mode, check physical USB connection directly
        if (session.devicePath.startsWith("usb://")) {
            val parts = session.devicePath.removePrefix("usb://").split('/')
            val deviceId = parts.firstOrNull()?.toIntOrNull()
            if (deviceId != null) {
                val app = com.bitlockerdroid.util.ContextProvider.app
                val usbManager = app?.getSystemService(android.hardware.usb.UsbManager::class.java)
                val deviceStillAttached = usbManager?.deviceList?.values?.any { it.deviceId == deviceId } == true
                if (!deviceStillAttached) {
                    Log.i(TAG, "isVolumeSessionActive: UsbDevice $deviceId physically disconnected")
                    closeSessionIfPresent(session.devicePath)
                    return false
                }
            }
        }

        return true
    }

    fun closeSessionIfPresent(devicePath: String) {
        val app = com.bitlockerdroid.util.ContextProvider.app
        // Remove under the lock, but close outside it: close() flushes and
        // does native IO, and holding `lock` during that stalls every
        // get()/isUnlocked() caller.
        val removed: DislockerCore? = synchronized(lock) {
            sessionCredentials.remove(devicePath)
            sessions.remove(devicePath)
        }
        removed?.close()
        if (removed != null) {
            app?.let {
                com.bitlockerdroid.share.LanShareManager.stopSharingForDevice(it, devicePath, removed.volumeGuid)
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
        val partInfo = com.bitlockerdroid.util.DeviceIdentity.resolvePartitionInfo(devicePath)

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
                val isRo = com.bitlockerdroid.util.PreferenceHelper.isVolumeReadOnly(com.bitlockerdroid.util.ContextProvider.app, effectiveGuid, devicePath)
                if (prev == null || prev.guid != effectiveGuid || prev.recoveryKeyId != effectiveRecoveryKeyId || prev.deviceName != devInfo.friendlyName || prev.isReadOnly != isRo || prev.partitionIndex != partInfo.partitionIndex || prev.partitionName != partInfo.partitionNodeName) {
                    detected[devicePath] = DetectedVolume(
                        devicePath = devicePath,
                        guid = effectiveGuid,
                        recoveryKeyId = effectiveRecoveryKeyId,
                        deviceName = devInfo.friendlyName,
                        capacity = devInfo.sizeBytes,
                        isReadOnly = isRo,
                        partitionIndex = partInfo.partitionIndex,
                        partitionName = partInfo.partitionNodeName
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

    /** Null [remember] preserves saved credentials; explicit false forgets them after a successful unlock. */
    fun unlockWithPassword(
        context: Context,
        devicePath: String,
        offset: Long,
        password: String,
        remember: Boolean?,
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
        remember: Boolean?,
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
        remember: Boolean?,
        expectedGuid: String? = null
    ): Result<DislockerCore> {
        return when (val admission = ProcessSessionTransition.gate.admit {
            synchronized(getParentDiskLock(com.bitlockerdroid.util.DeviceIdentity.resolveParentDiskNode(devicePath))) {
                unlockWithCredentialLocked(context, devicePath, offset, credential, isRecovery, remember, expectedGuid)
            }
        }) {
            is SessionTransitionGate.Admission.Accepted -> admission.value
            SessionTransitionGate.Admission.Rejected -> Result.failure(
                IllegalStateException("Session admission is closed for mode transition")
            )
        }
    }

    private fun unlockWithCredentialLocked(
        context: Context, devicePath: String, offset: Long, credential: String,
        isRecovery: Boolean, remember: Boolean?, expectedGuid: String?
    ): Result<DislockerCore> {
        return try {
            if (devicePath.startsWith("/dev/block/") && !expectedGuid.isNullOrBlank() &&
                !BitLockerDetector.getVolumeGuid(devicePath).equals(expectedGuid, ignoreCase = true)) {
                return Result.failure(SecurityException(context.getString(R.string.unlock_target_changed)))
            }
            // Duplicate notifications or unlock requests must not replace a live
            // filesystem/USB worker, which may still be serving file operations.
            val existing = synchronized(lock) { sessions[devicePath] }
            if (existing != null && !existing.isClosed) {
                if (closingSessions.contains(devicePath)) {
                    return Result.failure(IllegalStateException("Volume is being closed"))
                }
                if (existing.offset != offset || (!expectedGuid.isNullOrBlank() &&
                        !existing.volumeGuid.equals(expectedGuid, ignoreCase = true))) {
                    return Result.failure(SecurityException(context.getString(R.string.unlock_target_changed)))
                }
                // Reusing an unlocked volume does not validate the supplied key.
                // Keep its current credentials and remember preference intact.
                return Result.success(existing)
            }
            val openedCore = if (isRecovery) {
                DislockerCore.openWithRecoveryKey(devicePath, offset, credential)
            } else {
                DislockerCore.open(devicePath, offset, credential)
            }
            var guid: String? = null
            var stale: DislockerCore? = null
            val core = SessionAdmission.validateBeforePublish(
                candidate = openedCore,
                validate = { candidate ->
                    candidate.validateReadableFilesystem()
                    // The device node may have been reused while authentication was on screen.
                    if (!expectedGuid.isNullOrBlank() && !candidate.volumeGuid.equals(expectedGuid, ignoreCase = true)) {
                        throw SecurityException(context.getString(R.string.unlock_target_changed))
                    }
                    guid = candidate.volumeGuid ?: expectedGuid ?: BitLockerDetector.getVolumeGuid(devicePath)
                },
                publish = { candidate ->
                    synchronized(lock) {
                        if (!guid.isNullOrBlank()) manuallyLockedGuids.remove(guid)
                        manuallyLockedGuids.remove(devicePath)
                        // Replace any stale session: take it out under the lock,
                        // close outside so native teardown never runs under `lock`.
                        stale = sessions.put(devicePath, candidate)
                        detected.remove(devicePath)
                        if (!guid.isNullOrBlank()) {
                            val aliases = detected.entries.filter { it.value.guid.equals(guid, ignoreCase = true) }.map { it.key }
                            aliases.forEach { detected.remove(it) }
                        }
                    }
                }
            ).getOrThrow()
            stale?.close()
            val effectiveGuid = guid
            // Save session credentials in memory for the duration of this session
            sessionCredentials[devicePath] = Pair(credential, isRecovery)

            // Only an explicit persistence choice may save or forget a credential.
            // Saved-credential unlocks and provider calls without a choice preserve it.
            if (!effectiveGuid.isNullOrBlank()) {
                SavedCredentialPersistence.update(remember, save = {
                    val rawStored = if (isRecovery) "$RECOVERY_PREFIX$credential" else credential
                    KeyGuardService.encrypt(rawStored)?.let { blob ->
                        val friendly = core.volumeLabel.ifBlank {
                            com.bitlockerdroid.util.DeviceIdentity.queryDeviceInfo(devicePath, forceRefresh = true).friendlyName
                        }
                        PreferenceHelper.saveRememberedPassword(context, effectiveGuid, blob, friendlyName = friendly, isRecoveryKey = isRecovery)
                    }
                }, forget = {
                    PreferenceHelper.clearRememberedPassword(context, effectiveGuid)
                })
            } else {
                LogFile.write("app", "unlockWithCredential: no volume GUID found for $devicePath, skipping credential persistence")
            }

            // Trigger userspace FUSE virtual mount to /storage/<ID> if enabled for this volume
            val mountGuid = effectiveGuid ?: ""
            val effectiveLabel = core.volumeLabel.ifBlank {
                com.bitlockerdroid.util.DeviceIdentity.queryDeviceInfo(devicePath).friendlyName
            }
            val shouldVirtualMount = VirtualStorageMountManager.isSupported() &&
                PreferenceHelper.isVolumeVirtualMountEnabled(context, mountGuid, devicePath)
            if (shouldVirtualMount) {
                val resolvedBlock = VirtualStorageMountManager.resolveBlockDevice(devicePath, mountGuid)
                if (resolvedBlock != null) {
                    Thread {
                        VirtualStorageMountManager.mount(
                            context = context,
                            devicePath = resolvedBlock,
                            offset = offset,
                            key = credential,
                            isRecovery = isRecovery,
                            volumeLabel = effectiveLabel,
                            volumeGuid = mountGuid,
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
        return when (val admission = ProcessSessionTransition.gate.admit {
            if (closingSessions.contains(devicePath)) return@admit null
            synchronized(getParentDiskLock(com.bitlockerdroid.util.DeviceIdentity.resolveParentDiskNode(devicePath))) {
                if (closingSessions.contains(devicePath)) return@synchronized null
                val existing = synchronized(lock) { sessions[devicePath] }
                if (existing != null && !existing.isClosed &&
                    (guid.isNullOrBlank() || existing.volumeGuid.equals(guid, ignoreCase = true))) {
                    return@synchronized existing
                }
                val actualGuid = if (devicePath.startsWith("/dev/block/")) BitLockerDetector.getVolumeGuid(devicePath) else null
                if (devicePath.startsWith("/dev/block/") &&
                    (actualGuid.isNullOrBlank() || (!guid.isNullOrBlank() && !actualGuid.equals(guid, ignoreCase = true)))) {
                    return@synchronized null
                }
                val volId = actualGuid ?: guid ?: return@synchronized null
                val raw = SavedCredentialAccess.readForAutomaticUnlock(
                    context, volId, isManuallyLocked(volId, devicePath), closingSessions.contains(devicePath)
                ) ?: return@synchronized null
                // Settings can change while the Keystore operation is running.
                if (!SavedCredentialAccess.canAutomaticallyUnlock(
                        context, volId, isManuallyLocked(volId, devicePath), closingSessions.contains(devicePath)
                    )) return@synchronized null
                val isRecovery = raw.startsWith(RECOVERY_PREFIX)
                val cleanKey = if (isRecovery) raw.removePrefix(RECOVERY_PREFIX) else raw
                unlockWithCredentialLocked(context, devicePath, offset, cleanKey, isRecovery, null, volId).getOrNull()
            }
        }) {
            is SessionTransitionGate.Admission.Accepted -> admission.value
            SessionTransitionGate.Admission.Rejected -> null
        }
    }

    /**
     * Safely ejects an unlocked BitLocker volume:
     * 1. Freezes admissions and drains this session's SAF stream writes
     * 2. Stops sharing and waits for the FUSE daemon to exit without force-killing it
     * 3. Flushes and closes decrypted session and block device
     * 4. Multi-Partition Safe Physical Spindown & Offline:
     *    Checks remaining sessions AND sibling system partition mounts before spindown.
     * 5. Updates DocumentsProvider roots, foreground state, and posts safe-to-unplug notification
     */
    fun safeEject(devicePath: String): Result<EjectResult> =
        closeVolume(devicePath, stopPhysicalDisk = true, postEjectNotification = true)
            .map { it ?: throw IllegalStateException("Safe eject did not produce a result") }

    private fun closeVolume(
        devicePath: String,
        stopPhysicalDisk: Boolean,
        postEjectNotification: Boolean
    ): Result<EjectResult?> {
        val app = com.bitlockerdroid.util.ContextProvider.app
        val devInfo = com.bitlockerdroid.util.DeviceIdentity.queryDeviceInfo(devicePath, forceRefresh = true)
        val parentDisk = com.bitlockerdroid.util.DeviceIdentity.resolveParentDiskNode(devicePath)
        val diskLock = getParentDiskLock(parentDisk)

        synchronized(diskLock) {
            closingSessions.add(devicePath)
            var ejectingCore: DislockerCore? = null
            try {
                val stale: DislockerCore
                val effectiveLabel: String
                val closedVolume: DetectedVolume
                synchronized(lock) {
                    val core = sessions[devicePath]
                        ?: return Result.failure(java.io.IOException("Volume session not found"))
                    stale = core
                    ejectingCore = core
                    val guid = core.volumeGuid ?: BitLockerDetector.getVolumeGuid(devicePath)
                    val recoveryKeyId = core.recoveryKeyId ?: BitLockerDetector.getRecoveryKeyId(devicePath)
                    if (!guid.isNullOrBlank()) {
                        manuallyLockedGuids.add(guid)
                    }
                    manuallyLockedGuids.add(devicePath)
                    val fallbackLabel = app.getString(R.string.notification_drive_title)
                    effectiveLabel = core.volumeLabel.ifBlank { devInfo.friendlyName.ifBlank { fallbackLabel } }

                    LogFile.write("app", "UnlockManager.closeVolume: closing $devicePath ($effectiveLabel, guid=$guid, rkId=$recoveryKeyId)")

                    val isRo = com.bitlockerdroid.util.PreferenceHelper.isVolumeReadOnly(app, guid, devicePath)
                    val partInfo = com.bitlockerdroid.util.DeviceIdentity.resolvePartitionInfo(devicePath)
                    closedVolume = DetectedVolume(
                        devicePath = devicePath,
                        guid = guid,
                        recoveryKeyId = recoveryKeyId,
                        deviceName = devInfo.friendlyName,
                        capacity = devInfo.sizeBytes,
                        isReadOnly = isRo,
                        partitionIndex = partInfo.partitionIndex,
                        partitionName = partInfo.partitionNodeName
                    )
                }
                // Freeze admissions before waiting, without discarding the live session.
                // A timeout must leave the worker's native handles open.
                com.bitlockerdroid.provider.BitLockerDocumentsProvider.drainActiveWrites(stale)

                // Stop any active LAN sharing for this volume.
                try {
                    app?.let { appCtx ->
                        com.bitlockerdroid.share.LanShareManager.stopSharingForDevice(appCtx, devicePath, stale.volumeGuid)
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "LAN share stop warning for $devicePath", e)
                }

                // Unmount POSIX FUSE before closing the backing session.
                VirtualStorageMountManager.unmountChecked(devicePath)

                // Flush & close only after all writes really completed.
                stale.closeChecked()
                synchronized(lock) {
                    sessions.remove(devicePath)
                    detected[devicePath] = closedVolume
                }
                sessionCredentials.remove(devicePath)

                val outcome: EjectStatus? = if (!stopPhysicalDisk) {
                    null
                } else {
                    // Safe eject may stop/offline the physical disk only after checking
                    // sibling BitLocker sessions and system partitions.
                    val hasOtherBitLockerSessions = synchronized(lock) {
                        sessions.keys.any { otherPath ->
                            otherPath != devicePath && com.bitlockerdroid.util.DeviceIdentity.resolveParentDiskNode(otherPath) == parentDisk
                        }
                    } || closingSessions.any { otherPath ->
                        otherPath != devicePath && com.bitlockerdroid.util.DeviceIdentity.resolveParentDiskNode(otherPath) == parentDisk
                    }

                    val hasSystemPartitionsInUse = com.bitlockerdroid.util.DeviceIdentity.isParentDiskInUseBySystem(
                        parentDiskNode = parentDisk,
                        excludeDevicePaths = setOf(devicePath)
                    )

                    if (hasOtherBitLockerSessions || hasSystemPartitionsInUse) {
                        LogFile.write("app", "UnlockManager.safeEject: physical disk $parentDisk still in use (hasOtherBitLockerSessions=$hasOtherBitLockerSessions, hasSystemPartitionsInUse=$hasSystemPartitionsInUse), skipping hardware spindown and offlining")
                        EjectStatus.VOLUME_CLOSED_DISK_BUSY
                    } else {
                        LogFile.write("app", "UnlockManager.safeEject: no other active sessions or system partitions on physical disk $parentDisk, initiating hardware spindown and offline")
                        var spindownOk = false
                        try {
                            spindownOk = if (devicePath.startsWith("usb://")) {
                                com.bitlockerdroid.usb.UsbStorageManager.spindownDevice(devicePath)
                            } else if (com.bitlockerdroid.util.RootAccess.hasSu()) {
                                com.bitlockerdroid.util.RootAccess.spindownDevice(devicePath)
                            } else {
                                false
                            }
                        } catch (e: Throwable) {
                            Log.w(TAG, "Physical spindown warning for $devicePath ($parentDisk)", e)
                            spindownOk = false
                        }

                        if (spindownOk) {
                            com.bitlockerdroid.util.DeviceIdentity.invalidate(devicePath)
                            com.bitlockerdroid.util.DeviceIdentity.invalidate(parentDisk)
                            synchronized(lock) {
                                val toRemove = detected.keys.filter {
                                    it == devicePath || com.bitlockerdroid.util.DeviceIdentity.resolveParentDiskNode(it) == parentDisk
                                }
                                toRemove.forEach { detected.remove(it) }
                            }
                            EjectStatus.DISK_SPUN_DOWN
                        } else {
                            Log.w(TAG, "Physical spindown failed for $devicePath ($parentDisk)")
                            EjectStatus.VOLUME_CLOSED_SPINDOWN_FAILED
                        }
                    }
                }

                // Keep the physical device online for mode changes and do not emit a
                // safe-to-unplug message. Ordinary safe eject retains its old notice.
                app?.let {
                    dismissAllNotificationsForDevice(it, devicePath)
                    BitLockerCoreService.updateForegroundState(it)
                    com.bitlockerdroid.provider.BitLockerDocumentsProvider.notifyRootsChanged(it)
                    if (postEjectNotification && outcome != null) {
                        postSafeToUnplugNotification(it, devicePath, effectiveLabel, outcome)
                    }
                }
                notifyStateChanged()
                return Result.success(outcome?.let { EjectResult(effectiveLabel, it) })
            } catch (e: Exception) {
                Log.w(TAG, "Safe close aborted for $devicePath", e)
                if (ejectingCore?.isClosed == true) {
                    synchronized(lock) { sessions.remove(devicePath) }
                    sessionCredentials.remove(devicePath)
                    registerDetected(devicePath)
                }
                dismissEjectedNotification(app, devicePath)
                BitLockerCoreService.updateForegroundState(app)
                com.bitlockerdroid.provider.BitLockerDocumentsProvider.notifyRootsChanged(app)
                notifyStateChanged()
                return Result.failure(e)
            } finally {
                closingSessions.remove(devicePath)
                if (ejectingCore?.isClosed == false) ejectingCore?.pendingWrites?.resume()
            }
        }
    }

    fun lock(devicePath: String): Result<EjectResult> {
        return safeEject(devicePath)
    }

    /** Repairs unclean unmount dirty flags for the volume at [devicePath]. */
    fun repairDirtyVolume(devicePath: String): Result<String> {
        val core: DislockerCore
        synchronized(lock) {
            core = sessions[devicePath] ?: return Result.failure(IllegalStateException("Volume session not found"))
        }
        val res = core.repairDirty()
        if (res.isSuccess) {
            notifyStateChanged()
            com.bitlockerdroid.util.ContextProvider.app?.let { ctx ->
                com.bitlockerdroid.provider.BitLockerDocumentsProvider.notifyRootsChanged(ctx)
            }
        }
        return res
    }

    /** Performs non-destructive integrity diagnostics on filesystem metadata structures. */
    fun diagnoseVolume(devicePath: String): com.bitlockerdroid.ntfs.VolumeDiagnostic? {
        val core: DislockerCore
        synchronized(lock) {
            core = sessions[devicePath] ?: return null
        }
        return try {
            core.diagnose()
        } catch (e: Throwable) {
            Log.e(TAG, "diagnose failed for $devicePath", e)
            null
        }
    }

    /** Safely ejects all active unlocked BitLocker volumes. */
    fun safeEjectAll(): List<Result<EjectResult>> {
        val paths = synchronized(lock) { sessions.keys.toList() }
        val results = mutableListOf<Result<EjectResult>>()
        for (path in paths) {
            val result = safeEject(path)
            results.add(result)
            if (result.isFailure) break
        }
        if (results.all { it.isSuccess }) {
            sessionCredentials.clear()
            com.bitlockerdroid.util.ContextProvider.app?.let { app ->
                com.bitlockerdroid.share.LanShareManager.stopAll(app)
            }
        }
        return results
    }

    /** Already inside the transition gate: no new scan or worker can enter here. */
    private fun prepareUsbKernelHandoff(deviceId: Int) {
        com.bitlockerdroid.usb.UsbStorageManager.validateKernelHandoffTarget(deviceId)
        val paths = synchronized(lock) { sessions.keys.toList() }
        check(paths.all { it.startsWith("usb://$deviceId/") }) {
            "Another disk has open volume sessions"
        }
        paths.forEach {
            closeVolume(it, stopPhysicalDisk = false, postEjectNotification = false).getOrThrow()
        }
        check(synchronized(lock) { sessions.isEmpty() }) { "Volume sessions remain open" }
        com.bitlockerdroid.usb.UsbStorageManager.resetAndReleaseForKernelHandoff(deviceId)
    }

    /** Quiesces sessions and shared USB state before changing connection mode. */
    fun prepareForModeSwitch(targetRoot: Boolean): Result<Unit> = try {
        ProcessSessionTransition.gate.prepare {
            val handoffTarget = if (!PreferenceHelper.useRootAccess && targetRoot) {
                com.bitlockerdroid.usb.UsbStorageManager.kernelHandoffResetTarget()
            } else null
            if (handoffTarget != null) {
                prepareUsbKernelHandoff(handoffTarget)
            } else {
                safeEjectAll().firstOrNull { it.isFailure }?.getOrThrow()
            }
            VirtualStorageMountManager.unmountAll()
            Unit
        }
        Result.success(Unit)
    } catch (e: Exception) {
        Log.w(TAG, "Mode switch preparation failed", e)
        Result.failure(e)
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
            com.bitlockerdroid.util.ContextProvider.app?.let { app ->
                com.bitlockerdroid.share.LanShareManager.stopSharingForDevice(app, path, core.volumeGuid)
            }
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
    fun onUsbDetached(allStorageGone: Boolean = true) {
        LogFile.write("app", "UnlockManager.onUsbDetached(allStorageGone=$allStorageGone)")
        val app = com.bitlockerdroid.util.ContextProvider.app
        if (allStorageGone) {
            val toClose: List<Pair<String, DislockerCore>>
            val hadEntries: Boolean
            synchronized(lock) {
                hadEntries = sessions.isNotEmpty() || detected.isNotEmpty() || unencrypted.isNotEmpty()
                toClose = sessions.toList()
                sessions.clear()
                sessionCredentials.clear()
                detected.clear()
                unencrypted.clear()
                manuallyLockedGuids.clear()
            }
            if (hadEntries) {
                notifyStateChanged()
            }
            Thread({
                for ((path, core) in toClose) {
                    try {
                        core.close()
                    } catch (e: Throwable) {
                        Log.w(TAG, "core.close failed for $path", e)
                    }
                }
                try {
                    VirtualStorageMountManager.unmountAll()
                } catch (e: Throwable) {
                    Log.w(TAG, "VirtualStorageMountManager.unmountAll failed", e)
                }
                com.bitlockerdroid.util.DeviceIdentity.clearCache()
                StorageNotificationSuppressor.forgetMissing(emptyList())
                app?.let { ctx ->
                    com.bitlockerdroid.share.LanShareManager.stopAll(ctx)
                    val nm = ctx.getSystemService(NotificationManager::class.java)
                    nm?.cancelAll()
                    BitLockerCoreService.updateForegroundState(ctx)
                    com.bitlockerdroid.provider.BitLockerDocumentsProvider.notifyRootsChanged(ctx)
                }
            }, "BitLocker-UsbDetachCleanup").start()
        } else {
            Thread({
                try {
                    val nodes = BitLockerDetector.enumerateVoldNodes()
                    forgetDetectedMissing(nodes)
                } catch (e: Exception) {
                    Log.w(TAG, "forgetDetectedMissing error during onUsbDetached", e)
                }
                app?.let { ctx ->
                    val hasActiveSessions = synchronized(lock) { sessions.isNotEmpty() }
                    if (!hasActiveSessions) {
                        com.bitlockerdroid.share.LanShareManager.stopAll(ctx)
                    }
                    BitLockerCoreService.updateForegroundState(ctx)
                }
            }, "BitLocker-UsbDetachCleanup").start()
        }
    }

    /** Registers an already-opened core session (recovery-key path). */
    fun registerDirect(
        core: DislockerCore,
        context: Context?,
        key: String? = null,
        isRecovery: Boolean = false
    ) {
        when (ProcessSessionTransition.gate.admit {
            registerDirectAdmitted(core, context, key, isRecovery)
        }) {
            is SessionTransitionGate.Admission.Accepted -> Unit
            SessionTransitionGate.Admission.Rejected -> {
                try {
                    core.close()
                } catch (closeFailure: Throwable) {
                    LogFile.write("app", "registerDirect: failed to close rejected session ${core.devicePath}: ${closeFailure.message}")
                }
            }
        }
    }

    private fun registerDirectAdmitted(
        core: DislockerCore,
        context: Context?,
        key: String?,
        isRecovery: Boolean
    ) {
        var stale: DislockerCore? = null
        val admission = SessionAdmission.validateBeforePublish(
            candidate = core,
            validate = { it.validateReadableFilesystem() },
            publish = { candidate ->
                synchronized(lock) {
                    val guid = candidate.volumeGuid ?: BitLockerDetector.getVolumeGuid(candidate.devicePath)
                    if (!guid.isNullOrBlank()) manuallyLockedGuids.remove(guid)
                    manuallyLockedGuids.remove(candidate.devicePath)
                    stale = sessions.put(candidate.devicePath, candidate)
                    detected.remove(candidate.devicePath)
                }
            }
        )
        val admittedCore = admission.getOrElse { e ->
            LogFile.write("app", "registerDirect: refusing unreadable session ${core.devicePath}: ${e.message}")
            return
        }
        stale?.close()

        if (key != null && context != null &&
            VirtualStorageMountManager.isSupported() &&
            PreferenceHelper.isVolumeVirtualMountEnabled(context, admittedCore.volumeGuid, admittedCore.devicePath)
        ) {
            val guid = admittedCore.volumeGuid ?: ""
            val effectiveLabel = admittedCore.volumeLabel.ifBlank {
                com.bitlockerdroid.util.DeviceIdentity.queryDeviceInfo(admittedCore.devicePath).friendlyName
            }
            val resolvedBlock = VirtualStorageMountManager.resolveBlockDevice(admittedCore.devicePath, guid)
            if (resolvedBlock != null) {
                Thread {
                    VirtualStorageMountManager.mount(
                        context = context,
                        devicePath = resolvedBlock,
                        offset = admittedCore.offset,
                        key = key,
                        isRecovery = isRecovery,
                        volumeLabel = effectiveLabel,
                        volumeGuid = guid,
                        originalPath = admittedCore.devicePath
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
    private val autoUnlockExecutor = java.util.concurrent.Executors.newFixedThreadPool(2, object : java.util.concurrent.ThreadFactory {
        private val count = java.util.concurrent.atomic.AtomicInteger(1)
        override fun newThread(r: Runnable): Thread {
            return Thread(r, "BitLocker-AutoUnlock-${count.getAndIncrement()}").apply {
                priority = Thread.NORM_PRIORITY
            }
        }
    })

    @Volatile
    private var lastReadyVibrateTime = 0L

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

        if (!SavedCredentialAccess.canAutomaticallyUnlock(
                context, volumeId, isManuallyLocked(volumeId, devicePath), closingSessions.contains(devicePath)
            )) {
            LogFile.write("app", "onDeviceDetected: automatic credential access denied for $volumeId")
            notifyVolumeLocked(context, devicePath, offset, volumeId, rkId)
            return
        }

        if (PreferenceHelper.getRememberedPassword(context, volumeId) != null) {
            val lockKey = volumeId
            if (!inProgressUnlocks.add(lockKey)) {
                LogFile.write("app", "onDeviceDetected: unlock already in progress for $lockKey")
                return
            }
            unlockingLatch.incrementAndGet()
            autoUnlockExecutor.execute {
                try {
                    // Check authorization inside the queued task, not just when it was enqueued.
                    val core = ensureUnlocked(context, devicePath, offset, volumeId)
                    if (core == null) {
                        LogFile.write("app", "auto-unlock denied or failed for $devicePath (guid=$volumeId)")
                        notifyVolumeLocked(context, devicePath, offset, volumeId, rkId)
                    } else {
                        LogFile.write("app", "auto-unlock succeeded for $devicePath (guid=$volumeId)")
                        notifyVolumeReady(context, devicePath, core, isAutoUnlock = true)
                    }
                } finally {
                    inProgressUnlocks.remove(lockKey)
                    unlockingLatch.decrementAndGet()
                    synchronized(unlockWaitLock) {
                        (unlockWaitLock as java.lang.Object).notifyAll()
                    }
                }
            }
            return
        }

        // No saved credential or auto-unlock disabled: post high-priority unlock prompt notification
        notifyVolumeLocked(context, devicePath, offset, volumeId, rkId)
    }

    fun notifyVolumeReady(context: Context, devicePath: String, core: DislockerCore, isAutoUnlock: Boolean = false) {
        if (isAutoUnlock) {
            val now = System.currentTimeMillis()
            val shouldVibrate = synchronized(lock) {
                if (now - lastReadyVibrateTime > 2000L) {
                    lastReadyVibrateTime = now
                    true
                } else {
                    false
                }
            }

            if (shouldVibrate && PreferenceHelper.isOtgFeedbackVibration(context)) {
                triggerHapticFeedback(context)
            }
        }
    }

    private fun triggerHapticFeedback(context: Context) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                val vm = context.getSystemService(android.os.VibratorManager::class.java)
                val vibrator = vm?.defaultVibrator
                vibrator?.vibrate(android.os.VibrationEffect.createPredefined(android.os.VibrationEffect.EFFECT_CLICK))
            } else if (android.os.Build.VERSION.SDK_INT >= 26) {
                @Suppress("DEPRECATION")
                val v = context.getSystemService(android.os.Vibrator::class.java)
                v?.vibrate(android.os.VibrationEffect.createOneShot(40, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
            }
        } catch (_: Throwable) {}
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

    fun postSafeToUnplugNotification(
        context: Context,
        devicePath: String,
        label: String,
        status: EjectStatus = EjectStatus.DISK_SPUN_DOWN
    ) {
        if (!PreferenceHelper.isNotificationsEnabled(context)) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        ensureChannels(context)

        val (title, text) = when (status) {
            EjectStatus.DISK_SPUN_DOWN -> {
                context.getString(R.string.safe_to_unplug_title, label) to
                    context.getString(R.string.safe_to_unplug_desc)
            }
            EjectStatus.VOLUME_CLOSED_DISK_BUSY -> {
                context.getString(R.string.volume_closed_title, label) to
                    context.getString(R.string.volume_closed_disk_busy_desc)
            }
            EjectStatus.VOLUME_CLOSED_SPINDOWN_FAILED -> {
                context.getString(R.string.volume_closed_title, label) to
                    context.getString(R.string.volume_closed_spindown_failed_desc)
            }
        }

        val notif = NotificationCompat.Builder(context, CHANNEL_ID_ALERTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
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

enum class EjectStatus {
    DISK_SPUN_DOWN,
    VOLUME_CLOSED_DISK_BUSY,
    VOLUME_CLOSED_SPINDOWN_FAILED
}

@androidx.compose.runtime.Immutable
data class EjectResult(
    val label: String,
    val status: EjectStatus,
    val message: String? = null
) {
    override fun toString(): String = label
}

@androidx.compose.runtime.Immutable
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
    val isDirty: Boolean = false,
    val partitionIndex: Int = 0,
    val partitionName: String = ""
)

/** A BitLocker volume detected on the bus but not yet unlocked. */
@androidx.compose.runtime.Immutable
data class DetectedVolume(
    val devicePath: String,
    val guid: String? = null,
    val recoveryKeyId: String? = null,
    val deviceName: String = "",
    val capacity: Long = 0L,
    val isReadOnly: Boolean = false,
    val partitionIndex: Int = 0,
    val partitionName: String = ""
)

/** An unencrypted storage volume (e.g. FAT32, exFAT) managed natively by Android OS. */
@androidx.compose.runtime.Immutable
data class UnencryptedVolume(
    val id: String,
    val label: String,
    val fsType: String = "",
    val mountPath: String = "",
    val totalBytes: Long = 0L,
    val freeBytes: Long = 0L,
    val uuid: String? = null,
    val blockDevice: String? = null,
    val partitionIndex: Int = 0,
    val partitionName: String = ""
)
