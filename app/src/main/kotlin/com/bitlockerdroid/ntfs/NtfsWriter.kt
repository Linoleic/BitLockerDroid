package com.bitlockerdroid.ntfs

import android.util.Log
import com.bitlockerdroid.util.NativeBridge

/**
 * Handles write, create, delete, and rename operations on an NTFS volume using
 * the native libntfs-3g engine mounted over the decrypted BitLocker session.
 */
class NtfsWriter(
    private val sessionHandle: Long,
    readOnly: Boolean = false
) : VolumeWriter {

    companion object {
        private const val TAG = "NtfsWriter"
    }

    private var volHandle: Long = 0L

    init {
        volHandle = NativeBridge.nativeNtfsMount(sessionHandle, readOnly)
        if (volHandle == 0L) {
            Log.e(TAG, "Failed to mount NTFS volume with ntfs-3g: ${NativeBridge.nativeGetLastError()}")
        } else {
            Log.i(TAG, "Successfully mounted NTFS volume with ntfs-3g (volHandle=$volHandle)")
        }
    }

    override val isMounted: Boolean get() = volHandle != 0L

    override fun createFile(parentPath: String?, name: String, isDirectory: Boolean): Long {
        if (!isMounted) return -1L
        val mftNo = NativeBridge.nativeNtfsCreate(volHandle, parentPath, name, isDirectory)
        if (mftNo < 0) {
            Log.e(TAG, "createFile failed for $name in $parentPath: error=$mftNo (${NativeBridge.nativeGetLastError()})")
            return -1L
        }
        return mftNo
    }

    override fun delete(path: String): Boolean {
        if (!isMounted) return false
        val ret = NativeBridge.nativeNtfsDelete(volHandle, path)
        if (ret != 0) {
            Log.e(TAG, "delete failed for $path: error=$ret (${NativeBridge.nativeGetLastError()})")
            return false
        }
        return true
    }

    override fun rename(oldPath: String, newPath: String): Boolean {
        if (!isMounted) return false
        val ret = NativeBridge.nativeNtfsRename(volHandle, oldPath, newPath)
        if (ret != 0) {
            Log.e(TAG, "rename failed for $oldPath -> $newPath: error=$ret (${NativeBridge.nativeGetLastError()})")
            return false
        }
        return true
    }

    override fun write(path: String, offset: Long, data: ByteArray, count: Int): Long {
        if (!isMounted) return -1L
        val ret = NativeBridge.nativeNtfsWrite(volHandle, path, offset, data, count)
        if (ret < 0) {
            Log.e(TAG, "write failed for $path at offset $offset: error=$ret (${NativeBridge.nativeGetLastError()})")
        }
        return ret
    }

    override fun truncate(path: String, newSize: Long): Boolean {
        if (!isMounted) return false
        val ret = NativeBridge.nativeNtfsTruncate(volHandle, path, newSize)
        if (ret != 0L) {
            Log.e(TAG, "truncate failed for $path to $newSize: error=$ret (${NativeBridge.nativeGetLastError()})")
            return false
        }
        return true
    }

    override fun getSpace(): Pair<Long, Long>? {
        if (!isMounted) return null
        val arr = NativeBridge.nativeNtfsGetSpace(volHandle) ?: return null
        return Pair(arr[0], arr[1])
    }

    override fun close() {
        if (volHandle != 0L) {
            NativeBridge.nativeNtfsUmount(volHandle)
            volHandle = 0L
            Log.i(TAG, "Unmounted NTFS volume")
        }
    }
}
