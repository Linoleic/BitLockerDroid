package com.bitlockerdroid.util

/**
 * JNI bridge to the native dislocker core (libdislocker.so).
 *
 * The native side registers these methods in JNI_OnLoad; the function names
 * must match exactly. Sessions are held as long handles on the native side and
 * must be closed explicitly via close().
 */
object NativeBridge {

    init {
        System.loadLibrary("dislocker")
    }

    /** Returns true if ARMv8 Cryptography Extensions (Hardware AES) are supported by CPU and passed self-test. */
    external fun nativeIsHardwareAesSupported(): Boolean

    /** Returns true if ARMv8 Hardware AES is currently active. */
    external fun nativeIsHardwareAesEnabled(): Boolean

    /** Enables or disables ARMv8 Hardware AES at runtime (switching between hardware and software fallback). */
    external fun nativeSetHardwareAesEnabled(enabled: Boolean)

    /** Returns detailed underlying diagnosis string for ARMv8 Hardware AES (HWCAP, self-test status). */
    external fun nativeGetHardwareAesDetails(): String

    /** Returns true if ARMv8 Cryptography Extensions (Hardware SHA-256) are supported by CPU and passed self-test. */
    external fun nativeIsHardwareSha2Supported(): Boolean

    /** Returns true if ARMv8 Hardware SHA-256 is currently active. */
    external fun nativeIsHardwareSha2Enabled(): Boolean

    /** Enables or disables ARMv8 Hardware SHA-256 at runtime (switching between hardware and software fallback). */
    external fun nativeSetHardwareSha2Enabled(enabled: Boolean)

    /** Returns detailed underlying diagnosis string for ARMv8 Hardware SHA-256 (HWCAP, self-test status). */
    external fun nativeGetHardwareSha2Details(): String

    /** Returns true if all ARMv8 Cryptography Extensions (both AES and SHA-256) are supported by CPU. */
    fun isHardwareCryptoSupported(): Boolean = try {
        nativeIsHardwareAesSupported() && nativeIsHardwareSha2Supported()
    } catch (_: Throwable) { false }

    /** Returns true if both ARMv8 Hardware AES and SHA-256 are active. */
    fun isHardwareCryptoEnabled(): Boolean = try {
        nativeIsHardwareAesEnabled() && nativeIsHardwareSha2Enabled()
    } catch (_: Throwable) { false }

    /** Enables or disables all ARMv8 Hardware Cryptography Extensions at runtime. */
    fun setHardwareCryptoEnabled(enabled: Boolean) {
        try { nativeSetHardwareAesEnabled(enabled) } catch (_: Throwable) {}
        try { nativeSetHardwareSha2Enabled(enabled) } catch (_: Throwable) {}
    }

    /** Runs key stretching micro-benchmark for [rounds] rounds and returns elapsed time in microseconds. */
    external fun nativeBenchmarkKeyStretching(rounds: Int): Long

    /** Queries SCSI, SAT, or NVMe SMART telemetry for block device at [devicePath] and returns JSON string. */
    external fun nativeReadDeviceSmart(devicePath: String): String?

    /** True if the block device at [path] carries a BitLocker volume header. */
    external fun nativeHasBitLockerHeader(path: String): Boolean

    /**
     * Opens a BitLocker volume at [path] (block device) with partition [offset]
     * and decrypts the VMK/FVEK using [password] (UTF-8 bytes).
     * Returns a native session handle (nonzero) or 0 on failure.
     */
    external fun nativeOpenVolume(path: String, offset: Long, password: ByteArray): Long

    /** Same, but using a 48-digit recovery key string. */
    external fun nativeOpenVolumeRecovery(path: String, offset: Long, recoveryKey: String): Long

    /**
     * Returns long[4]: { sectorSize, volumeSize, algorithm, fvekLen }.
     * Throws IllegalStateException if the handle is invalid.
     */
    external fun nativeSessionInfo(handle: Long): LongArray

    /** Returns the volume GUID string (e.g. 4967d63b-2e29-4ad8-8399-f6a339e3d001) or null. */
    external fun nativeGetVolumeGuid(handle: Long): String?

    /** Returns the 48-digit recovery key identifier (uppercase GUID) or null. */
    external fun nativeGetRecoveryKeyId(handle: Long): String?

    /** Reads [size] decrypted bytes at [offset]; returns null on error. */
    external fun nativeRead(handle: Long, offset: Long, size: Int): ByteArray?

    /** Writes [size] bytes from [data] at decrypted [offset]; returns bytes written or -1 on error. */
    external fun nativeWrite(handle: Long, offset: Long, data: ByteArray, size: Int): Int

    /** Decrypts an already-read encrypted sector buffer at [offset]. */
    external fun nativeDecryptBuffer(handle: Long, input: ByteArray, offset: Long): ByteArray?

    /** Encrypts a plaintext sector buffer at [offset]. Enforces write barrier. */
    external fun nativeEncryptBuffer(handle: Long, input: ByteArray, offset: Long): ByteArray?

    external fun nativeClose(handle: Long)

    /** Flushes the encrypted block device (fdatasync / daemon sync). Safe-eject step. */
    external fun nativeSync(handle: Long): Int

    external fun nativeGetLastError(): String

    fun getLastError(): String = nativeGetLastError()

    // ---------------- NTFS-3G Integration ----------------

    /** Mounts the NTFS volume over the dislocker session; returns native ntfs volume handle or 0. */
    external fun nativeNtfsMount(handle: Long, readOnly: Boolean): Long

