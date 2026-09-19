package com.bitlockerdroid.service

import android.util.Log
import com.bitlockerdroid.ntfs.ExFatReader
import com.bitlockerdroid.ntfs.ExFatVolume
import com.bitlockerdroid.ntfs.Fat32Reader
import com.bitlockerdroid.ntfs.Fat32Volume
import com.bitlockerdroid.ntfs.NtfsBlockSource
import com.bitlockerdroid.ntfs.NtfsReader
import com.bitlockerdroid.ntfs.NtfsVolume
import com.bitlockerdroid.ntfs.NtfsVolume.BootSector
import com.bitlockerdroid.ntfs.VolumeReader
import com.bitlockerdroid.util.LogFile
import com.bitlockerdroid.util.NativeBridge
import com.bitlockerdroid.util.RootAccess
import java.util.concurrent.atomic.AtomicInteger

/**
 * Manages a decrypted BitLocker session: native handle, NTFS/FAT32 reader, and
 * the block source that decrypts on demand. Owns the lifecycle of a mounted volume.
 */
class DislockerCore private constructor(
    val devicePath: String,
    val offset: Long,
    val handle: Long,
    val info: NativeBridge.SessionInfo,
    val isRecovery: Boolean = false,
    val usbSession: com.bitlockerdroid.usb.UsbStorageManager.UsbSession? = null
) : AutoCloseable {

    val reader: VolumeReader by lazy { buildReader() }
    val writer: com.bitlockerdroid.ntfs.VolumeWriter? by lazy {
        val app = try { com.bitlockerdroid.util.ContextProvider.app } catch (_: Throwable) { null }
        val isRo = if (app != null) {
            com.bitlockerdroid.util.PreferenceHelper.isVolumeReadOnly(app, volumeGuid, devicePath)
        } else false
        when (reader) {
            is NtfsReader -> {
                try {
                    com.bitlockerdroid.ntfs.NtfsWriter(handle, isRo)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to initialize NtfsWriter", e)
                    null
                }
            }
            is ExFatReader, is Fat32Reader -> {
                try {
                    com.bitlockerdroid.ntfs.FatFsWriter(handle, isRo)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to initialize FatFsWriter", e)
                    null
                }
            }
            else -> null
        }
    }

    val ntfsWriter: com.bitlockerdroid.ntfs.NtfsWriter?
        get() = writer as? com.bitlockerdroid.ntfs.NtfsWriter

    private val pathCache = java.util.concurrent.ConcurrentHashMap<Long, String>()
    private val pathToRecord = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val parentCache = java.util.concurrent.ConcurrentHashMap<Long, Long>()
    private val createdEntries = java.util.concurrent.ConcurrentHashMap<Long, com.bitlockerdroid.ntfs.VolumeEntry>()

    private val recordAliases = java.util.concurrent.ConcurrentHashMap<Long, Long>()

    fun setRecordAlias(oldRecord: Long, newRecord: Long) {
        if (oldRecord != newRecord) {
            // Unidirectional alias: oldRecord -> newRecord
            // Break any reverse link to prevent circular aliases
            if (recordAliases[newRecord] == oldRecord) {
                recordAliases.remove(newRecord)
            }
            recordAliases[oldRecord] = newRecord
            pathCache[oldRecord]?.let {
                pathCache[newRecord] = it
                pathToRecord[it] = newRecord
            }
            parentCache[oldRecord]?.let {
                parentCache[newRecord] = it
            }
        }
    }

    fun resolveRecord(record: Long): Long {
        var cur = record
        var depth = 0
        val visited = java.util.HashSet<Long>()
        visited.add(cur)
        while (depth++ < 10) {
            val next = recordAliases[cur] ?: break
            if (!visited.add(next)) break
            cur = next
        }
        return cur
    }

    fun registerPath(record: Long, path: String, parentRecord: Long? = null) {
        val effRecord = resolveRecord(record)
        pathCache[record] = path
        pathCache[effRecord] = path
        pathToRecord[path] = effRecord
        if (parentRecord != null) {
            parentCache[record] = parentRecord
            parentCache[effRecord] = parentRecord
        }
    }

    fun updatePathAfterRename(oldPath: String, newPath: String, record: Long, parentRecord: Long? = null) {
        val effRecord = resolveRecord(record)
        pathToRecord.remove(oldPath)

        for ((r, p) in pathCache.entries.toList()) {
            if (p == oldPath || r == record || r == effRecord || resolveRecord(r) == effRecord) {
                pathCache[r] = newPath
                if (parentRecord != null) {
                    parentCache[r] = parentRecord
                }
            }
        }
        pathCache[record] = newPath
        pathCache[effRecord] = newPath
        pathToRecord[newPath] = effRecord
        if (parentRecord != null) {
            parentCache[record] = parentRecord
            parentCache[effRecord] = parentRecord
        }
    }

    fun getRecordForPath(path: String): Long? = pathToRecord[path]

    fun removePath(record: Long) {
        val eff = resolveRecord(record)
        val path = pathCache.remove(record) ?: pathCache.remove(eff)
        if (path != null) {
            pathToRecord.remove(path)
            for ((r, p) in pathCache.entries.toList()) {
                if (p == path || r == record || r == eff) {
                    pathCache.remove(r)
                    parentCache.remove(r)
                }
            }
        }
        parentCache.remove(record)
        parentCache.remove(eff)
        createdEntries.remove(record)
        createdEntries.remove(eff)
        recordAliases.remove(record)
    }

    fun registerCreatedEntry(entry: com.bitlockerdroid.ntfs.VolumeEntry) {
        createdEntries[entry.ref] = entry
    }

    fun removeCreatedEntry(record: Long) {
        val eff = resolveRecord(record)
        createdEntries.remove(record)
        createdEntries.remove(eff)
    }

    fun getCreatedEntry(record: Long): com.bitlockerdroid.ntfs.VolumeEntry? =
        createdEntries[resolveRecord(record)] ?: createdEntries[record]

    private fun findPathRecursive(dirRef: Long, dirPath: String, targetRecord: Long, depth: Int = 0): String? {
        if (depth > 16) return null
        val entries = try { reader.listDirectory(dirRef) } catch (_: Exception) { return null }
        val effTarget = resolveRecord(targetRecord)
        for (e in entries) {
            val childPath = if (dirPath == "/") "/${e.name}" else "$dirPath/${e.name}"
            registerPath(e.ref, childPath, dirRef)
            if (e.ref == targetRecord || e.ref == effTarget || resolveRecord(e.ref) == effTarget) {
                return childPath
            }
            if (e.isDirectory && e.ref != dirRef) {
                val found = findPathRecursive(e.ref, childPath, targetRecord, depth + 1)
                if (found != null) return found
            }
        }
        return null
    }

    fun resolvePath(record: Long): String? {
        val effRecord = resolveRecord(record)
        if (effRecord == reader.rootRef) return "/"
        pathCache[effRecord]?.let { return it }
        pathCache[record]?.let { return it }
        (reader as? NtfsReader)?.resolvePath(effRecord)?.let {
            pathCache[effRecord] = it
            pathToRecord[it] = effRecord
            return it
        }
        // Fallback: search directory tree starting from root
        val found = findPathRecursive(reader.rootRef, "/", effRecord)
            ?: findPathRecursive(reader.rootRef, "/", record)
        if (found != null) {
            pathCache[effRecord] = found
            pathCache[record] = found
            pathToRecord[found] = effRecord
            return found
        }
        return null
    }

    fun parentOf(record: Long): Long {
        val effRecord = resolveRecord(record)
        if (effRecord == reader.rootRef) return reader.rootRef
        parentCache[effRecord]?.let { return it }
        parentCache[record]?.let { return it }
        val path = resolvePath(record)
        if (path != null && path != "/") {
            val parentPath = path.substringBeforeLast('/').ifEmpty { "/" }
            pathToRecord[parentPath]?.let { return it }
            if (parentPath == "/") return reader.rootRef
        }
        (reader as? NtfsReader)?.readFileRecord(effRecord)?.parentRecord?.let { return it }
        return reader.rootRef
    }

    /**
     * Resolves metadata for [record], with resilient fallbacks for cases where
     * cache was invalidated or cluster reallocated during a file write.
     */
    fun getEntry(record: Long): com.bitlockerdroid.ntfs.VolumeEntry? {
        val effRecord = resolveRecord(record)
        if (effRecord == reader.rootRef) {
            return com.bitlockerdroid.ntfs.VolumeEntry(record, isDirectory = true, fileName = volumeLabel, fileSize = 0L)
        }

        // 1. Direct read from reader cache
        val direct = try { reader.readEntry(effRecord) } catch (_: Exception) { null }
            ?: try { reader.readEntry(record) } catch (_: Exception) { null }
        if (direct != null) {
            return if (effRecord != record) {
                com.bitlockerdroid.ntfs.VolumeEntry(
                    ref = record,
                    isDirectory = direct.isDirectory,
                    fileName = direct.fileName,
                    fileSize = direct.fileSize,
                    lastModified = direct.lastModified
                )
            } else direct
        }

        // 2. Resolve path to locate entry in parent directory directly from disk
        val path = resolvePath(record)
        if (path != null && path != "/") {
            val parentRecord = parentOf(record)
            val fileName = path.substringAfterLast('/')
            try {
                val dirEntries = reader.listDirectory(parentRecord)
                val isDir = reader.readEntry(effRecord)?.isDirectory
                    ?: reader.readEntry(record)?.isDirectory
                    ?: createdEntries[effRecord]?.isDirectory
                    ?: createdEntries[record]?.isDirectory
                val found = dirEntries.find {
                    (it.name.trim().equals(fileName.trim(), ignoreCase = true) ||
                    it.name.equals(fileName, ignoreCase = true)) &&
                    (isDir == null || it.isDirectory == isDir)
                }
                if (found != null) {
                    if (found.ref != record) {
                        setRecordAlias(record, found.ref)
                        setRecordAlias(effRecord, found.ref)
                        registerPath(found.ref, path, parentRecord)
                    }
                    registerPath(record, path, parentRecord)
                    return com.bitlockerdroid.ntfs.VolumeEntry(
                        ref = record,
                        isDirectory = found.isDirectory,
                        fileName = found.name,
                        fileSize = found.size,
                        lastModified = found.lastModified
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "getEntry fallback failed for path=$path", e)
            }
        }

        // 3. Check created entries in memory (for newly created files not yet reloaded)
        createdEntries[effRecord]?.let {
            return if (effRecord != record) {
                com.bitlockerdroid.ntfs.VolumeEntry(
                    ref = record,
                    isDirectory = it.isDirectory,
                    fileName = it.fileName,
                    fileSize = it.fileSize,
                    lastModified = it.lastModified
                )
            } else it
        }
        createdEntries[record]?.let { return it }

        return null
    }

    /**
     * Resilient file read that handles cluster chain reallocation after write.
     */
    fun readFile(record: Long, offset: Long, dst: ByteArray, dstPos: Int, len: Int): Int {
        val effRecord = resolveRecord(record)
        val n = try { reader.readFile(effRecord, offset, dst, dstPos, len) } catch (_: Exception) { 0 }
        if (n > 0) return n

        // Fallback if record cluster changed after write/truncate:
        val path = resolvePath(record)
        if (path != null && path != "/") {
            val parentRecord = parentOf(record)
            val fileName = path.substringAfterLast('/')
            val dirEntries = try { reader.listDirectory(parentRecord) } catch (_: Exception) { emptyList() }
            val found = dirEntries.find { it.name.equals(fileName, ignoreCase = true) && !it.isDirectory }
            if (found != null && found.ref != effRecord) {
                setRecordAlias(record, found.ref)
                registerPath(found.ref, path, parentRecord)
                return reader.readFile(found.ref, offset, dst, dstPos, len)
            }
        }
        return 0
    }

    val volumeLabel: String by lazy {
        val label = try { reader.volumeLabel() } catch (e: Exception) { null }
        if (!label.isNullOrBlank()) label else "BitLocker ${info.algorithmName}"
    }

    val volumeGuid: String? by lazy {
        try { NativeBridge.nativeGetVolumeGuid(handle) } catch (e: Exception) { null }
    }

    val recoveryKeyId: String? by lazy {
        try { NativeBridge.nativeGetRecoveryKeyId(handle) } catch (e: Exception) { null }
    }

    val isDirty: Boolean
        get() = try { reader.isDirty } catch (e: Exception) { false }

    /** Performs non-destructive integrity diagnostics on filesystem metadata structures. */
    fun diagnose(): com.bitlockerdroid.ntfs.VolumeDiagnostic = reader.diagnose()

    /** Returns Pair(totalBytes, freeBytes) queried from native filesystem, or null if unavailable. */
    fun getSpaceInfo(): Pair<Long, Long>? {
        try {
            val sp = writer?.getSpace()
            if (sp != null && sp.first > 0L) {
                return sp
            }
        } catch (e: Throwable) {
            Log.w(TAG, "getSpaceInfo failed", e)
        }
        return null
    }

    @Volatile
    private var closed = false

    private val rawSectorReader: (Long, Int) -> ByteArray? = { offset, len ->
        if (offset >= info.volumeSize) null
        else {
            val bounded = minOf(len.toLong(), info.volumeSize - offset).toInt()
            val sectorSize = if (info.sectorSize > 0) info.sectorSize else 512
            val alignedBounded = ((bounded + sectorSize - 1) / sectorSize) * sectorSize
            val actualToRead = minOf(alignedBounded.toLong(), info.volumeSize - offset).toInt()
            val finalBounded = ((actualToRead + sectorSize - 1) / sectorSize) * sectorSize

            NativeBridge.nativeRead(handle, offset, finalBounded)
        }
    }

    private val cachedSource = com.bitlockerdroid.ntfs.CachedBlockSource(
        volumeSize = info.volumeSize,
        sectorSize = if (info.sectorSize > 0) info.sectorSize else 512,
        rawSectorReader = rawSectorReader
    )

    val blockSource: NtfsBlockSource get() = cachedSource

    /** Invalidate all block and filesystem caches after a write/delete/rename operation */
    fun invalidateCache() {
        cachedSource.clear()
        createdEntries.clear()
        reader.invalidateCache()
        // SAF mutations must become visible to the FUSE daemon's own FatFs /
        // ntfs-3g caches (cross-session cache coherency).
        VirtualStorageMountManager.notifyDataChanged(devicePath)
    }

    private fun buildReader(): VolumeReader {
        // Read the decrypted boot sector. Logical/physical offsets are
        // absolute; the NTFS/FAT32 volume boot sector is at data_offset.
        val bootBytes = ByteArray(512)
        val n = blockSource.read(info.dataOffset, bootBytes, 0, 512)
        // Diagnostic: dump the decrypted boot sector head.
        val hex = bootBytes.take(32).joinToString("") { "%02x".format(it) }
        Log.w(TAG, "boot sector n=$n dataOffset=${info.dataOffset} hex=${hex.take(64)}")
        if (n < 512) {
            throw IllegalStateException("Cannot read boot sector (n=$n)")
        }

        // Branch on the OEM / FS signature.
        val oem = String(bootBytes, 3, 8, Charsets.US_ASCII)
        if (oem == "NTFS    ") {
            val boot = NtfsVolume.parseBootSector(bootBytes)
                ?: throw IllegalStateException("Invalid NTFS boot sector")
            return NtfsReader(blockSource, boot)
        }

        if (oem == "EXFAT   ") {
            val exBoot = ExFatVolume.parseBootSector(bootBytes)
                ?: throw IllegalStateException("Invalid exFAT boot sector")
            Log.i(TAG, "volume is exFAT (oem=$oem)")
            return ExFatReader(blockSource, exBoot, bootBytes, info.dataOffset)
        }

        val fatBoot = Fat32Volume.parseBootSector(bootBytes)
        if (fatBoot != null) {
            Log.i(TAG, "volume is FAT32 (oem=$oem)")
            return Fat32Reader(blockSource, fatBoot, bootBytes, info.dataOffset)
        }

        throw IllegalStateException(
            "Decrypted volume is neither NTFS nor FAT32 nor exFAT (oem=$oem)"
        )
    }

    /**
     * Flushes the encrypted block device (fdatasync / daemon CMD_SYNC).
     * Safe-eject step: call after all write pipelines have drained and before
     * [close] so no decrypted-and-re-encrypted sector is left in flight.
     */
    fun flush() {
        try {
            NativeBridge.nativeSync(handle)
        } catch (e: Throwable) {
            Log.w(TAG, "flush failed", e)
        }
    }

    override fun close() {
        // Serialize double-close: two threads racing here would flush the
        // writer twice and call nativeClose on an already-freed handle.
        synchronized(this) {
            if (closed) return
            closed = true
            try {
                writer?.close()
            } catch (e: Throwable) {
                Log.w(TAG, "writer close failed", e)
            }
            try {
                flush()
            } catch (e: Throwable) {
                Log.w(TAG, "flush during close failed", e)
            }
            cachedSource.clear()
            createdEntries.clear()
            pathCache.clear()
            parentCache.clear()
            try {
                NativeBridge.nativeClose(handle)
            } catch (e: Throwable) {
                Log.w(TAG, "close failed", e)
            }
            try {
                usbSession?.close()
            } catch (e: Throwable) {
                Log.w(TAG, "usbSession close failed", e)
            }
        }
    }

    /**
     * Maps a filesystem-relative logical byte offset to the physical offset on the
     * encrypted block device, accounting for the BitLocker header redirection of the
     * boot sector area (first 8192 bytes / nb_backup_sectors).
     */
    private fun toPhysicalOffset(logicalOffset: Long): Long {
        val backupBytes = 8192L
        return if (info.dataOffset > 0 && logicalOffset < backupBytes) {
            info.dataOffset + logicalOffset
        } else {
            logicalOffset
        }
    }

    /**
     * Checks and clears filesystem dirty flags / unclean unmount markers, restoring
     * the volume to a clean state.
     */
    fun repairDirty(): Result<String> {
        val fsName = when (reader) {
            is com.bitlockerdroid.ntfs.NtfsReader -> "NTFS"
            is com.bitlockerdroid.ntfs.ExFatReader -> "exFAT"
            is com.bitlockerdroid.ntfs.Fat32Reader -> "FAT32"
            else -> "Unknown"
        }
        Log.i(TAG, "Starting repairDirty for $devicePath ($fsName)")
        try {
            when (reader) {
                is com.bitlockerdroid.ntfs.NtfsReader -> {
                    val w = writer as? com.bitlockerdroid.ntfs.NtfsWriter
                    var success = false
                    if (w != null && w.isMounted) {
                        success = w.repairDirty()
                    }
                    if (!success) {
                        val tempWriter = com.bitlockerdroid.ntfs.NtfsWriter(handle, readOnly = false)
                        try {
                            if (tempWriter.isMounted) {
                                success = tempWriter.repairDirty()
                            }
                        } finally {
                            tempWriter.close()
                        }
                    }
                    if (!success) {
                        return Result.failure(Exception("NTFS dirty bit repair failed in ntfs-3g"))
                    }
                }
                is com.bitlockerdroid.ntfs.ExFatReader -> {
                    val sectorSize = if (info.sectorSize > 0) info.sectorSize else 512
                    val sec0Offset = toPhysicalOffset(0L)
                    val sec0Raw = NativeBridge.nativeRead(handle, sec0Offset, sectorSize)
                        ?: return Result.failure(Exception("Cannot read exFAT main boot sector"))
                    val sec0 = sec0Raw.copyOf(sectorSize)
                    val flags0 = (sec0[0x6A].toInt() and 0xff) or ((sec0[0x6B].toInt() and 0xff) shl 8)
                    val cleanFlags0 = flags0 and 0x0006.inv()
                    sec0[0x6A] = (cleanFlags0 and 0xff).toByte()
                    sec0[0x6B] = ((cleanFlags0 shr 8) and 0xff).toByte()
                    val w0 = NativeBridge.nativeWrite(handle, sec0Offset, sec0, sectorSize)
                    if (w0 < 0) {
                        return Result.failure(Exception("Cannot write exFAT main boot sector"))
                    }

                    // Update backup boot sector (sector 12)
                    val backupOffset = toPhysicalOffset(12L * sectorSize)
                    val sec12Raw = NativeBridge.nativeRead(handle, backupOffset, sectorSize)
                    if (sec12Raw != null && sec12Raw.size >= sectorSize) {
                        val sec12 = sec12Raw.copyOf(sectorSize)
                        val flags12 = (sec12[0x6A].toInt() and 0xff) or ((sec12[0x6B].toInt() and 0xff) shl 8)
                        val cleanFlags12 = flags12 and 0x0006.inv()
                        sec12[0x6A] = (cleanFlags12 and 0xff).toByte()
                        sec12[0x6B] = ((cleanFlags12 shr 8) and 0xff).toByte()
                        NativeBridge.nativeWrite(handle, backupOffset, sec12, sectorSize)
                    }
                    (reader as com.bitlockerdroid.ntfs.ExFatReader).updateBootFlags(cleanFlags0)
                }
                is com.bitlockerdroid.ntfs.Fat32Reader -> {
                    val boot = (reader as com.bitlockerdroid.ntfs.Fat32Reader).boot
                    val sectorSize = boot.bytesPerSector

                    // 1. Clear Windows fastfat dirty bit in Sector 0 (offset 0x41 / decimal 65)
                    val sec0Offset = toPhysicalOffset(0L)
                    val sec0Raw = NativeBridge.nativeRead(handle, sec0Offset, sectorSize)
                        ?: return Result.failure(Exception("Cannot read FAT32 main boot sector"))
                    val sec0 = sec0Raw.copyOf(sectorSize)
                    sec0[0x41] = 0.toByte()
                    val w0 = NativeBridge.nativeWrite(handle, sec0Offset, sec0, sectorSize)
                    if (w0 < 0) {
                        return Result.failure(Exception("Cannot write FAT32 main boot sector"))
                    }

                    // 2. Clear backup boot sector (usually sector 6)
                    if (boot.backupBootSector in 1 until boot.reservedSectors) {
                        val bkOffset = toPhysicalOffset(boot.backupBootSector.toLong() * sectorSize)
                        val secBkRaw = NativeBridge.nativeRead(handle, bkOffset, sectorSize)
                        if (secBkRaw != null && secBkRaw.size >= sectorSize) {
                            val secBk = secBkRaw.copyOf(sectorSize)
                            secBk[0x41] = 0.toByte()
                            NativeBridge.nativeWrite(handle, bkOffset, secBk, sectorSize)
                        }
                    }

                    // 3. Clear FAT[1] dirty bits (Bit 31: clean shutdown, Bit 30: hard error)
                    val fat1Offset = toPhysicalOffset(boot.fatStartByte)
                    val secFat1Raw = NativeBridge.nativeRead(handle, fat1Offset, sectorSize)
                        ?: return Result.failure(Exception("Cannot read FAT32 FAT1"))
                    if (secFat1Raw.size >= 8) {
                        val secFat1 = secFat1Raw.copyOf(sectorSize)
                        val fat1 = com.bitlockerdroid.ntfs.NtfsVolume.le32(secFat1, 4)
                        val cleanFat1 = fat1 or 0xC0000000L
                        secFat1[4] = (cleanFat1 and 0xff).toByte()
                        secFat1[5] = ((cleanFat1 shr 8) and 0xff).toByte()
                        secFat1[6] = ((cleanFat1 shr 16) and 0xff).toByte()
                        secFat1[7] = ((cleanFat1 shr 24) and 0xff).toByte()
                        val w1 = NativeBridge.nativeWrite(handle, fat1Offset, secFat1, sectorSize)
                        if (w1 < 0) {
                            return Result.failure(Exception("Cannot write FAT32 FAT1"))
                        }

                        // 4. FAT 2 (backup FAT)
                        if (boot.fatCount > 1) {
                            val fat2Offset = toPhysicalOffset(boot.fatStartByte + boot.sectorsPerFat * sectorSize)
                            val secFat2Raw = NativeBridge.nativeRead(handle, fat2Offset, sectorSize)
                            if (secFat2Raw != null && secFat2Raw.size >= 8) {
                                val secFat2 = secFat2Raw.copyOf(sectorSize)
                                secFat2[4] = secFat1[4]
                                secFat2[5] = secFat1[5]
                                secFat2[6] = secFat1[6]
                                secFat2[7] = secFat1[7]
                                NativeBridge.nativeWrite(handle, fat2Offset, secFat2, sectorSize)
                            }
                        }
                    }

                    (reader as com.bitlockerdroid.ntfs.Fat32Reader).updateBootFlags(clean = true)
                }
                else -> {
                    return Result.failure(Exception("Unsupported filesystem: $fsName"))
                }
            }

            flush()
            invalidateCache()

            if (isDirty) {
                return Result.failure(Exception("Volume is still marked dirty after repair"))
            }

            LogFile.write("app", "repairDirty: successfully repaired $devicePath ($fsName)")
            return Result.success(fsName)
        } catch (e: Throwable) {
            Log.e(TAG, "repairDirty failed for $devicePath", e)
            return Result.failure(e)
        }
    }

    companion object {
        private const val TAG = "DislockerCore"
        private val seq = AtomicInteger(0)

        /**
         * Unlocks a BitLocker volume with a user password.
         * Returns a ready-to-use [DislockerCore] or throws.
         */
        fun open(devicePath: String, offset: Long, password: String): DislockerCore {
            com.bitlockerdroid.util.DevicePathSecurity.requireValid(devicePath)
            var usbSession: com.bitlockerdroid.usb.UsbStorageManager.UsbSession? = null
            val effectivePath: String
            if (devicePath.startsWith("usb://")) {
                val session = com.bitlockerdroid.usb.UsbStorageManager.openSession(devicePath)
                    ?: throw UnlockException("Failed to open USB device session for $devicePath")
                usbSession = session
                effectivePath = "fd:${session.nativeFd}"
            } else {
                effectivePath = devicePath
            }

            try {
                val handle = NativeBridge.nativeOpenVolume(
                    effectivePath, offset, password.toByteArray(Charsets.UTF_8)
                )
                if (handle == 0L) {
                    val err = NativeBridge.nativeGetLastError()
                    throw UnlockException("Unlock failed: $err")
                }
                val info = NativeBridge.sessionInfo(handle)
                    ?: run { NativeBridge.nativeClose(handle); throw UnlockException("Cannot read session info") }

                Log.i(TAG, "opened volume at $devicePath: ${info.algorithmName} ${info.volumeSize} bytes")
                return DislockerCore(devicePath, offset, handle, info, isRecovery = false, usbSession = usbSession)
            } catch (e: Throwable) {
                usbSession?.close()
                throw e
            }
        }

        /** Unlocks with a 48-digit recovery key. */
        fun openWithRecoveryKey(devicePath: String, offset: Long, recoveryKey: String): DislockerCore {
            com.bitlockerdroid.util.DevicePathSecurity.requireValid(devicePath)
            var usbSession: com.bitlockerdroid.usb.UsbStorageManager.UsbSession? = null
            val effectivePath: String
            if (devicePath.startsWith("usb://")) {
                val session = com.bitlockerdroid.usb.UsbStorageManager.openSession(devicePath)
                    ?: throw UnlockException("Failed to open USB device session for $devicePath")
                usbSession = session
                effectivePath = "fd:${session.nativeFd}"
            } else {
                effectivePath = devicePath
            }

            try {
                val handle = NativeBridge.nativeOpenVolumeRecovery(effectivePath, offset, recoveryKey)
                if (handle == 0L) {
                    val err = NativeBridge.nativeGetLastError()
                    throw UnlockException("Recovery unlock failed: $err")
                }
                val info = NativeBridge.sessionInfo(handle)
                    ?: run { NativeBridge.nativeClose(handle); throw UnlockException("Cannot read session info") }
                return DislockerCore(devicePath, offset, handle, info, isRecovery = true, usbSession = usbSession)
            } catch (e: Throwable) {
                usbSession?.close()
                throw e
            }
        }
    }
}

/** Thrown when the password / recovery key is rejected or the volume is unreadable. */
class UnlockException(message: String) : Exception(message)
