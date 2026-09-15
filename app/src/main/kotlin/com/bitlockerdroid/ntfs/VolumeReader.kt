package com.bitlockerdroid.ntfs

/**
 * Read-only volume navigator abstraction shared by the NTFS and FAT32 parsers.
 *
 * The DocumentsProvider talks to this interface only, so a volume can be
 * served by whichever parser its boot sector requires.
 *
 * A document is addressed by a [ref] that is opaque to callers — for NTFS it
 * is the MFT record number, for FAT32 the first cluster of a directory or
 * file. The provider encodes [ref] into document IDs.
 */
interface VolumeReader {
    /** Root document reference (NTFS: MFT record 5, FAT32: root cluster). */
    val rootRef: Long

    /**
     * Reads a single directory / file record identified by [ref].
     * Returns null if the ref does not resolve to a valid entry.
     */
    fun readEntry(ref: Long): VolumeEntry?

    /** Lists the children of the directory identified by [ref]. */
    fun listDirectory(ref: Long): List<VolumeDirEntry>

    /**
     * Reads file content into [dst] at [dstPos], starting [offset] bytes into
     * the file identified by [ref]. Returns bytes copied, or 0 if none.
     */
    fun readFile(ref: Long, offset: Long, dst: ByteArray, dstPos: Int, len: Int): Int

    /** Volume label (e.g. "NO NAME"), or null if the volume has none. */
    fun volumeLabel(): String?

    /** Volume serial number — unique per formatted volume. Used in document
     *  IDs so that swapping to a different drive (even one reusing the same
     *  vold node path) yields fresh document IDs and the file manager reloads. */
    fun volumeSerial(): Long

    /** Returns true if the volume has a dirty bit set (e.g. unmounted uncleanly from Windows). */
    val isDirty: Boolean get() = false

    /** Invalidates any in-memory cached directory or entry structures. */
    fun invalidateCache() {}
}

/** Metadata of a directory or file record (used by the provider). */
class VolumeEntry(
    val ref: Long,
    val isDirectory: Boolean,
    val fileName: String?,
    val fileSize: Long,
    val lastModified: Long = 0L
)

/** A child entry returned by [VolumeReader.listDirectory]. */
class VolumeDirEntry(
    val name: String,
    val ref: Long,
    val isDirectory: Boolean,
    val size: Long = 0L,
    val lastModified: Long = 0L
)

object VolumeTimestampUtil {
    fun dosDateTimeToMillis(dosDate: Int, dosTime: Int): Long {
        if (dosDate == 0) return 0L
        val year = ((dosDate shr 9) and 0x7f) + 1980
        val month = ((dosDate shr 5) and 0x0f).coerceIn(1, 12)
        val day = (dosDate and 0x1f).coerceIn(1, 31)
        val hour = ((dosTime shr 11) and 0x1f).coerceIn(0, 23)
        val minute = ((dosTime shr 5) and 0x3f).coerceIn(0, 59)
        val second = ((dosTime and 0x1f) * 2).coerceIn(0, 59)

        val cal = java.util.Calendar.getInstance()
        cal.set(year, month - 1, day, hour, minute, second)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    fun filetimeToMillis(filetime: Long): Long {
        if (filetime <= 116444736000000000L) return 0L
        return (filetime - 116444736000000000L) / 10000L
    }
}
