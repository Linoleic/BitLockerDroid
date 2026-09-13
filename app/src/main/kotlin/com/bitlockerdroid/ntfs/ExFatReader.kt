package com.bitlockerdroid.ntfs

import com.bitlockerdroid.ntfs.ExFatVolume.BootSector
import java.util.concurrent.ConcurrentHashMap

/**
 * Read-only exFAT volume navigator. Lists directories through the exFAT entry
 * sets and streams file data by following the FAT cluster chains or contiguous allocations.
 *
 * All byte offsets are physical (see [ExFatVolume.BootSector]) and handed to
 * the block source, which decrypts at the physical address.
 *
 * Entry set layout (per Microsoft exFAT spec):
 *   - 0x85 File Directory Entry: secondaryCount@1, attrs@4 (Dir bit=0x10)
 *   - 0xC0 Stream Extension: flags@1 (NoFatChain bit=0x02), first_cluster@20, data_length@24
 *   - 0xC1 File Name: UTF-16LE name from byte 2 (15 chars per entry)
 */
class ExFatReader(
    private val source: NtfsBlockSource,
    private val boot: BootSector,
    private val bootBytes: ByteArray = ByteArray(0)
) : VolumeReader {

    companion object {
        private const val ENTRY_SIZE = 32
        private const val FAT_ENTRY_SIZE = 4

        // Primary Entry types
        private const val ENTRY_FILE = 0x85         // File directory entry (Critical Primary)
        private const val ENTRY_BITMAP = 0x81
        private const val ENTRY_UPCASE = 0x82
        private const val ENTRY_VOLUME_LABEL = 0x83
        private const val ENTRY_VOLUME_GUID = 0xa0

        // Secondary Entry types
        private const val ENTRY_STREAM = 0xc0       // Stream extension entry (Critical Secondary)
        private const val ENTRY_NAME = 0xc1         // File name entry (Critical Secondary)

        private const val EOF_MARK = 0x0ffffff8L
        private const val BAD_CLUSTER = 0x0ffffff7L

        // File entry attribute: directory bit.
        private const val ATTR_DIRECTORY = 0x10
    }

    override val rootRef: Long get() = boot.rootCluster

    override fun volumeSerial(): Long {
        // exFAT volume serial number is a u32 at boot offset 0x64.
        if (bootBytes.size >= 0x68) return le32(bootBytes, 0x64)
        return 0L
    }

    /** Cache of entries by ref. */
    private val entryCache = ConcurrentHashMap<Long, VolumeEntry>()
    private val noFatChainMap = ConcurrentHashMap<Long, Boolean>()
    private val startClusterMap = ConcurrentHashMap<Long, Long>()

    override fun volumeLabel(): String? {
        if (labelCache == null) {
            listDirectory(rootRef)
        }
        return labelCache
    }

    @Volatile
    private var labelCache: String? = null

    override fun invalidateCache() {
        entryCache.clear()
        noFatChainMap.clear()
        startClusterMap.clear()
        labelCache = null
    }

    override fun readEntry(ref: Long): VolumeEntry? {
        entryCache[ref]?.let { return it }
        if (ref == rootRef) {
            val entry = VolumeEntry(
                ref = ref,
                isDirectory = true,
                fileName = null,
                fileSize = 0L
            )
            entryCache[ref] = entry
            startClusterMap[ref] = ref
            noFatChainMap[ref] = false
            return entry
        }
        // Cache miss: directory may have been invalidated after a write. Refresh by reading root directory.
        try {
            listDirectory(rootRef)
        } catch (_: Exception) {}
        return entryCache[ref]
    }

    /**
     * Lists children of the directory whose first cluster is [ref].
     * Directory data may span several clusters linked through the FAT or contiguous.
     */
    override fun listDirectory(ref: Long): List<VolumeDirEntry> {
        val out = ArrayList<VolumeDirEntry>()
        val firstCluster = startClusterMap[ref] ?: ref
        if (!isValidCluster(firstCluster)) return out

        val noFatChain = noFatChainMap[ref] ?: false
        val clusterSize = boot.clusterSize.toInt()
        val buf = ByteArray(clusterSize)
        var cluster = firstCluster
        var guard = 0
        val maxClusters = if (noFatChain) {
            val sz = entryCache[ref]?.fileSize ?: 0L
            if (sz > 0) ((sz + clusterSize - 1) / clusterSize).toInt().coerceAtLeast(1) else 1
        } else {
            100000
        }
        while (isValidCluster(cluster) && guard++ < maxClusters) {
            val n = source.read(boot.clusterByte(cluster), buf, 0, buf.size)
            if (n < ENTRY_SIZE) break
            val endOfDir = parseDirCluster(buf, n, out, cluster)
            if (endOfDir) break
            cluster = if (noFatChain) (cluster + 1) else nextCluster(cluster)
            if (!noFatChain && isEof(cluster)) break
        }
        return out
    }

    /** Parses one directory cluster, collecting entry sets. Returns true if end of directory marker (0x00) was hit. */
    private fun parseDirCluster(buf: ByteArray, len: Int, out: MutableList<VolumeDirEntry>, cluster: Long): Boolean {
        var pos = 0
        while (pos + ENTRY_SIZE <= len) {
            val type = buf[pos].toInt() and 0xff
            if (type == 0x00) return true // end of directory
            if (type == 0xe5 || (type and 0x80) == 0) { pos += ENTRY_SIZE; continue } // deleted or unused

            when (type) {
                ENTRY_VOLUME_LABEL -> {
                    // 0x83 volume label: CharacterCount is at offset 1 (up to 11 chars). Name at offset 2.
                    val charCount = buf[pos + 1].toInt() and 0xff
                    if (charCount in 1..11 && pos + 2 + charCount * 2 <= len) {
                        labelCache = String(buf, pos + 2, charCount * 2, Charsets.UTF_16LE)
                    }
                    pos += ENTRY_SIZE
                }
                ENTRY_FILE -> {
                    val (entrySet, nextPos) = parseFileEntrySet(buf, pos, len, cluster)
                    if (entrySet != null) {
                        entryCache[entrySet.ref] = entrySet
                        out.add(VolumeDirEntry(entrySet.fileName ?: "", entrySet.ref, entrySet.isDirectory, entrySet.fileSize))
                    }
                    pos = nextPos
                }
                else -> pos += ENTRY_SIZE
            }
        }
        return false
    }

    /**
     * Parses a file entry set starting at a 0x85 File entry and advances past
     * all of its secondary entries (stream extension + file name entries).
     * Returns (entry, position-after-set).
     */
    private fun parseFileEntrySet(buf: ByteArray, start: Int, len: Int, cluster: Long): Pair<VolumeEntry?, Int> {
        val secondaryCount = buf[start + 1].toInt() and 0xff
        val attrLow = buf[start + 4].toInt() and 0xff
        val attrHigh = buf[start + 5].toInt() and 0xff
        val attrs = (attrHigh shl 8) or attrLow
        val isDir = (attrs and ATTR_DIRECTORY) != 0

        var pos = start + ENTRY_SIZE
        var firstCluster = 0L
        var dataLength = 0L
        var noFatChain = false
        val nameChunks = ArrayList<String>()

        var count = 0
        while (count < secondaryCount && pos + ENTRY_SIZE <= len) {
            val secType = buf[pos].toInt() and 0xff
            when (secType) {
                ENTRY_STREAM -> {
                    val flags = buf[pos + 1].toInt() and 0xff
                    noFatChain = (flags and 0x02) != 0
                    firstCluster = le32(buf, pos + 20)
                    dataLength = le64(buf, pos + 24)
                }
                ENTRY_NAME -> {
                    val chunk = String(buf, pos + 2, 30, Charsets.UTF_16LE).trimEnd('\u0000')
                    if (chunk.isNotEmpty()) nameChunks.add(chunk)
                }
            }
            pos += ENTRY_SIZE
            count++
        }

        val name = nameChunks.joinToString("").trimEnd('\u0000')
        val posRef = 0x4000000000000000L or ((cluster and 0xFFFFFFL) shl 24) or (pos.toLong() and 0xFFFFFFL)
        val startRef = 0x4000000000000000L or ((cluster and 0xFFFFFFL) shl 24) or (start.toLong() and 0xFFFFFFL)
        val ref = if (firstCluster >= 2) firstCluster else startRef
        val entry = VolumeEntry(
            ref = ref,
            isDirectory = isDir,
            fileName = name.ifEmpty { null },
            fileSize = dataLength
        )
        startClusterMap[ref] = firstCluster
        noFatChainMap[ref] = noFatChain
        entryCache[ref] = entry

        // Also map directory-offset pseudo-refs so document IDs issued before cluster allocation
        // continue to resolve consistently after the file is written to:
        startClusterMap[posRef] = firstCluster
        noFatChainMap[posRef] = noFatChain
        entryCache[posRef] = entry

        startClusterMap[startRef] = firstCluster
        noFatChainMap[startRef] = noFatChain
        entryCache[startRef] = entry

        return entry to pos
    }

    /**
     * Reads file content for the cluster chain starting at [ref] into [dst].
     * Returns bytes copied.
     */
    override fun readFile(ref: Long, offset: Long, dst: ByteArray, dstPos: Int, len: Int): Int {
        val firstCluster = startClusterMap[ref] ?: ref
        if (!isValidCluster(firstCluster) || len <= 0) return 0
        val clusterSize = boot.clusterSize.toInt()
        val cachedSize = entryCache[ref]?.fileSize
        if (cachedSize != null && offset >= cachedSize) return 0
        val toRead = if (cachedSize != null) {
            minOf(len.toLong(), cachedSize - offset).toInt()
        } else len

        val noFatChain = noFatChainMap[ref] ?: false
        var filePos = 0L
        var cluster = firstCluster
        var written = 0
        var guard = 0
        while (isValidCluster(cluster) && guard++ < 100000 && written < toRead) {
            val clusterStart = filePos
            val clusterEnd = filePos + clusterSize
            if (offset < clusterEnd) {
                val within = (offset - clusterStart).coerceAtLeast(0)
                if (within < clusterSize) {
                    val chunkLen = minOf(
                        (toRead - written).toLong(),
                        (clusterSize - within).toLong()
                    ).toInt()
                    val buf = ByteArray(clusterSize)
                    val n = source.read(boot.clusterByte(cluster), buf, 0, buf.size)
                    if (n <= within.toInt()) break
                    val copy = minOf(chunkLen, n - within.toInt())
                    System.arraycopy(buf, within.toInt(), dst, dstPos + written, copy)
                    written += copy
                    if (copy < chunkLen) break
                }
            }
            filePos = clusterEnd
            cluster = if (noFatChain) (cluster + 1) else nextCluster(cluster)
            if (!noFatChain && isEof(cluster)) break
        }
        return written
    }

    /** FAT entry for [cluster], advancing the chain. */
    private fun nextCluster(cluster: Long): Long {
        val fatByte = boot.fatStartByte + cluster * FAT_ENTRY_SIZE
        val e = ByteArray(FAT_ENTRY_SIZE)
        if (source.read(fatByte, e, 0, FAT_ENTRY_SIZE) < FAT_ENTRY_SIZE) return BAD_CLUSTER
        val v = le32(e, 0)
        return if (isEof(v)) v else (v and 0x0fffffffL)
    }

    private fun isEof(v: Long): Boolean = v >= EOF_MARK || v == BAD_CLUSTER
    private fun isValidCluster(c: Long): Boolean = c >= 2 && c < 0x0ffffff0L

    private fun le32(b: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 0 until 4) v = v or (((b[off + i].toLong() and 0xff) shl (8 * i)))
        return v
    }

    private fun le64(b: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = v or (((b[off + i].toLong() and 0xff) shl (8 * i)))
        return v
    }
}
