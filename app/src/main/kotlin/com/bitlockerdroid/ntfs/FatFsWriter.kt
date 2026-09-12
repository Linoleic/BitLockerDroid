package com.bitlockerdroid.ntfs

import android.util.Log
import com.bitlockerdroid.util.NativeBridge

/**
 * Handles write, create, delete, and rename operations on FAT32 and exFAT volumes
 * using ChaN's FatFs engine mounted over the decrypted BitLocker session.
 */
class FatFsWriter(
    private val sessionHandle: Long,
    readOnly: Boolean = false
) : VolumeWriter {

    companion object {
        private const val TAG = "FatFsWriter"
    }

    private var volHandle: Long = 0L

    init {
        volHandle = NativeBridge.nativeFatfsMount(sessionHandle, readOnly)
        if (volHandle == 0L) {
            Log.e(TAG, "Failed to mount FAT/exFAT volume with FatFs: ${NativeBridge.nativeGetLastError()}")
        } else {
            Log.i(TAG, "Successfully mounted FAT/exFAT volume with FatFs (volHandle=$volHandle)")
        }
    }

    override val isMounted: Boolean get() = volHandle != 0L

    override fun createFile(parentPath: String?, name: String, isDirectory: Boolean): Long {
        if (!isMounted) return -1L
        val clst = NativeBridge.nativeFatfsCreate(volHandle, parentPath, name, isDirectory)
        if (clst < 0) {
            Log.e(TAG, "createFile failed for $name in $parentPath: error=$clst (${NativeBridge.nativeGetLastError()})")
            return -1L
        }
        return clst
    }

    override fun delete(path: String): Boolean {
        if (!isMounted) return false
        val ret = NativeBridge.nativeFatfsDelete(volHandle, path)
        if (ret != 0) {
            Log.e(TAG, "delete failed for $path: error=$ret (${NativeBridge.nativeGetLastError()})")
            return false
        }
        return true
    }

    override fun rename(oldPath: String, newPath: String): Boolean {
        if (!isMounted) return false
        val ret = NativeBridge.nativeFatfsRename(volHandle, oldPath, newPath)
        if (ret != 0) {
            Log.e(TAG, "rename failed for $oldPath -> $newPath: error=$ret (${NativeBridge.nativeGetLastError()})")
            return false
        }
        return true
    }

    override fun write(path: String, offset: Long, data: ByteArray, count: Int): Long {
        if (!isMounted) return -1L
        val ret = NativeBridge.nativeFatfsWrite(volHandle, path, offset, data, count)
        if (ret < 0) {
            Log.e(TAG, "write failed for $path at offset $offset: error=$ret (${NativeBridge.nativeGetLastError()})")
        }
        return ret
    }

    override fun truncate(path: String, newSize: Long): Boolean {
        if (!isMounted) return false
        val ret = NativeBridge.nativeFatfsTruncate(volHandle, path, newSize)
        if (ret != 0L) {
            Log.e(TAG, "truncate failed for $path to $newSize: error=$ret (${NativeBridge.nativeGetLastError()})")
            return false
        }
        return true
    }

    override fun getSpace(): Pair<Long, Long>? {
        if (!isMounted) return null
        val arr = NativeBridge.nativeFatfsGetSpace(volHandle) ?: return null
        return Pair(arr[0], arr[1])
    }

    override fun close() {
        if (volHandle != 0L) {
            NativeBridge.nativeFatfsUmount(volHandle)
            volHandle = 0L
            Log.i(TAG, "Unmounted FatFs volume")
        }
    }
}
