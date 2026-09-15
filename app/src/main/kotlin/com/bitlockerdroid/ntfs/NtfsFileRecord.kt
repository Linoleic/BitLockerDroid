package com.bitlockerdroid.ntfs

import com.bitlockerdroid.ntfs.NtfsVolume.le16
import com.bitlockerdroid.ntfs.NtfsVolume.le32
import com.bitlockerdroid.ntfs.NtfsVolume.le64

/**
 * NTFS MFT file record: attributes ($STANDARD_INFORMATION, $FILE_NAME,
 * $DATA) and the data-run map used to read file content.
 *
 * Read-only: only the attributes we need are parsed.
 */
class NtfsFileRecord(
    val recordNumber: Long,
    val attributes: List<NtfsAttribute>
) {

    val fileName: String?
        get() {
            for (a in attributes) {
                if (a.type == TYPE_FILE_NAME && a is FileNameAttribute) return a.name
            }
            return null
        }

    val parentRecord: Long
        get() {
            for (a in attributes) {
                if (a.type == TYPE_FILE_NAME && a is FileNameAttribute) return a.parentRecord
            }
            return 5L
        }

    val isDirectory: Boolean
        get() = attributes.any { it.type == TYPE_INDEX_ROOT }

    val lastModified: Long
        get() {
            for (a in attributes) {
                if (a.type == TYPE_STANDARD_INFO && a is StandardInfoAttribute) {
                    if (a.lastModifiedTime > 0L) return a.lastModifiedTime
                }
            }
            for (a in attributes) {
                if (a.type == TYPE_FILE_NAME && a is FileNameAttribute) {
                    if (a.lastModifiedTime > 0L) return a.lastModifiedTime
                }
            }
            return 0L
        }

    val fileSize: Long
        get() {
            for (a in attributes) {
                when (a) {
                    is NonResidentDataAttribute -> return a.size
                    is ResidentDataAttribute -> return a.data.size.toLong()
                }
            }
            return 0
        }

    /** Returns the first $DATA attribute data-run, or null if resident/empty. */
    fun dataRuns(): List<DataRun>? {
        for (a in attributes) {
            if (a.type == TYPE_DATA && a is NonResidentDataAttribute) return a.runs
        }
        return null
    }

    /** Resident file content (small files stored inline in the record). */
    fun residentData(): ByteArray? {
        for (a in attributes) {
            if (a.type == TYPE_DATA && a is ResidentDataAttribute) return a.data
        }
        return null
    }

    /** Attribute list entries ($ATTRIBUTE_LIST, 0x20), if present. */
    val attributeList: List<AttributeListEntry>?
        get() {
            for (a in attributes) {
                if (a is AttributeListAttribute) return a.entries
            }
            return null
        }

    companion object {
        const val TYPE_STANDARD_INFO = 0x10
        const val TYPE_ATTRIBUTE_LIST = 0x20
        const val TYPE_FILE_NAME = 0x30
        const val TYPE_INDEX_ROOT = 0x90
        const val TYPE_INDEX_ALLOCATION = 0xa0
        const val TYPE_BITMAP = 0xb0
        const val TYPE_DATA = 0x80

        const val FILE_RECORD_MAGIC = 0x454c4946L // "FILE"
    }
}

/** An entry in $ATTRIBUTE_LIST (0x20). */
class AttributeListEntry(
    val type: Int,
    val length: Int,
    val lowestVcn: Long,
    val mftReference: Long,
    val instance: Int,
    val name: String
)

/** Attribute list attribute ($ATTRIBUTE_LIST, 0x20). */
class AttributeListAttribute(
    type: Int,
    val entries: List<AttributeListEntry>
) : NtfsAttribute(type)

/** Standard information attribute ($STANDARD_INFORMATION). */
class StandardInfoAttribute(
    type: Int,
    val creationTime: Long = 0L,
    val lastModifiedTime: Long = 0L
) : NtfsAttribute(type)

/** A file name from a $FILE_NAME attribute (UTF-16LE). */
class FileNameAttribute(
    type: Int,
    val name: String,
    val parentRecord: Long = 5L,
    val lastModifiedTime: Long = 0L
) : NtfsAttribute(type)

