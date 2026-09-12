package com.bitlockerdroid.ntfs

/**
 * Common abstraction for write operations on a decrypted filesystem volume
 * (e.g. NTFS via ntfs-3g, FAT32/exFAT via FatFs).
 */
interface VolumeWriter : AutoCloseable {
    val isMounted: Boolean
    fun createFile(parentPath: String?, name: String, isDirectory: Boolean): Long
    fun delete(path: String): Boolean
    fun rename(oldPath: String, newPath: String): Boolean
    fun write(path: String, offset: Long, data: ByteArray, count: Int = data.size): Long
    fun truncate(path: String, newSize: Long): Boolean
    fun getSpace(): Pair<Long, Long>? = null
}
