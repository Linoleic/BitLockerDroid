package com.bitlockerdroid.ntfs

/**
 * exFAT volume parsing (read-only) over the decrypted block device.
 *
 * Parses the exFAT boot sector (BPB) into the layout parameters the reader
 * needs. All offsets are PHYSICAL byte offsets — the block source reads at the
 * physical address and uses it as the CBC IV, so the values computed here are
 * used directly.
 *
 * Layout (from the exFAT spec):
 *   [boot region][FATs][cluster heap]
 *   FAT[cluster] = next cluster in chain (0 = free, 0xfffffff8+ = EOF).
 */
object ExFatVolume {

    const val SECTOR_SIZE = 512
    const val FAT_ENTRY_SIZE = 4

    class BootSector(
        val bytesPerSector: Int,
        val sectorsPerCluster: Int,
        val fatOffsetBytes: Long,
        val clusterHeapOffsetBytes: Long,
        val rootCluster: Long
    ) {
        val clusterSize: Long get() = bytesPerSector.toLong() * sectorsPerCluster

        /** Physical byte offset of the FAT table. */
        val fatStartByte: Long get() = fatOffsetBytes

        /** Physical byte offset of the first data cluster (cluster 2). */
        val dataStartByte: Long get() = clusterHeapOffsetBytes

        /** Physical byte offset of the first cluster of a FAT chain. */
        fun clusterByte(cluster: Long): Long =
            clusterHeapOffsetBytes + (cluster - 2) * clusterSize
    }

    /**
     * Parses the exFAT boot sector. Returns null if the sector does not carry
     * the exFAT signature ("EXFAT   ").
     */
    fun parseBootSector(sector: ByteArray): BootSector? {
        require(sector.size >= 512) { "boot sector too small" }

        val oem = String(sector, 3, 8, Charsets.US_ASCII)
        if (oem != "EXFAT   ") return null

        val shift = sector[108].toInt() and 0xff
        val bytesPerSector = if (shift in 9..12) (1 shl shift) else 512
        val spcShift = sector[109].toInt() and 0xff
        val sectorsPerCluster = if (spcShift in 0..25) (1 shl spcShift) else 1
        val fatOffset = le32(sector, 80)
        val clusterHeapOffset = le32(sector, 88)
        val rootCluster = le32(sector, 96)

        if (bytesPerSector <= 0 || sectorsPerCluster <= 0 ||
            fatOffset == 0L || clusterHeapOffset == 0L || rootCluster < 2) {
            return null
        }

        return BootSector(
            bytesPerSector = bytesPerSector,
            sectorsPerCluster = sectorsPerCluster,
            fatOffsetBytes = fatOffset * bytesPerSector,
            clusterHeapOffsetBytes = clusterHeapOffset * bytesPerSector,
            rootCluster = rootCluster
        )
    }

    /** Little-endian u32 at [off]. */
    fun le32(b: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 0 until 4) {
            v = v or (((b[off + i].toLong() and 0xff) shl (8 * i)))
        }
        return v
    }
}