/** $DATA attribute with resident content. */
class ResidentDataAttribute(
    type: Int,
    val data: ByteArray
) : NtfsAttribute(type)

/** $DATA attribute stored non-resident (via data runs). */
class NonResidentDataAttribute(
    type: Int,
    val size: Long,
    val runs: List<DataRun>
) : NtfsAttribute(type)

/** Index root (directory marker) attribute. */
class IndexRootAttribute(
    type: Int,
    /** Raw value bytes of the $INDEX_ROOT attribute (resident). */
    val value: ByteArray
) : NtfsAttribute(type)

/** Non-resident $INDEX_ALLOCATION attribute (large directories). */
class IndexAllocationAttribute(
    type: Int,
    val runs: List<DataRun>,
    val indexBlockSize: Int
) : NtfsAttribute(type)

/** A data run: starting cluster (relative to previous run) + length in clusters. */
data class DataRun(
    val clusterOffset: Long,
    val clusterCount: Long
)

open class NtfsAttribute(val type: Int)

/**
 * Parses a raw 1024-byte MFT file record.
 *
 * Returns null if the record is not valid ("FILE" magic, in-use, not a
 * self-relocating record). Attributes are parsed until the end marker (0xFFFFFFFF).
 */
object NtfsFileRecordParser {

    const val ATTRIBUTE_END = 0xffffffffL

    /**
     * Applies the NTFS Update Sequence Array (fixup) to a protected
     * multi-sector structure before parsing.
     *
     * Both "FILE" (MFT record) and "INDX" (index block) start with the shared
     * multi-sector header: magic @0x00, USA offset field @0x04, USA count
     * field @0x06 — the field VALUES (typically 0x30 / 0x28) point at the
     * array itself, not the other way around.
     *
     * The last 2 bytes of every sector are replaced by a check value whose
     * original bytes are stored in the USA; without restoring them, entry
     * headers and attribute data that land on a sector tail are garbage.
     *
     * Returns false when a sector check value mismatches (corrupt record).
     */
    fun applyUpdateSequenceArray(rec: ByteArray, usaOffsetFieldPos: Int, sectorSize: Int): Boolean {
        if (sectorSize <= 0 || rec.size < usaOffsetFieldPos + 4) return true
        val usaOffset = le16(rec, usaOffsetFieldPos).toInt()
        val usaCount = le16(rec, usaOffsetFieldPos + 2).toInt()
        // Sanity: the array must fit inside the record and hold exactly one
        // check value + one fixup per sector.
        if (usaOffset <= 0 || usaCount != rec.size / sectorSize + 1 ||
            usaOffset + usaCount * 2 > rec.size) return true
        val checkValue = le16(rec, usaOffset)
        for (i in 1 until usaCount) {
            val sectorEnd = i * sectorSize - 2
            if (sectorEnd < 0 || sectorEnd + 2 > rec.size) break
            if (le16(rec, sectorEnd) != checkValue) return false
            val saved = le16(rec, usaOffset + i * 2)
            rec[sectorEnd] = (saved and 0xff).toByte()
            rec[sectorEnd + 1] = ((saved shr 8) and 0xff).toByte()
        }
        return true
    }

