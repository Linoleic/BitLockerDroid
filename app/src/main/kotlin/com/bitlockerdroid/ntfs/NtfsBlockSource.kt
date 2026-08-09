package com.bitlockerdroid.ntfs

/**
 * Abstraction over the decrypted volume for the NTFS parser. Implementations
 * decrypt on demand through the native dislocker core.
 */
interface NtfsBlockSource {
    /** Total size of the decrypted volume in bytes. */
    val size: Long

    /**
     * Read exactly [len] bytes at absolute byte [offset] into [dst] at [dstPos].
     * Returns the number of bytes read; fewer than [len] means end-of-volume.
     */
    fun read(offset: Long, dst: ByteArray, dstPos: Int, len: Int): Int

    fun readSector(sectorNumber: Long, dst: ByteArray, dstPos: Int = 0): Int =
        read(sectorNumber * NtfsVolume.SECTOR_SIZE, dst, dstPos, NtfsVolume.SECTOR_SIZE)
}

/** An in-memory block source backed by an already-decrypted byte array. */
class MemoryBlockSource(private val data: ByteArray) : NtfsBlockSource {
    override val size: Long get() = data.size.toLong()

    override fun read(offset: Long, dst: ByteArray, dstPos: Int, len: Int): Int {
        if (offset >= data.size) return 0
        val n = minOf(len.toLong(), data.size - offset).toInt()
        System.arraycopy(data, offset.toInt(), dst, dstPos, n)
        return n
    }
}
