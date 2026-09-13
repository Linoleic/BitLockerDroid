package com.bitlockerdroid.ntfs

import com.bitlockerdroid.ntfs.NtfsVolume.BootSector
import com.bitlockerdroid.ntfs.NtfsVolume.le16
import com.bitlockerdroid.ntfs.NtfsVolume.le32
import com.bitlockerdroid.ntfs.NtfsVolume.le64

/**
 * Read-only NTFS volume navigator. Reads $MFT records via a [NtfsBlockSource],
 * resolves directory indexes through the MFT, and streams file data through
 * data runs.
 */
class NtfsReader(
    private val source: NtfsBlockSource,
    private val boot: BootSector
) : VolumeReader {

    companion object {
        const val MFT_RECORD_SIZE = 1024
        const val ROOT_DIR_RECORD = 5L // $MFT entry 5 is the root directory "\"
    }

    override val rootRef: Long get() = ROOT_DIR_RECORD

    override fun volumeSerial(): Long {
        // The NTFS volume serial lives in the boot sector at 0x48 (8 bytes
        // LE). $Volume's $VOLUME_INFORMATION (0x70) only carries version and
        // flags — it has no serial, so the old MFT scan always returned 0.
        return boot.serial
    }

    override fun volumeLabel(): String? {
        // $Volume MFT record (3) holds $VOLUME_NAME (0x60), a resident
        // UTF-16LE string (NUL-terminated).
        return readVolumeName(3L)
    }

    private fun readVolumeName(recordNumber: Long): String? {
        val bytePos = boot.mftStartByte + recordNumber * MFT_RECORD_SIZE
        val rec = ByteArray(MFT_RECORD_SIZE)
        if (source.read(bytePos, rec, 0, MFT_RECORD_SIZE) < 56) return null
        if (!NtfsFileRecordParser.applyUpdateSequenceArray(rec, 0x04, boot.bytesPerSector)) return null

        var attrOff = le16(rec, 20).toLong()
        while (attrOff > 0 && attrOff < rec.size - 16) {
            val type = le32(rec, attrOff.toInt())
            if (type == 0xffffffffL) break
            val length = le32(rec, attrOff.toInt() + 4).toInt()
            if (length < 16 || attrOff + length > rec.size) break
            if (type == 0x60L) {
                val nonResident = rec[attrOff.toInt() + 8].toInt() and 0xff
                if (nonResident == 0) {
                    val valueLen = le32(rec, attrOff.toInt() + 16).toInt()
                    val valueOff = le16(rec, attrOff.toInt() + 20)
                    val start = attrOff.toInt() + valueOff
                    if (start + valueLen <= rec.size && valueLen >= 2) {
                        // UTF-16LE, possibly NUL-terminated.
                        var end = start + valueLen
                        if (rec[end - 2].toInt() == 0 && rec[end - 1].toInt() == 0) end -= 2
                        return String(rec, start, end - start, Charsets.UTF_16LE)
                    }
                }
                return null
            }
            attrOff += length
        }
        return null
    }

    override fun readEntry(ref: Long): VolumeEntry? {
        val rec = readRecord(ref) ?: return null
        return VolumeEntry(
            ref = ref,
            isDirectory = rec.isDirectory,
            fileName = rec.fileName,
            fileSize = rec.fileSize
        )
    }

    private val cache = HashMap<Long, NtfsFileRecord?>()

    override fun invalidateCache() {
        synchronized(cache) {
            cache.clear()
        }
    }

    private fun readRecord(recordNumber: Long): NtfsFileRecord? {
        synchronized(cache) {
            if (cache.containsKey(recordNumber)) return cache[recordNumber]
        }

        val bytePos = boot.mftStartByte + recordNumber * MFT_RECORD_SIZE
        val rec = ByteArray(MFT_RECORD_SIZE)
        val n = source.read(bytePos, rec, 0, MFT_RECORD_SIZE)
        if (n < 56) {
            synchronized(cache) { cache[recordNumber] = null }
            return null
        }

        // Restore the Update Sequence Array before parsing: the tail 2 bytes
        // of every 512B sector inside the record are USN check values.
        if (!NtfsFileRecordParser.applyUpdateSequenceArray(rec, 0x04, boot.bytesPerSector)) {
            synchronized(cache) { cache[recordNumber] = null }
            return null
        }

        val record = NtfsFileRecordParser.parse(recordNumber, rec)
        synchronized(cache) { cache[recordNumber] = record }
        return record
    }

    /** Public accessor used by the DocumentsProvider. */
    fun readFileRecord(recordNumber: Long): NtfsFileRecord? = readRecord(recordNumber)

    /** Reconstructs the full path from the root directory to [recordNumber]. */
    fun resolvePath(recordNumber: Long): String {
        if (recordNumber == ROOT_DIR_RECORD) return "/"
        val parts = ArrayList<String>()
        var cur = recordNumber
        var depth = 0
        while (cur != ROOT_DIR_RECORD && depth < 32) {
            val rec = readRecord(cur) ?: break
            val name = rec.fileName ?: break
            parts.add(0, name)
            cur = rec.parentRecord
            depth++
        }
        return "/" + parts.joinToString("/")
    }

    /**
     * Lists directory entries for the MFT record [dirRecord].
     *
     * Parses the resident $INDEX_ROOT attribute, which contains the directory
     * index header + index entries (each a FILE_NAME index key). For large
     * directories, entries spill into non-resident $INDEX_ALLOCATION blocks,
     * which this read-only parser also follows.
     */
    override fun listDirectory(dirRecord: Long): List<VolumeDirEntry> {
        val out = ArrayList<VolumeDirEntry>()
        val record = readRecord(dirRecord) ?: return out

        // $INDEX_ROOT attribute value layout (resident):
        //   0x00: attribute type (u32) = $FILE_NAME (0x30)
        //   0x04: collation rule (u32)
        //   0x08: index block size (u32)
        //   0x0c: clusters per index block (u8)
        //   0x10: INDEX_HEADER (16 bytes)
        //   then index entries
        val root = record.attributes.firstOrNull { it.type == NtfsFileRecord.TYPE_INDEX_ROOT } as? IndexRootAttribute
            ?: run {
                android.util.Log.w("NtfsReader", "record $dirRecord has no INDEX_ROOT; attrs=${record.attributes.map { "0x%02x".format(it.type) }}")
                return out
            }
        val value = root.value
        android.util.Log.i("NtfsReader", "record $dirRecord INDEX_ROOT value size=${value.size}")
        if (value.size < 32) return out

        val indexBlockSize = le32(value, 8).toInt()

        // INDEX_HEADER at offset 0x10: first_entry_offset (u32), total entry
        // slots (u32), allocated (u32), not-allocated (u32)
        val entriesStart = 0x10 + le32(value, 0x10).toInt()
        parseIndexEntries(value, entriesStart, value.size, out)

        // Follow $INDEX_ALLOCATION for large directories
        for (a in record.attributes) {
            if (a is IndexAllocationAttribute && a.runs.isNotEmpty()) {
                val clusterSize = boot.clusterSize
                val block = ByteArray(indexBlockSize)
                for (run in a.runs) {
                    if (run.clusterOffset == 0L) continue
                    val n = source.read(run.clusterOffset * clusterSize, block, 0, block.size)
                    if (n < 24) continue
                    // Each index block starts with an INDEX_RECORD_HEADER:
                    //   0x00 magic "INDX", 0x18 INDEX_HEADER whose first field
                    //   (entries_offset) points to the first index entry.
                    if (le32(block, 0) != 0x58444e49L) continue
                    // INDX blocks are fixup-protected too (USA at 0x28/0x2A).
                    if (!NtfsFileRecordParser.applyUpdateSequenceArray(block, 0x04, boot.bytesPerSector)) continue
                    val firstEntry = 0x18 + le32(block, 0x18).toInt()
                    val entriesTotal = le32(block, 0x1c).toInt()
                    parseIndexEntries(block, firstEntry, firstEntry + entriesTotal, out)
                }
            }
        }

        return out
    }

    /** Parses index entries in [buf] from [start] to [end] into [out]. */
    private fun parseIndexEntries(buf: ByteArray, start: Int, end: Int, out: MutableList<VolumeDirEntry>) {
        var pos = start
        while (pos + 16 <= end) {
            // INDEX_ENTRY: file reference (u64) | entry length (u16) |
            // key length (u16) | flags (u16) | reserved (u16) | key (FILE_NAME)
            val fileRef = le64(buf, pos)
            val entryLength = le16(buf, pos + 8)
            if (entryLength == 0) break
            val keyLength = le16(buf, pos + 10)
            val flags = le16(buf, pos + 12)

            // 0x01 = has sub-node (skip); 0x02 = last entry. A normal entry has
            // a FILE_NAME key (keyLength > 0).
            val isLast = flags and 0x02 != 0
            if (keyLength > 0) {
                val keyStart = pos + 16
                // $FILE_NAME key is at least 66 bytes (10 header + 56 fixed + name)
                if (keyStart + 66 <= end) {
                    val fileNameLen = buf[keyStart + 64].toInt() and 0xff
                    val nameType = buf[keyStart + 65].toInt() and 0xff
                    // nameType: 0=POSIX, 1=Win32, 2=DOS, 3=Win32 & DOS.
                    // Skip 2 (DOS 8.3 alias) to avoid duplicate entries for long filenames.
                    if (fileNameLen > 0 && nameType != 2 &&
                        keyStart + 66 + fileNameLen * 2 <= end) {
                        val nb = buf.copyOfRange(keyStart + 66, keyStart + 66 + fileNameLen * 2)
                        val name = String(nb, Charsets.UTF_16LE)
                        if (name != "." && name != "..") {
                            // file reference: low 48 bits = MFT record number
                            val recNum = fileRef and 0x0000FFFFFFFFFFFFL
                            // Hide NTFS internal system metadata files (MFT 0..15, $*, System Volume Information)
                            val isSystemMeta = recNum < 16L || name.startsWith("$") || name.equals("System Volume Information", ignoreCase = true)
                            if (!isSystemMeta) {
                                // determine if directory by reading the record
                                val child = readRecord(recNum)
                                val isDir = child?.isDirectory ?: false
                                out.add(VolumeDirEntry(name, recNum, isDir, child?.fileSize ?: 0L))
                            }
                        }
                    }
                }
            }
            if (isLast) break
            pos += entryLength
        }
    }

    /**
     * Reads a file's bytes into [dst] at [dstPos]. Supports resident data and
     * non-resident data runs. Returns the number of bytes read.
     */
    override fun readFile(recordNumber: Long, offset: Long, dst: ByteArray, dstPos: Int, len: Int): Int {
        val record = readRecord(recordNumber) ?: return 0
        val clusterSize = boot.clusterSize

        // Resident content
        record.residentData()?.let { data ->
            if (offset >= data.size) return 0
            val n = minOf(len.toLong(), data.size - offset).toInt()
            System.arraycopy(data, offset.toInt(), dst, dstPos, n)
            return n
        }

        // Non-resident: walk data runs. Each run covers [runFileStart, runFileEnd)
        // bytes of the logical file, backed by clusters at run.clusterOffset.
        val runs = record.dataRuns() ?: return 0
        var filePos = offset
        var remaining = len.toLong()
        var runFileStart = 0L

        for (run in runs) {
            if (remaining <= 0) break
            if (run.clusterCount <= 0) {
                runFileStart += 0
                continue
            }
            val runBytes = run.clusterCount * clusterSize
            val runFileEnd = runFileStart + runBytes

            if (filePos >= runFileEnd) {
                runFileStart = runFileEnd
                continue
            }
            if (filePos + remaining <= runFileStart) break

            val within = filePos - runFileStart
            val toRead = minOf(remaining, runBytes - within)

            if (run.clusterOffset == 0L) {
                // Sparse run: logical zeros
                val dstOff = dstPos + (filePos - offset)
                java.util.Arrays.fill(dst, dstOff.toInt(), (dstOff + toRead).toInt(), 0.toByte())
            } else {
                var clusterIdx = within / clusterSize
                var byteIn = (within % clusterSize)
                var todo = toRead
                var dstOff = dstPos + (filePos - offset)

                while (todo > 0) {
                    val clusterBuf = ByteArray(clusterSize.toInt())
                    val n = source.read((run.clusterOffset + clusterIdx) * clusterSize, clusterBuf, 0, clusterBuf.size)
                    if (n <= 0) break

                    val fromBuf = minOf(todo, (n - byteIn).toLong()).toInt()
                    System.arraycopy(clusterBuf, byteIn.toInt(), dst, (dstOff + (toRead - todo)).toInt(), fromBuf)
                    todo -= fromBuf
                    byteIn = 0
                    clusterIdx++
                    if (n < clusterBuf.size && fromBuf < todo) break
                }
            }

            filePos += toRead
            remaining -= toRead
            runFileStart = runFileEnd
        }

        return (len - remaining).toInt()
    }
}
