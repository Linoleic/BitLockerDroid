package com.bitlockerdroid.ntfs

/**
 * FAT32 volume parsing (read-only) over the decrypted block device.
 *
 * Parses the FAT32 boot sector (BPB) into the layout parameters the reader
 * needs. All offsets are PHYSICAL byte offsets into the decrypted volume —
 * the block source reads at the physical address and uses that address as the
 * CBC IV, so the values computed here are used directly.
 */
object Fat32Volume {

    const val SECTOR_SIZE = 512

    class BootSector(
        val bytesPerSector: Int,
        val sectorsPerCluster: Int,
        val reservedSectors: Int,
        val fatCount: Int,
        val sectorsPerFat: Long,
        val rootCluster: Long
    ) {
        val clusterSize: Long get() = bytesPerSector.toLong() * sectorsPerCluster

        /** Physical byte offset of FAT[0] (reserved sectors after the boot area). */
        val fatStartByte: Long get() = reservedSectors.toLong() * bytesPerSector

        /** Physical byte offset of the data area start. */
        val dataStartByte: Long
            get() = (reservedSectors.toLong() + fatCount.toLong() * sectorsPerFat) * bytesPerSector

        /** Physical byte offset of the first cluster of a FAT chain. */
        fun clusterByte(cluster: Long): Long =
            dataStartByte + (cluster - 2) * clusterSize
    }

    /**
     * Parses the FAT32 boot sector. Returns null if the sector does not carry
     * a FAT32 signature ("MSDOS5.0" + "FAT32   ", or an OEM that starts with
     * "MSDOS"/"MSWIN" and a FAT32 FS type field).
     */
    fun parseBootSector(sector: ByteArray): BootSector? {
        require(sector.size >= 90) { "boot sector too small" }

        val oem = String(sector, 3, 8, Charsets.US_ASCII)
        val fsType = String(sector, 82, 8, Charsets.US_ASCII)
        val isFat32 = fsType.trim() == "FAT32" ||
            (oem.startsWith("MSDOS") && sector[11].toInt() == 0x00 && sector[12].toInt() == 0x02)
        if (!isFat32) return null

        val bytesPerSector = (sector[11].toInt() and 0xff) or
            ((sector[12].toInt() and 0xff) shl 8)
        val sectorsPerCluster = sector[13].toInt() and 0xff
        val reservedSectors = (sector[14].toInt() and 0xff) or
            ((sector[15].toInt() and 0xff) shl 8)
        val fatCount = sector[16].toInt() and 0xff

        // FAT32: sectors per FAT is a 32-bit value at offset 0x24.
        val sectorsPerFat = le32(sector, 0x24)
        val rootCluster = le32(sector, 0x2c)

        if (bytesPerSector == 0 || sectorsPerCluster == 0 || fatCount == 0 ||
            sectorsPerFat == 0L || rootCluster == 0L) return null

        return BootSector(
            bytesPerSector = bytesPerSector,
            sectorsPerCluster = sectorsPerCluster,
            reservedSectors = reservedSectors,
            fatCount = fatCount,
            sectorsPerFat = sectorsPerFat,
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