    fun parse(recordNumber: Long, rec: ByteArray): NtfsFileRecord? {
        if (rec.size < 56) return null
        if (le32(rec, 0) != NtfsFileRecord.FILE_RECORD_MAGIC) return null

        // flags at offset 22 (u16): 0x01 = in use
        val flags = le16(rec, 22)
        if (flags and 0x01 == 0) return null

        // first attribute offset (u16 at 0x14 = offset 20)
        var attrOff = le16(rec, 20).toLong()
        val attrs = ArrayList<NtfsAttribute>(8)

        while (attrOff > 0 && attrOff < rec.size - 16) {
            val type = le32(rec, attrOff.toInt())
            if (type == ATTRIBUTE_END) break

            val length = le32(rec, attrOff.toInt() + 4).toInt()
            if (length < 16 || attrOff + length > rec.size) break

            // NTFS attribute header (resident layout):
            //   0x00 type (u32), 0x04 length (u32), 0x08 non-resident (u8),
            //   0x09 name length (u8), 0x0a name offset (u16), 0x0c flags (u16),
            //   0x0e attribute id (u16), 0x10 value length (u32), 0x14 value offset (u16)
            val nonResident = rec[attrOff.toInt() + 8].toInt() and 0xff
            val nameLen = rec[attrOff.toInt() + 9].toInt() and 0xff
            val nameOff = le16(rec, attrOff.toInt() + 10)
            val valueLen = le32(rec, attrOff.toInt() + 16).toInt()
            val valueOff = le16(rec, attrOff.toInt() + 20)

            val name = if (nameLen > 0) {
                val bo = attrOff.toInt() + nameOff
                val nameBytes = rec.copyOfRange(bo, bo + nameLen * 2)
                String(nameBytes, Charsets.UTF_16LE)
            } else ""

            when (type.toInt()) {
                NtfsFileRecord.TYPE_STANDARD_INFO -> {
                    val valuePos = attrOff.toInt() + valueOff.toInt()
                    if (valuePos >= 0 && valuePos + 16 <= rec.size) {
                        val cTime = VolumeTimestampUtil.filetimeToMillis(le64(rec, valuePos))
                        val mTime = VolumeTimestampUtil.filetimeToMillis(le64(rec, valuePos + 8))
                        attrs.add(StandardInfoAttribute(type.toInt(), cTime, mTime))
                    }
                }
                NtfsFileRecord.TYPE_ATTRIBUTE_LIST -> {
                    if (nonResident == 0) {
                        val valuePos = attrOff.toInt() + valueOff.toInt()
                        if (valuePos >= 0 && valuePos + valueLen <= rec.size) {
                            val entries = parseAttributeList(rec, valuePos, valueLen)
                            attrs.add(AttributeListAttribute(type.toInt(), entries))
                        }
                    }
                }
                NtfsFileRecord.TYPE_FILE_NAME -> {
                    val valuePos = attrOff.toInt() + valueOff.toInt()
                    if (valuePos >= 0 && valuePos + valueLen.toInt() <= rec.size) {
                        val parentRef = le64(rec, valuePos) and 0x0000ffffffffffffL
                        val mTime = if (valuePos + 24 <= rec.size) {
                            VolumeTimestampUtil.filetimeToMillis(le64(rec, valuePos + 16))
                        } else 0L
                        val name = parseFileName(rec, valuePos, valueLen.toInt())
                        if (name != null) attrs.add(FileNameAttribute(type.toInt(), name, parentRef, mTime))
                    }
                }
                NtfsFileRecord.TYPE_DATA -> {
                    if (nonResident == 0) {
                        val valuePos = attrOff.toInt() + valueOff.toInt()
                        if (valuePos >= 0 && valuePos + valueLen.toInt() <= rec.size) {
                            val data = rec.copyOfRange(valuePos, valuePos + valueLen.toInt())
                            attrs.add(ResidentDataAttribute(type.toInt(), data))
                        }
                    } else {
                        parseNonResident(rec, attrOff.toInt(), length)?.let {
                            attrs.add(it)
                        }
                    }
                }
                NtfsFileRecord.TYPE_INDEX_ROOT -> {
                    val valuePos = attrOff.toInt() + valueOff.toInt()
                    if (valuePos + valueLen.toInt() <= rec.size) {
                        val value = rec.copyOfRange(valuePos, valuePos + valueLen.toInt())
                        attrs.add(IndexRootAttribute(type.toInt(), value))
                    }
                }
                NtfsFileRecord.TYPE_INDEX_ALLOCATION -> {
                    if (nonResident != 0) {
                        val lowestVcn = le64(rec, attrOff.toInt() + 16)
                        val mappingPairsOff = le16(rec, attrOff.toInt() + 32)
                        val runs = parseDataRuns(rec, attrOff.toInt() + mappingPairsOff, attrOff.toInt() + length)
                        // index block size is in the index root; default 4096
                        attrs.add(IndexAllocationAttribute(type.toInt(), runs, 4096))
                    }
                }
            }

            attrOff += length
        }

        return NtfsFileRecord(recordNumber, attrs)
    }

