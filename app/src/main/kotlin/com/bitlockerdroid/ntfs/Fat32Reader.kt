package com.bitlockerdroid.ntfs

import com.bitlockerdroid.ntfs.Fat32Volume.BootSector
import java.util.concurrent.ConcurrentHashMap

/**
 * Read-only FAT32 volume navigator. Lists directories through the FAT cluster
 * chains and streams file data by following the same chains.
 *
 * All byte offsets are physical (see [Fat32Volume.BootSector]) and handed to
 * the block source, which decrypts at the physical address.
 *
 * Directory listings populate an entry cache keyed by first cluster, so that
 * later [readEntry] calls (single-document queries / openDocument) can resolve
 * name, type and size without re-walking the parent directory.
 */
class Fat32Reader(
    private val source: NtfsBlockSource,
    private val boot: BootSector,
    private val bootBytes: ByteArray = ByteArray(0)
) : VolumeReader {

    companion object {
        private const val DIR_ENTRY_SIZE = 32
        private const val ATTR_DIRECTORY = 0x10
        private const val ATTR_LONG_NAME = 0x0f
        private const val ATTR_VOLUME_ID = 0x08
        private const val EOF_MARK = 0x0ffffff8L
        private const val BAD_CLUSTER = 0x0ffffff7L
    }

    override val rootRef: Long get() = boot.rootCluster

    override fun volumeSerial(): Long {
        // FAT32 volume serial number lives in the boot sector at offset 0x43
        // (little-endian u32). Prefer the boot bytes captured at unlock time;
        // fall back to reading the boot sector through the block source.
        if (bootBytes.size >= 0x47) return le32(bootBytes, 0x43)
        val sector = ByteArray(512)
        if (source.read(boot.fatStartByte - boot.reservedSectors.toLong() * boot.bytesPerSector, sector, 0, 512) < 512) return 0L
        return le32(sector, 0x43)
    }

    override fun volumeLabel(): String? {
        // FAT32 volume label is the first directory entry in the root dir,
        // with attribute VOLUME_ID (0x08). The name is 11 raw ASCII bytes.
        // Read a whole sector: the block source decrypts per sector, so reads
        // must be sector-aligned (the native core rejects non-aligned lengths).
        val sector = ByteArray(512)
        if (source.read(boot.clusterByte(rootRef), sector, 0, 512) < 512) return null
        val attr = sector[11].toInt() and 0xff
        if (attr != ATTR_VOLUME_ID) return null
        val name = StringBuilder()
        for (i in 0 until 11) {
            val c = sector[i].toInt() and 0xff
            if (c == 0 || c == 0x20) break
            name.append(c.toChar())
        }
        return name.toString().ifEmpty { null }
    }

    /** first cluster -> entry metadata, filled by [listDirectory]. */
    private val entryCache = ConcurrentHashMap<Long, VolumeEntry>()

    override fun invalidateCache() {
        entryCache.clear()
    }

    override fun readEntry(ref: Long): VolumeEntry? {
        entryCache[ref]?.let { return it }

        // The root directory is not prefixed by a '.' entry, so special-case it.
        if (ref == rootRef) {
            val entry = VolumeEntry(ref, isDirectory = true, fileName = null, fileSize = 0L)
            entryCache[ref] = entry
            return entry
        }

        // Cache miss: directory may have been invalidated after a write. Refresh by reading root directory.
        try {
            listDirectory(rootRef)
            entryCache[ref]?.let { return it }
        } catch (_: Exception) {}

        // If this is a position-based synthetic ref (high bit 0x40):
        if ((ref and 0x4000000000000000L) != 0L) {
            val cluster = (ref ushr 24) and 0xFFFFFFL
            if (isValidCluster(cluster)) {
                try {
                    listDirectory(cluster)
                    entryCache[ref]?.let { return it }
                } catch (_: Exception) {}
            }
        }

        if (!isValidCluster(ref)) return null

        // Cache miss (e.g. a directory that was never listed). Detect a
        // directory by the leading '.' entry of a directory cluster; anything
        // else is treated as a file (size unknown without the parent listing).
        val first = ByteArray(DIR_ENTRY_SIZE)
        if (source.read(boot.clusterByte(ref), first, 0, DIR_ENTRY_SIZE) < DIR_ENTRY_SIZE) return null
        val isDir = (first[11].toInt() and ATTR_DIRECTORY) != 0 &&
            (first[0].toInt() and 0xff) == '.'.code
        if (isDir) {
            val entry = VolumeEntry(ref, true, null, 0L)
            entryCache[ref] = entry
            return entry
        }
        return null
    }

    /**
     * Lists children of the directory whose first cluster is [ref].
     * Directory data may span several clusters linked through the FAT.
     */
    override fun listDirectory(ref: Long): List<VolumeDirEntry> {
        val out = ArrayList<VolumeDirEntry>()
        if (!isValidCluster(ref)) return out

        val clusterSize = boot.clusterSize.toInt()
        val buf = ByteArray(clusterSize)
        var cluster = ref
        var guard = 0
        while (isValidCluster(cluster) && guard++ < 100000) {
            val n = source.read(boot.clusterByte(cluster), buf, 0, buf.size)
            if (n < DIR_ENTRY_SIZE) break
            val endOfDir = parseDirCluster(buf, n, out, cluster)
            if (endOfDir) break
            cluster = nextCluster(cluster)
            if (isEof(cluster)) break
        }
        return out
    }

    /** Parses one directory cluster into [out], assembling LFN entries. Returns true if 0x00 end-of-directory was hit. */
    private fun parseDirCluster(buf: ByteArray, len: Int, out: MutableList<VolumeDirEntry>, cluster: Long): Boolean {
        var pendingLfn = ArrayList<String>()
        var pos = 0
        while (pos + DIR_ENTRY_SIZE <= len) {
            val e = buf.copyOfRange(pos, pos + DIR_ENTRY_SIZE)
            val first = e[0].toInt() and 0xff
            if (first == 0x00) return true // end of directory
            if (first == 0xe5) { pendingLfn.clear(); pos += DIR_ENTRY_SIZE; continue } // deleted
            val attr = e[11].toInt() and 0xff

            if (attr == ATTR_LONG_NAME) {
                // If this is the start of a new LFN entry chain (bit 0x40 is set), reset previous fragments
                if ((first and 0x40) != 0) {
                    pendingLfn.clear()
                }
                val chunk = String(e, 1, 10, Charsets.UTF_16LE) +
                    String(e, 14, 12, Charsets.UTF_16LE) +
                    String(e, 28, 4, Charsets.UTF_16LE)
                pendingLfn.add(0, chunk)
                pos += DIR_ENTRY_SIZE
                continue
            }

            // 8.3 entry — the LFN (if any) belongs to this one.
            val short = shortName(e)
            val lfn = if (pendingLfn.isNotEmpty()) {
                val full = pendingLfn.joinToString("")
                pendingLfn.clear()
                val nullIdx = full.indexOf('\u0000')
                val s = if (nullIdx >= 0) full.substring(0, nullIdx) else full
                s.trim('\uffff', ' ')
            } else short
            val name = lfn.ifEmpty { short }

            // Skip '.' and '..' (self / parent links) like Windows Explorer.
            if (name == "." || name == "..") { pos += DIR_ENTRY_SIZE; continue }

            val clusterHi = le16(e, 20)
            val clusterLo = le16(e, 26)
            val firstCluster = ((clusterHi.toLong() shl 16) or clusterLo.toLong())
            val size = le32(e, 28)
            val isDir = (attr and ATTR_DIRECTORY) != 0

            // Skip volume-label entries (attr only VOLUME_ID, cluster 0).
            if (attr == ATTR_VOLUME_ID) { pos += DIR_ENTRY_SIZE; continue }

            val writeTime = le16(e, 22)
            val writeDate = le16(e, 24)
            val lastModified = VolumeTimestampUtil.dosDateTimeToMillis(writeDate, writeTime)

            val posRef = 0x4000000000000000L or ((cluster and 0xFFFFFFL) shl 24) or (pos.toLong() and 0xFFFFFFL)
            val ref = if (firstCluster >= 2) firstCluster else posRef
            val entry = VolumeEntry(ref, isDir, name, size, lastModified)
            entryCache[ref] = entry
            entryCache[posRef] = entry
            if (firstCluster >= 2) {
                entryCache[firstCluster] = entry
            }
            out.add(VolumeDirEntry(name, ref, isDir, size, lastModified))
            pos += DIR_ENTRY_SIZE
        }
        return false
    }

    /**
     * Reads file content for the cluster chain starting at [ref] into [dst].
     * Returns bytes copied. If the file size is not cached (e.g. the parent
     * directory was never listed in this process), reads the chain until EOF.
     */
    override fun readFile(ref: Long, offset: Long, dst: ByteArray, dstPos: Int, len: Int): Int {
        if (!isValidCluster(ref) || len <= 0) return 0
        val clusterSize = boot.clusterSize.toInt()
        val cachedSize = entryCache[ref]?.fileSize
        // If size is known and the request starts past it, nothing to read.
        if (cachedSize != null && offset >= cachedSize) return 0
        val toRead = minOf(
            len.toLong(),
            if (cachedSize != null) cachedSize - offset else len.toLong()
        ).toInt()

        var filePos = 0L
        var cluster = ref
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
            cluster = nextCluster(cluster)
        }
        return written
    }

    /** FAT entry for [cluster], advancing the chain. */
    private fun nextCluster(cluster: Long): Long {
        val fatByte = boot.fatStartByte + cluster * 4
        val e = ByteArray(4)
        if (source.read(fatByte, e, 0, 4) < 4) return BAD_CLUSTER
        val v = le32(e, 0)
        return if (isEof(v)) v else (v and 0x0fffffffL)
    }

    private fun isEof(v: Long): Boolean = v >= EOF_MARK || v == BAD_CLUSTER
    private fun isValidCluster(c: Long): Boolean = c >= 2 && c < 0x0ffffff0L

    /** 8.3 short name (uppercase, no path). */
    private fun shortName(e: ByteArray): String {
        val baseRaw = String(e, 0, 8, Charsets.US_ASCII).trimEnd(' ', '\u0000')
        val extRaw = String(e, 8, 3, Charsets.US_ASCII).trimEnd(' ', '\u0000')
        // In FAT, 0x05 in first byte indicates actual character 0xE5 (Kanji lead byte)
        val base = if ((e[0].toInt() and 0xff) == 0x05 && baseRaw.isNotEmpty()) {
            "\u00e5" + baseRaw.substring(1)
        } else baseRaw
        return if (extRaw.isEmpty()) base else "$base.$extRaw"
    }

    private fun le16(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xff) or ((b[off + 1].toInt() and 0xff) shl 8)

    private fun le32(b: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 0 until 4) v = v or (((b[off + i].toLong() and 0xff) shl (8 * i)))
        return v
    }
}
