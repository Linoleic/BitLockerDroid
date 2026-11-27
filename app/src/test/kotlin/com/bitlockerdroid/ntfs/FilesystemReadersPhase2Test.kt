package com.bitlockerdroid.ntfs

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class FilesystemReadersPhase2Test {

    private class ArrayBlockSource(private val disk: ByteArray) : NtfsBlockSource {
        override val size: Long get() = disk.size.toLong()
        override fun read(offset: Long, dst: ByteArray, dstPos: Int, len: Int): Int {
            if (offset < 0 || offset >= disk.size || len <= 0) return 0
            val n = minOf(len, (disk.size - offset.toInt()), dst.size - dstPos)
            if (n <= 0) return 0
            System.arraycopy(disk, offset.toInt(), dst, dstPos, n)
            return n
        }
    }

    @Test
    fun cachedBlockSourceClearDuringInFlightReadDoesNotRepopulateCache() {
        val reads = AtomicInteger(0)
        val started = CountDownLatch(1)
        val allowFinish = CountDownLatch(1)

        val source = CachedBlockSource(volumeSize = 4096L, sectorSize = 512, blockSize = 512) { _, len ->
            val call = reads.incrementAndGet()
            if (call == 1) {
                started.countDown()
                allowFinish.await(2, TimeUnit.SECONDS)
            }
            ByteArray(len) { call.toByte() }
        }

        val out1 = ByteArray(16)
        val readerThread = Thread {
            assertEquals(16, source.read(0L, out1, 0, 16))
        }
        readerThread.start()

        assertTrue(started.await(2, TimeUnit.SECONDS))
        source.clear()
        allowFinish.countDown()
        readerThread.join(2000)

        // First reader should still receive its read bytes (all 1s).
        assertArrayEquals(ByteArray(16) { 1 }, out1)

        // Subsequent read must NOT hit stale cache from call 1; it must invoke rawSectorReader again (call 2).
        val out2 = ByteArray(16)
        assertEquals(16, source.read(0L, out2, 0, 16))
        assertEquals(2, reads.get())
        assertArrayEquals(ByteArray(16) { 2 }, out2)
    }

    @Test
    fun ntfsBootSectorValidationAndNegativeClusterSize() {
        val badZeroSpc = ByteArray(512).apply {
            "NTFS    ".toByteArray(Charsets.US_ASCII).copyInto(this, 3)
            this[11] = 0x00; this[12] = 0x02 // 512 bytes/sector
            this[13] = 0x00 // invalid 0 spc
            ByteBuffer.wrap(this, 40, 8).order(ByteOrder.LITTLE_ENDIAN).putLong(1000L)
        }
        assertNull(NtfsVolume.parseBootSector(badZeroSpc))

        val badSectorSize = badZeroSpc.copyOf().apply {
            this[11] = 0x01; this[12] = 0x00 // 1 byte/sector
            this[13] = 0x08
        }
        assertNull(NtfsVolume.parseBootSector(badSectorSize))

        val negSpc = ByteArray(512).apply {
            "NTFS    ".toByteArray(Charsets.US_ASCII).copyInto(this, 3)
            this[11] = 0x00; this[12] = 0x02 // 512 bytes/sector
            this[13] = 0xF8.toByte() // -8 => 2^8 = 256 sectors/cluster (128 KiB)
            ByteBuffer.wrap(this, 40, 8).order(ByteOrder.LITTLE_ENDIAN).putLong(4096L)
            ByteBuffer.wrap(this, 48, 8).order(ByteOrder.LITTLE_ENDIAN).putLong(2L)
        }
        val parsed = NtfsVolume.parseBootSector(negSpc)
        assertNotNull(parsed)
        assertEquals(256, parsed!!.sectorsPerCluster)
        assertEquals(512 * 256L, parsed.clusterSize)
    }

    @Test
    fun ntfsParserKeepsWin32SkipsDos83AndRejectsBadUsa() {
        val rec = ByteArray(1024)
        val bb = ByteBuffer.wrap(rec).order(ByteOrder.LITTLE_ENDIAN)
        // FILE magic, USA off=0x30, USA count=3, firstAttr=0x38, flags=0x01 (in-use)
        bb.putInt(0, 0x454c4946)
        bb.putShort(4, 0x30)
        bb.putShort(6, 3)
        bb.putShort(20, 0x38)
        bb.putShort(22, 0x01)

        // USA check=0x1234, sector tails = 0x1234
        bb.putShort(0x30, 0x1234)
        bb.putShort(510, 0x1234)
        bb.putShort(1022, 0x1234)

        // Build DOS 8.3 $FILE_NAME (type=0x30, nameType=2) followed by Win32 $FILE_NAME (type=0x30, nameType=1)
        fun writeFileNameAttr(offset: Int, nameType: Int, name: String): Int {
            val nameBytes = name.toByteArray(Charsets.UTF_16LE)
            val valLen = 66 + nameBytes.size
            val attrLen = ((24 + valLen + 7) / 8) * 8
            bb.putInt(offset, NtfsFileRecord.TYPE_FILE_NAME)
            bb.putInt(offset + 4, attrLen)
            rec[offset + 8] = 0 // resident
            bb.putInt(offset + 16, valLen)
            bb.putShort(offset + 20, 24) // valueOff
            val vp = offset + 24
            bb.putLong(vp, 5L) // parentRef
            rec[vp + 64] = name.length.toByte()
            rec[vp + 65] = nameType.toByte()
            nameBytes.copyInto(rec, vp + 66)
            return offset + attrLen
        }

        var pos = 0x38
        pos = writeFileNameAttr(pos, 2, "LONGFI~1.TXT")
        pos = writeFileNameAttr(pos, 1, "LongFileName.txt")
        bb.putInt(pos, -1) // 0xFFFFFFFF end

        assertTrue(NtfsFileRecordParser.applyUpdateSequenceArray(rec, 0x04, 512))
        val parsed = NtfsFileRecordParser.parse(10L, rec)
        assertNotNull(parsed)
        assertEquals("LongFileName.txt", parsed!!.fileName)

        // Corrupt USA check value at sector 1 tail -> applyUpdateSequenceArray must return false
        val badUsa = rec.copyOf()
        badUsa[510] = 0x00
        assertFalse(NtfsFileRecordParser.applyUpdateSequenceArray(badUsa, 0x04, 512))

        // Invalid USA offset/count -> must return false (not true)
        val badCount = rec.copyOf()
        ByteBuffer.wrap(badCount).order(ByteOrder.LITTLE_ENDIAN).putShort(6, 99)
        assertFalse(NtfsFileRecordParser.applyUpdateSequenceArray(badCount, 0x04, 512))
    }

    @Test
    fun fat32MasksHighFourBitsBeforeEofAndStopsOnCycle() {
        val boot = Fat32Volume.BootSector(
            bytesPerSector = 512,
            sectorsPerCluster = 1,
            reservedSectors = 1,
            fatCount = 1,
            sectorsPerFat = 1,
            rootCluster = 2L
        )
        // Disk layout: sector 0 = boot, sector 1 = FAT (512B), sector 2 = cluster 2, sector 3 = cluster 3
        val disk = ByteArray(4 * 512)
        val fat = ByteBuffer.wrap(disk).order(ByteOrder.LITTLE_ENDIAN)
        // FAT[2] = 0xF0000003 (high 4 reserved bits set, low 28 bits = cluster 3!)
        fat.putInt(512 + 2 * 4, 0xF0000003.toInt())
        // FAT[3] = 0x0FFFFFF8 (EOF)
        fat.putInt(512 + 3 * 4, 0x0FFFFFF8)

        "Hello".toByteArray().copyInto(disk, boot.clusterByte(2).toInt())
        "World".toByteArray().copyInto(disk, boot.clusterByte(3).toInt())

        val reader = Fat32Reader(ArrayBlockSource(disk), boot)
        val out = ByteArray(1024)
        val n = reader.readFile(2L, 0L, out, 0, out.size)
        assertEquals(1024, n)
        assertEquals("Hello", String(out, 0, 5))
        assertEquals("World", String(out, 512, 5))

        // Now create a FAT cycle: FAT[2] = 3, FAT[3] = 2 -> Brent's cycle detection must terminate cleanly (< buffer size)
        fat.putInt(512 + 2 * 4, 3)
        fat.putInt(512 + 3 * 4, 2)
        val cycleOut = ByteArray(4096)
        val cn = reader.readFile(2L, 0L, cycleOut, 0, cycleOut.size)
        assertTrue(cn in 1024..2048)
    }

    @Test
    fun exFatNoFatChainDirectoryAndCycleStop() {
        val boot = ExFatVolume.BootSector(
            bytesPerSector = 512,
            sectorsPerCluster = 1,
            fatOffsetBytes = 512L,
            clusterHeapOffsetBytes = 1024L,
            rootCluster = 2L
        )
        // Cluster 2 = root dir; Cluster 3..4 = contiguous subdir ("sub", noFatChain=true, DataLength=1024);
        // Cluster 5 = file inside subdir
        val disk = ByteArray(7 * 512)
        val rootOff = boot.clusterByte(2).toInt()
        // 0x85 File entry for "sub" (isDir=0x10, secondaryCount=2)
        disk[rootOff] = 0x85.toByte()
        disk[rootOff + 1] = 2
        disk[rootOff + 4] = 0x10
        // 0xC0 Stream extension (NoFatChain=0x02, nameLen=3, firstCluster=3, dataLength=1024)
        val sOff = rootOff + 32
        disk[sOff] = 0xc0.toByte()
        disk[sOff + 1] = 0x02
        disk[sOff + 3] = 3
        ByteBuffer.wrap(disk, sOff + 20, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(3)
        ByteBuffer.wrap(disk, sOff + 24, 8).order(ByteOrder.LITTLE_ENDIAN).putLong(1024L)
        // 0xC1 File name "sub"
        val nOff = rootOff + 64
        disk[nOff] = 0xc1.toByte()
        "sub".toByteArray(Charsets.UTF_16LE).copyInto(disk, nOff + 2)

        // Put a file "a.txt" in cluster 4 (the 2nd contiguous cluster of "sub")
        // Cluster 3 has no 0x00 end marker (filled with 0x80-cleared deleted entries 0x05)
        val c3 = boot.clusterByte(3).toInt()
        for (i in 0 until 512 step 32) disk[c3 + i] = 0x05
        val c4 = boot.clusterByte(4).toInt()
        disk[c4] = 0x85.toByte(); disk[c4 + 1] = 2
        disk[c4 + 32] = 0xc0.toByte(); disk[c4 + 35] = 5
        ByteBuffer.wrap(disk, c4 + 32 + 20, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(5)
        ByteBuffer.wrap(disk, c4 + 32 + 24, 8).order(ByteOrder.LITTLE_ENDIAN).putLong(12L)
        disk[c4 + 64] = 0xc1.toByte()
        "a.txt".toByteArray(Charsets.UTF_16LE).copyInto(disk, c4 + 66)

        val reader = ExFatReader(ArrayBlockSource(disk), boot)
        val rootEntries = reader.listDirectory(2L)
        assertEquals(1, rootEntries.size)
        assertEquals("sub", rootEntries[0].name)

        // If DataLength (1024 = 2 clusters) were ignored or only 1 cluster read, "a.txt" in cluster 4 would be missed!
        val subEntries = reader.listDirectory(3L)
        assertEquals(1, subEntries.size)
        assertEquals("a.txt", subEntries[0].name)
        assertEquals(12L, subEntries[0].size)

        // Also verify FAT cycle terminates in readFile (cluster 6 self-loop)
        val fat = ByteBuffer.wrap(disk).order(ByteOrder.LITTLE_ENDIAN)
        fat.putInt(512 + 6 * 4, 6) // self-loop on cluster 6
        val buf = ByteArray(2048)
        assertEquals(12, reader.readFile(5L, 0L, buf, 0, buf.size))
        // Reading an unindexed cluster with self-loop stops after 1 cluster = 512 bytes
        assertEquals(512, reader.readFile(6L, 0L, buf, 0, buf.size))
    }
}
