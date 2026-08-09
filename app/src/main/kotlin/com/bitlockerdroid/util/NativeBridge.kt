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

    /** Reads [size] decrypted bytes at [offset]; returns null on error. */
    external fun nativeRead(handle: Long, offset: Long, size: Int): ByteArray?

    /** Decrypts an already-read encrypted sector buffer at [offset]. */
    external fun nativeDecryptBuffer(handle: Long, input: ByteArray, offset: Long): ByteArray?

    external fun nativeClose(handle: Long)

    external fun nativeGetLastError(): String

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
