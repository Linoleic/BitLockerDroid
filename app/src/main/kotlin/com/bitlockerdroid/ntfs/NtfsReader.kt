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

    private var cachedVolumeInfo: Pair<String?, Boolean>? = null

    override fun volumeLabel(): String? = readVolumeInformation().first

    override val isDirty: Boolean get() = readVolumeInformation().second

    private fun readVolumeInformation(): Pair<String?, Boolean> {
        cachedVolumeInfo?.let { return it }
        val bytePos = boot.mftStartByte + 3L * MFT_RECORD_SIZE
        val rec = ByteArray(MFT_RECORD_SIZE)
        if (source.read(bytePos, rec, 0, MFT_RECORD_SIZE) < 56) {
            val res = Pair<String?, Boolean>(null, false)
            cachedVolumeInfo = res
            return res
        }
        if (!NtfsFileRecordParser.applyUpdateSequenceArray(rec, 0x04, boot.bytesPerSector)) {
            val res = Pair<String?, Boolean>(null, false)
            cachedVolumeInfo = res
            return res
        }

        var label: String? = null
        var isDirty = false

        var attrOff = le16(rec, 20).toLong()
        while (attrOff > 0 && attrOff < rec.size - 16) {
            val type = le32(rec, attrOff.toInt())
            if (type == 0xffffffffL) break
            val length = le32(rec, attrOff.toInt() + 4).toInt()
            if (length < 16 || attrOff + length > rec.size) break
            val nonResident = rec[attrOff.toInt() + 8].toInt() and 0xff
            if (nonResident == 0) {
                val valueLen = le32(rec, attrOff.toInt() + 16).toInt()
                val valueOff = le16(rec, attrOff.toInt() + 20)
                val start = attrOff.toInt() + valueOff

                if (type == 0x60L) { // $VOLUME_NAME
                    if (start + valueLen <= rec.size && valueLen >= 2) {
                        var end = start + valueLen
                        if (rec[end - 2].toInt() == 0 && rec[end - 1].toInt() == 0) end -= 2
                        label = String(rec, start, end - start, Charsets.UTF_16LE)
                    }
                } else if (type == 0x70L) { // $VOLUME_INFORMATION
                    // Offset 8: major_ver (u8), 9: minor_ver (u8), 10: flags (u16 LE)
                    // Flags: 0x0001 = VOLUME_IS_DIRTY, 0x4000 = VOLUME_CHKDSK_UNDERWAY
                    if (start + 12 <= rec.size) {
                        val flags = le16(rec, start + 10)
                        if ((flags and 0x0001) != 0 || (flags and 0x4000) != 0) {
                            isDirty = true
                        }
                    }
                }
            }
            attrOff += length
        }
        val res = Pair(label, isDirty)
        cachedVolumeInfo = res
        return res
    }

    override fun readEntry(ref: Long): VolumeEntry? {
        val rec = readRecord(ref) ?: return null
        return VolumeEntry(
            ref = ref,
            isDirectory = rec.isDirectory,
            fileName = rec.fileName,
            fileSize = resolveFileSize(rec),
            lastModified = rec.lastModified
        )
    }

    /** Resolves all data runs for a file record, following $ATTRIBUTE_LIST if present. */
    private fun resolveDataRuns(record: NtfsFileRecord): List<DataRun>? {
        val directRuns = record.dataRuns()
        val attrList = record.attributeList
        if (attrList.isNullOrEmpty()) {
            return directRuns
        }

        val allRuns = ArrayList<DataRun>()
        if (directRuns != null) {
            allRuns.addAll(directRuns)
        }

        for (entry in attrList) {
            if (entry.type == NtfsFileRecord.TYPE_DATA && entry.name.isEmpty()) {
                val childRecNum = entry.mftReference and 0x0000FFFFFFFFFFFFL
                if (childRecNum != record.recordNumber) {
                    val childRec = readRecord(childRecNum)
                    childRec?.dataRuns()?.let { childRuns ->
                        allRuns.addAll(childRuns)
                    }
                }
            }
        }

        return if (allRuns.isNotEmpty()) allRuns else directRuns
    }

    /** Resolves the effective file size, following $ATTRIBUTE_LIST if primary size is 0. */
    private fun resolveFileSize(record: NtfsFileRecord): Long {
        val directSize = record.fileSize
        if (directSize > 0L) return directSize
        val attrList = record.attributeList ?: return directSize
        var maxSize = directSize
        for (entry in attrList) {
            if (entry.type == NtfsFileRecord.TYPE_DATA && entry.name.isEmpty()) {
                val childRecNum = entry.mftReference and 0x0000FFFFFFFFFFFFL
                if (childRecNum != record.recordNumber) {
                    val childRec = readRecord(childRecNum)
                    if (childRec != null && childRec.fileSize > maxSize) {
                        maxSize = childRec.fileSize
                    }
                }
            }
        }
        return maxSize
    }

    private val cache = HashMap<Long, NtfsFileRecord?>()

    override fun invalidateCache() {
        cachedVolumeInfo = null
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

    /**
     * Reconstructs the full path from the root directory to [recordNumber].
     * Returns null when the parent chain cannot be walked (unreadable record)
     * so callers can fall back to a directory search — returning "/" here
     * would silently route writes into the volume root.
     */
    fun resolvePath(recordNumber: Long): String? {
        if (recordNumber == ROOT_DIR_RECORD) return "/"
        val parts = ArrayList<String>()
        var cur = recordNumber
        var depth = 0
        while (cur != ROOT_DIR_RECORD && depth < 32) {
            val rec = readRecord(cur) ?: return null
            val name = rec.fileName ?: return null
            parts.add(0, name)
            cur = rec.parentRecord
            depth++
        }
        if (cur != ROOT_DIR_RECORD) return null
        return "/" + parts.joinToString("/")
    }

    /**
     * Lists directory entries for the MFT record [dirRecord].
     *
     * Parses the resident $INDEX_ROOT attribute (the B-tree root node), then
     * follows sub-node pointers breadth-first into the $INDEX_ALLOCATION
     * blocks (INDX records). Stream offsets map through the attribute's data
     * runs, so multi-block and multi-run allocations are both handled.
     */
    override fun listDirectory(dirRecord: Long): List<VolumeDirEntry> {
        val out = LinkedHashMap<String, VolumeDirEntry>()
        val record = readRecord(dirRecord) ?: return emptyList()

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
                return emptyList()
            }
        val value = root.value
        if (value.size < 32) return emptyList()

        val indexBlockSize = le32(value, 8).toInt()
        if (indexBlockSize <= 0) return emptyList()

        // INDEX_HEADER at offset 0x10: first_entry_offset (u32), total entry
        // slots (u32), allocated (u32), not-allocated (u32)
        val entriesStart = 0x10 + le32(value, 0x10).toInt()
        // Sub-node VCNs discovered while parsing, queued for BFS below.
        val pendingBlocks = ArrayDeque<Long>()
        parseIndexEntries(value, entriesStart, value.size, out, pendingBlocks)

        // Follow $INDEX_ALLOCATION for large directories.
        for (a in record.attributes) {
            if (a !is IndexAllocationAttribute || a.runs.isEmpty()) continue
            val clusterSize = boot.clusterSize
            val visitedBlocks = HashSet<Long>()
            val block = ByteArray(indexBlockSize)

            while (pendingBlocks.isNotEmpty()) {
                val vcn = pendingBlocks.removeFirst()
                // A sub-node VCN addresses one cluster of the index stream;
                // the INDX record starts at the containing index-block boundary.
                val streamByte = vcn * clusterSize
                val blockStart = streamByte / indexBlockSize * indexBlockSize
                if (!visitedBlocks.add(blockStart)) continue

                val phys = resolveStreamOffset(a.runs, blockStart, clusterSize) ?: continue
                val n = source.read(phys, block, 0, block.size)
                if (n < indexBlockSize) continue
                if (le32(block, 0) != 0x58444e49L) continue
                // INDX blocks are fixup-protected too (USA offset field at 0x04).
                if (!NtfsFileRecordParser.applyUpdateSequenceArray(block, 0x04, boot.bytesPerSector)) continue
                val firstEntry = 0x18 + le32(block, 0x18).toInt()
                val entriesTotal = le32(block, 0x1c).toInt()
                parseIndexEntries(block, firstEntry, firstEntry + entriesTotal, out, pendingBlocks)
            }
        }

        return out.values.toList()
    }

    /**
     * Resolves a byte offset within the $INDEX_ALLOCATION stream to a physical
     * volume offset by walking the attribute's data runs. Sparse runs resolve
     * to null (no INDX record lives in a hole). Returns null when [streamByte]
     * lies beyond the stream.
     */
    private fun resolveStreamOffset(runs: List<DataRun>, streamByte: Long, clusterSize: Long): Long? {
        var streamPos = 0L
        for (run in runs) {
            val runBytes = run.clusterCount * clusterSize
            if (streamByte < streamPos + runBytes) {
                if (run.clusterOffset == 0L) return null
                return run.clusterOffset * clusterSize + (streamByte - streamPos)
            }
            streamPos += runBytes
        }
        return null
    }

    /**
     * Parses index entries in [buf] from [start] to [end] into [out] (deduped
     * by case-insensitive name — a separator entry also appears as the real
     * entry inside its sub-node). Entries carrying a sub-node pointer push the
     * pointed-to VCN onto [subNodes] for B-tree traversal.
     */
    private fun parseIndexEntries(
        buf: ByteArray,
        start: Int,
        end: Int,
        out: LinkedHashMap<String, VolumeDirEntry>,
        subNodes: MutableList<Long>
    ) {
        var pos = start
        while (pos + 16 <= end) {
            // INDEX_ENTRY: file reference (u64) | entry length (u16) |
            // key length (u16) | flags (u16) | reserved (u16) | key (FILE_NAME)
            val fileRef = le64(buf, pos)
            val entryLength = le16(buf, pos + 8)
            if (entryLength == 0) break
            val keyLength = le16(buf, pos + 10)
            val flags = le16(buf, pos + 12)

            // 0x01 = has sub-node (B-tree child); 0x02 = last entry.
            if (flags and 0x01 != 0 && entryLength >= 24) {
                // Sub-node VCN occupies the trailing 8 bytes of the entry.
                subNodes.add(le64(buf, pos + entryLength - 8))
            }
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
                            val hideSvi = try { com.bitlockerdroid.util.PreferenceHelper.hideSviFolder } catch (_: Throwable) { true }
                            val isSystemMeta = recNum < 16L || name.startsWith("$") || (hideSvi && name.equals("System Volume Information", ignoreCase = true))
                            if (!isSystemMeta) {
                                val dedupeKey = name.lowercase()
                                if (!out.containsKey(dedupeKey)) {
                                    // determine if directory by reading the record
                                    val child = readRecord(recNum)
                                    val isDir = child?.isDirectory ?: false
                                    val idxTime = if (keyStart + 24 <= end) VolumeTimestampUtil.filetimeToMillis(le64(buf, keyStart + 16)) else 0L
                                    val childTime = child?.lastModified ?: 0L
                                    val modTime = if (childTime > 0L) childTime else idxTime
                                    val effectiveSize = child?.let { resolveFileSize(it) } ?: 0L
                                    out[dedupeKey] = VolumeDirEntry(name, recNum, isDir, effectiveSize, modTime)
                                }
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

        // Non-resident: walk data runs. Supports fragmented data runs distributed across $ATTRIBUTE_LIST.
        val runs = resolveDataRuns(record) ?: return 0
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
