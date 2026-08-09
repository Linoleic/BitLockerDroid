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
    val info: NativeBridge.SessionInfo
) : AutoCloseable {

    val reader: VolumeReader by lazy { buildReader() }
    val volumeLabel: String
        get() {
            val label = try { reader.volumeLabel() } catch (e: Exception) { null }
            return if (!label.isNullOrBlank()) label else "BitLocker ${info.algorithmName}"
        }

    private var closed = false

    /** Block source that reads decrypted sectors: raw sectors via su (root),
     *  decrypted by the native core. */
    private val blockSource = object : NtfsBlockSource {
        override val size: Long get() = info.volumeSize

        override fun read(offset: Long, dst: ByteArray, dstPos: Int, len: Int): Int {
            if (offset >= info.volumeSize) return 0
            val bounded = minOf(len.toLong(), info.volumeSize - offset).toInt()

            // Logical and physical offsets are absolute and identical; read the
            // encrypted bytes at `offset` via su.
            val enc = RootAccess.execBytes(
                "dd if='$devicePath' bs=1 skip=${offset} count=$bounded 2>/dev/null",
                15000
            ) ?: return 0
            if (enc.size != bounded) {
                Log.w(TAG, "su dd short read: got ${enc.size} want $bounded")
                return 0
            }

            // Decrypt via the native core (IV = absolute offset).
            val dec = NativeBridge.nativeDecryptBuffer(handle, enc, offset)
                ?: return 0
            System.arraycopy(dec, 0, dst, dstPos, dec.size)
            return dec.size
        }
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
            return ExFatReader(blockSource, exBoot)
        }

        val fatBoot = Fat32Volume.parseBootSector(bootBytes)
        if (fatBoot != null) {
            Log.i(TAG, "volume is FAT32 (oem=$oem)")
            return Fat32Reader(blockSource, fatBoot)
        }

        throw IllegalStateException(
            "Decrypted volume is neither NTFS nor FAT32 nor exFAT (oem=$oem)"
        )
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            NativeBridge.nativeClose(handle)
        } catch (e: Throwable) {
            Log.w(TAG, "close failed", e)
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
            val handle = NativeBridge.nativeOpenVolume(
                devicePath, offset, password.toByteArray(Charsets.UTF_8)
            )
            if (handle == 0L) {
                val err = NativeBridge.nativeGetLastError()
                throw UnlockException("Unlock failed: $err")
            }
            val info = NativeBridge.sessionInfo(handle)
                ?: run { NativeBridge.nativeClose(handle); throw UnlockException("Cannot read session info") }

            Log.i(TAG, "opened volume at $devicePath: ${info.algorithmName} ${info.volumeSize} bytes")
            return DislockerCore(devicePath, offset, handle, info)
        }

        /** Unlocks with a 48-digit recovery key. */
        fun openWithRecoveryKey(devicePath: String, offset: Long, recoveryKey: String): DislockerCore {
            val handle = NativeBridge.nativeOpenVolumeRecovery(devicePath, offset, recoveryKey)
            if (handle == 0L) {
                val err = NativeBridge.nativeGetLastError()
                throw UnlockException("Recovery unlock failed: $err")
            }
            val info = NativeBridge.sessionInfo(handle)
                ?: run { NativeBridge.nativeClose(handle); throw UnlockException("Cannot read session info") }
            return DislockerCore(devicePath, offset, handle, info)
        }
    }
}

/** Thrown when the password / recovery key is rejected or the volume is unreadable. */
class UnlockException(message: String) : Exception(message)
