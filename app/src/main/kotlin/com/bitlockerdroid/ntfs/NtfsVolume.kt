package com.bitlockerdroid.ntfs

/**
 * NTFS volume parsing (read-only) over the dislocker-decrypted block device.
 *
 * Reads through a [NtfsBlockSource] which decrypts sectors via the native
 * dislocker core. Supports enough of NTFS to navigate directories and read
 * files: boot sector, $MFT file records, attributes, and data runs.
 */
object NtfsVolume {

    const val SECTOR_SIZE = 512

    class BootSector(
        val bytesPerSector: Int,
        val sectorsPerCluster: Int,
        val mftLcn: Long,          // MFT location in clusters
        val mftMirrorLcn: Long,
        val totalClusters: Long,
        /** 8-byte volume serial at boot sector offset 0x48. */
        val serial: Long
    ) {
        val clusterSize: Long get() = (bytesPerSector.toLong() * sectorsPerCluster)
        val mftStartByte: Long get() = mftLcn * clusterSize
    }

    /**
     * Parses the NTFS boot sector. Returns null if the sector does not contain
     * a valid NTFS signature ("NTFS    ").
     */
    fun parseBootSector(sector: ByteArray): BootSector? {
        require(sector.size >= 512) { "boot sector too small" }

        // "NTFS    " at offset 3
        val oem = String(sector, 3, 8, Charsets.US_ASCII)
        if (oem != "NTFS    ") return null

        val bytesPerSector = (sector[11].toInt() and 0xff) or
            ((sector[12].toInt() and 0xff) shl 8)
        val sectorsPerCluster = sector[13].toInt() and 0xff

        // at offset 48 (0x30): MFT LCN (8 bytes LE)
        val mftLcn = le64(sector, 48)
        // at offset 56 (0x38): MFTMirr LCN
        val mftMirrorLcn = le64(sector, 56)
        // total sectors at offset 40 (0x28)
        val totalSectors = le64(sector, 40)
        val totalClusters = totalSectors / sectorsPerCluster

        return BootSector(
            bytesPerSector = bytesPerSector,
            sectorsPerCluster = sectorsPerCluster,
            mftLcn = mftLcn,
            mftMirrorLcn = mftMirrorLcn,
            totalClusters = totalClusters,
            // 0x48 = volume serial (8 bytes LE). Not in $VOLUME_INFORMATION —
            // that attribute holds only version/flags.
            serial = le64(sector, 0x48)
        )
    }

    /** Little-endian u64 at [off]. */
    fun le64(b: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 0 until 8) {
            v = v or (((b[off + i].toLong() and 0xff) shl (8 * i)))
        }
        return v
    }

    /** Little-endian u32 at [off]. */
    fun le32(b: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 0 until 4) {
            v = v or (((b[off + i].toLong() and 0xff) shl (8 * i)))
        }
        return v
    }

    /** Little-endian u16 at [off]. */
    fun le16(b: ByteArray, off: Int): Int {
        return (b[off].toInt() and 0xff) or ((b[off + 1].toInt() and 0xff) shl 8)
    }
}