    /** Unmounts the NTFS volume. */
    external fun nativeNtfsUmount(volHandle: Long): Int

    /** Creates a file (isDir=false) or directory (isDir=true) in parentPath. Returns created MFT number (>= 0) or negative error. */
    external fun nativeNtfsCreate(volHandle: Long, parentPath: String?, name: String, isDir: Boolean): Long

    /** Deletes a file or empty directory by path. Returns 0 on success. */
    external fun nativeNtfsDelete(volHandle: Long, path: String): Int

    /** Renames/moves a file or directory from oldPath to newPath. Returns 0 on success. */
    external fun nativeNtfsRename(volHandle: Long, oldPath: String, newPath: String): Int

    /** Writes [count] bytes from [data] at [offset] in file [path]. Returns bytes written or -1. */
    external fun nativeNtfsWrite(volHandle: Long, path: String, offset: Long, data: ByteArray, count: Int): Long

    /** Truncates or extends file [path] to [newSize]. Returns 0 on success. */
    external fun nativeNtfsTruncate(volHandle: Long, path: String, newSize: Long): Long

    /** Returns long[2] { totalBytes, freeBytes } or null on error. */
    external fun nativeNtfsGetSpace(volHandle: Long): LongArray?

    /** Clears NTFS dirty flags (VOLUME_IS_DIRTY, VOLUME_CHKDSK_UNDERWAY). Returns 0 on success. */
    external fun nativeNtfsRepairDirty(volHandle: Long): Int

    /** Syncs and flushes NTFS volume metadata and dirty sectors to device. Returns 0 on success. */
    external fun nativeNtfsSync(volHandle: Long): Int

    // ---------------- FatFs Integration (FAT32 & exFAT) ----------------

    /** Mounts the FAT/exFAT volume over the dislocker session; returns native volume handle or 0. */
    external fun nativeFatfsMount(handle: Long, readOnly: Boolean): Long

    /** Unmounts the FatFs volume. */
    external fun nativeFatfsUmount(volHandle: Long): Int

    /** Creates a file (isDir=false) or directory (isDir=true) in parentPath. Returns created cluster or negative error. */
    external fun nativeFatfsCreate(volHandle: Long, parentPath: String?, name: String, isDir: Boolean): Long

    /** Deletes a file or directory at path. Returns 0 on success. */
    external fun nativeFatfsDelete(volHandle: Long, path: String): Int

    /** Renames/moves a file or directory from oldPath to newPath. Returns 0 on success. */
    external fun nativeFatfsRename(volHandle: Long, oldPath: String, newPath: String): Int

    /** Writes [count] bytes from [data] at [offset] in file [path]. Returns bytes written or -1. */
    external fun nativeFatfsWrite(volHandle: Long, path: String, offset: Long, data: ByteArray, count: Int): Long

    /** Truncates or extends file [path] to [newSize]. Returns 0 on success. */
    external fun nativeFatfsTruncate(volHandle: Long, path: String, newSize: Long): Long

    /** Returns long[2] { totalBytes, freeBytes } or null on error. */
    external fun nativeFatfsGetSpace(volHandle: Long): LongArray?

    /** Syncs and flushes FatFs volume dirty sectors to device. Returns 0 on success. */
    external fun nativeFatfsSync(volHandle: Long): Int

    // ---------------- Disaster Recovery & Low-level Backup ----------------

    /**
     * Extracts .fvemeta disaster recovery package from a BitLocker partition.
     * Can be invoked on locked volumes without password.
     */
    external fun nativeExtractFveMetadata(path: String, offset: Long): ByteArray?

    /**
     * Extracts .fvemeta disaster recovery package from an already open dislocker handle.
     */
    external fun nativeExtractFveMetadataFromHandle(handle: Long): ByteArray?

    /**
     * Restores .fvemeta disaster recovery package to target partition.
     * Validates SHA-256 and strictly checks target partition size against backup header.
     * Returns 0 on success, negative error code on failure.
     */
    external fun nativeRestoreFveMetadata(path: String, offset: Long, data: ByteArray, targetPartitionSize: Long): Int

    /**
     * Opens a raw device/partition session for raw sector access and imaging.
     */
    external fun nativeOpenRawDevice(path: String, offset: Long): Long

    /**
     * Reads raw (physical/encrypted) sectors via handle with resilient bad sector zero-fill fallback.
     */
    external fun nativeReadRaw(handle: Long, offset: Long, size: Int): ByteArray?

    /**
     * Gets the partition/device size in bytes.
     */
    external fun nativeGetDeviceSize(handle: Long): Long

    // ---------------- convenience wrappers ----------------

    class SessionInfo(
        val sectorSize: Int,
        val volumeSize: Long,
        val algorithm: Int,
        val fvekLen: Int,
        val dataOffset: Long = 0
    ) {
        val algorithmName: String
            get() = when (algorithm) {
                0x8004 -> "AES-XTS-128"
                0x8005 -> "AES-XTS-256"
                0x8000, 0x8002 -> "AES-128"
                0x8001, 0x8003 -> "AES-256"
                else -> "0x%04x".format(algorithm)
            }
    }

    fun sessionInfo(handle: Long): SessionInfo? {
        val arr = try {
            nativeSessionInfo(handle)
        } catch (e: Exception) {
            null
        } ?: return null
        if (arr.size < 4) return null
        return SessionInfo(
            arr[0].toInt(), arr[1], arr[2].toInt(), arr[3].toInt(),
            if (arr.size >= 5) arr[4] else 0L
        )
    }
}