    private fun parseAttributeList(rec: ByteArray, start: Int, length: Int): List<AttributeListEntry> {
        val entries = ArrayList<AttributeListEntry>()
        var pos = start
        val end = start + length
        while (pos + 26 <= end) {
            val entryType = le32(rec, pos).toInt()
            val entryLen = le16(rec, pos + 4)
            if (entryLen < 26 || pos + entryLen > end) break
            val nameLen = rec[pos + 6].toInt() and 0xff
            val nameOff = rec[pos + 7].toInt() and 0xff
            val lowestVcn = le64(rec, pos + 8)
            val mftRef = le64(rec, pos + 16)
            val instance = le16(rec, pos + 24)
            val name = if (nameLen > 0 && pos + nameOff + nameLen * 2 <= pos + entryLen) {
                String(rec, pos + nameOff, nameLen * 2, Charsets.UTF_16LE)
            } else ""

            entries.add(
                AttributeListEntry(
                    type = entryType,
                    length = entryLen,
                    lowestVcn = lowestVcn,
                    mftReference = mftRef,
                    instance = instance,
                    name = name
                )
            )
            pos += entryLen
        }
        return entries
    }

    private fun parseFileName(rec: ByteArray, valuePos: Int, valueLen: Int): String? {
        if (valueLen < 66) return null // $FILE_NAME is 66+ bytes
        val nameLen = rec[valuePos + 64].toInt() and 0xff
        if (nameLen == 0) return null
        val nameType = rec[valuePos + 65].toInt() and 0xff
        // Skip DOS 8.3 name types (0x01), prefer normal names
        if (nameType == 1) return null
        val start = valuePos + 66
        val nameBytes = rec.copyOfRange(start, start + nameLen * 2)
        return String(nameBytes, Charsets.UTF_16LE)
    }

    private fun parseNonResident(rec: ByteArray, attrPos: Int, length: Int): NonResidentDataAttribute? {
        if (length < 64) return null
        // non-resident header: offset 24 = lowest VCN, 32 = highest VCN, 40 = mapping pairs offset
        val lowestVcn = le64(rec, attrPos + 16)
        val highestVcn = le64(rec, attrPos + 24)
        val mappingPairsOff = le16(rec, attrPos + 32)
        val allocatedSize = le64(rec, attrPos + 40)
        val realSize = le64(rec, attrPos + 48)

        if (mappingPairsOff <= 0 || mappingPairsOff >= length) return null

        val runs = parseDataRuns(rec, attrPos + mappingPairsOff, attrPos + length)
        return NonResidentDataAttribute(
            type = NtfsFileRecord.TYPE_DATA,
            size = realSize,
            runs = runs
        )
    }

    /**
     * Parses the NTFS mapping-pairs data run list.
     * Format: 1 byte header = (offset_size << 4) | length_size.
     * Followed by length bytes (LE) then offset bytes (LE, signed). Offset is
     * relative to the previous run's starting cluster.
     */
    fun parseDataRuns(rec: ByteArray, start: Int, end: Int): List<DataRun> {
        val runs = ArrayList<DataRun>()
        var pos = start
        var lastCluster = 0L
        var totalClusters = 0L

        while (pos < end) {
            val header = rec[pos].toInt() and 0xff
            if (header == 0) break // terminator
            // NTFS: high nibble = offset field size, low nibble = length field size
            val offSize = (header shr 4) and 0x0f
            val lenSize = header and 0x0f

            pos++
            if (pos + lenSize > end) break
            var clusterCount = 0L
            for (i in 0 until lenSize) {
                clusterCount = clusterCount or ((rec[pos + i].toLong() and 0xff) shl (8 * i))
            }
            pos += lenSize

            if (offSize == 0) {
                // sparse run: offset 0 means clusters are not allocated (zeros)
                runs.add(DataRun(0, clusterCount))
                totalClusters += clusterCount
                continue
            }

            if (pos + offSize > end) break
            // signed little-endian offset
            var raw = 0L
            val signByte = rec[pos + offSize - 1].toLong()
            for (i in 0 until offSize) {
                raw = raw or ((rec[pos + i].toLong() and 0xff) shl (8 * i))
            }
            if (offSize < 8 && (signByte and 0x80) != 0L) {
                // sign-extend
                raw = raw or ((-1L) shl (8 * offSize))
            }
            pos += offSize

            lastCluster += raw
            runs.add(DataRun(lastCluster, clusterCount))
            totalClusters += clusterCount
        }
        return runs
    }
}
