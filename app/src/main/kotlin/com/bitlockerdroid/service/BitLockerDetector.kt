package com.bitlockerdroid.service

import android.content.Context
import android.util.Log
import com.bitlockerdroid.util.LogFile
import com.bitlockerdroid.util.RootAccess

/**
 * Detects BitLocker volumes on mounted / connected block devices.
 *
 * Reads block devices via `su` (KernelSU/Magisk) because SELinux blocks both
 * unprivileged apps and system_server from opening block_device nodes.
 */
object BitLockerDetector {

    private const val TAG = "BitLockerDetector"

    /**
     * App-side scan via su: enumerates /dev/block/vold and reads signatures as
     * root, then triggers the unlock flow for BitLocker matches.
     * Returns the number of BitLocker volumes found.
     */
    data class BitLockerHeaderInfo(
        val signature: String,
        val guid: String?
    )

    /**
     * App-side scan via su: enumerates /dev/block/vold and reads signatures & volume GUIDs as
     * root, then triggers the unlock flow for BitLocker matches.
     * Returns the number of BitLocker volumes found.
     */
    fun scanAndDetect(context: Context): Int {
        if (!RootAccess.hasSu()) {
            LogFile.write("app", "scanAndDetect: su not available (not granted root?)")
            return 0
        }

        com.bitlockerdroid.util.DeviceIdentity.clearCache()
        val nodes = enumerateVoldNodes()
        LogFile.write("app", "scanAndDetect: ${nodes.size} vold nodes")

        // Drop detected-but-unlocked volumes that are no longer present on the
        // bus (device unplugged). Unlocked sessions are left untouched; the
        // DocumentsProvider will re-lock them lazily.
        UnlockManager.forgetDetectedMissing(nodes)

        var found = 0

        for (node in nodes) {
            val headerInfo = readHeaderInfo(node)
            val signature = headerInfo?.signature
            val guid = headerInfo?.guid
            LogFile.write("app", "  node $node sig=$signature guid=$guid")
            if (signature == "-FVE-FS-" || signature == "MSWIN4.1") {
                LogFile.write("app", "  >>> BitLocker DETECTED: $node (guid=$guid)")
                found++
                UnlockManager.onDeviceDetected(context, node, 0, guid)
            } else {
                UnlockManager.forgetDetected(node)
                UnlockManager.closeSessionIfPresent(node)
            }
        }

        LogFile.write("app", "scanAndDetect done, BitLocker found=$found")
        return found
    }

    /** Lists partition nodes under /dev/block/vold as root. */
    fun enumerateVoldNodes(): List<String> {
        val out = LinkedHashSet<String>()

        // Prefer public:* partition nodes (BitLocker headers live there).
        val public = RootAccess.exec("ls -d /dev/block/vold/public:* 2>/dev/null")
        public?.second?.lines()?.forEach { line ->
            val l = line.trim()
            if (l.isNotEmpty()) out.add(l)
        }

        // Only fall back to disk:* nodes if NO partition nodes exist
        // (rare whole-disk encryption without partition table).
        // Scanning raw disk:* when partition nodes are present locks the kernel SCSI bus
        // and returns redundant non-BitLocker MBR/GPT data.
        if (out.isEmpty()) {
            val disk = RootAccess.exec("ls -d /dev/block/vold/disk:* 2>/dev/null")
            disk?.second?.lines()?.forEach { line ->
                val l = line.trim()
                if (l.isNotEmpty()) out.add(l)
            }
        }

        return out.toList()
    }

    const val STATIC_BDE_GUID_FIXED = "4967d63b-2e29-4ad8-8399-f6a339e3d000"
    const val STATIC_BDE_GUID_TOGO = "4967d63b-2e29-4ad8-8399-f6a339e3d001"

    /** Reads sector 0 (512 bytes) and extracts signature and true persistent Volume GUID. */
    fun readHeaderInfo(path: String): BitLockerHeaderInfo? {
        if (!com.bitlockerdroid.util.DevicePathSecurity.isValid(path)) {
            LogFile.write("app", "readHeaderInfo rejected unsafe path: $path")
            return null
        }
        // Direct read of sector 0. Never use blockdev --flushbufs as it can deadlock kernel SCSI ioctl on open partitions.
        val cmd = "dd if='$path' bs=512 count=1 2>/dev/null"
        val sector0 = RootAccess.execBytes(cmd, 2500) ?: return null
        if (sector0.size < 512) return null

        val sig = String(sector0, 3, 8, Charsets.US_ASCII)
        if (sig != "-FVE-FS-" && sig != "MSWIN4.1") return null

        val guid = extractVolumeGuid(path, sig, sector0)
        return BitLockerHeaderInfo(sig, guid)
    }

    /**
     * Extracts the real unique persistent Volume GUID from the BitLocker metadata block.
     * Note: Sector 0 offset 0xa0/0x1a8 only stores the static Microsoft specification GUID
     * (4967d63b-2e29-4ad8-8399-f6a339e3d001), which is identical across ALL BitLocker drives.
     * The actual unique per-volume GUID is located in the dataset header (offset 0x50 of metadata block).
     */
    fun extractVolumeGuid(path: String, sig: String, sector0: ByteArray): String? {
        try {
            var metaOffset = 0L
            if (sig == "-FVE-FS-") {
                metaOffset = readLongLE(sector0, 0xb0)
                if (metaOffset <= 0L || metaOffset == -1L) {
                    // Vista fallback: metadata_lcn * spc * sectorSize
                    val sectorSize = readShortLE(sector0, 0x0b).toLong() and 0xffffL
                    val spc = sector0[0x0d].toLong() and 0xffL
                    val lcn = readLongLE(sector0, 0x38)
                    if (sectorSize > 0 && spc > 0 && lcn > 0) {
                        metaOffset = lcn * spc * sectorSize
                    }
                }
            } else if (sig == "MSWIN4.1") {
                metaOffset = readLongLE(sector0, 0x1b8)
            }

            if (metaOffset > 0L) {
                val skipSectors = metaOffset / 512L
                val metaCmd = "dd if='$path' bs=512 skip=$skipSectors count=1 2>/dev/null"
                val metaSector = RootAccess.execBytes(metaCmd, 2500)
                if (metaSector != null && metaSector.size >= 0x60) {
                    val metaSig = String(metaSector, 0, 8, Charsets.US_ASCII)
                    if (metaSig == "-FVE-FS-") {
                        // Offset 0x40 is dataset, offset 0x10 within dataset is volume guid -> 0x50
                        val guid = formatGuid(metaSector, 0x50)
                        if (!guid.isNullOrBlank() &&
                            guid != STATIC_BDE_GUID_TOGO &&
                            guid != STATIC_BDE_GUID_FIXED
                        ) {
                            return guid
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "failed extracting volume GUID from metadata for $path", e)
        }

        // Fallback: If metadata block could not be read, check fallback offsets but filter out static spec GUIDs
        val fallbackGuid = if (sig == "-FVE-FS-") formatGuid(sector0, 0xa0) else formatGuid(sector0, 0x1a8)
        if (!fallbackGuid.isNullOrBlank() &&
            fallbackGuid != STATIC_BDE_GUID_TOGO &&
            fallbackGuid != STATIC_BDE_GUID_FIXED
        ) {
            return fallbackGuid
        }

        return null
    }

    private fun readLongLE(bytes: ByteArray, offset: Int): Long {
        if (offset + 8 > bytes.size) return 0L
        var res = 0L
        for (i in 0 until 8) {
            res = res or ((bytes[offset + i].toLong() and 0xffL) shl (i * 8))
        }
        return res
    }

    private fun readShortLE(bytes: ByteArray, offset: Int): Short {
        if (offset + 2 > bytes.size) return 0
        val b0 = bytes[offset].toInt() and 0xff
        val b1 = bytes[offset + 1].toInt() and 0xff
        return ((b1 shl 8) or b0).toShort()
    }

    /** Formats a 16-byte Windows GUID into standard UUID string representation (lowercase). */
    fun formatGuid(bytes: ByteArray, offset: Int): String? {
        if (offset + 16 > bytes.size) return null
        var allZero = true
        for (i in 0 until 16) {
            if (bytes[offset + i] != 0.toByte()) {
                allZero = false
                break
            }
        }
        if (allZero) return null

        val d1 = (bytes[offset].toInt() and 0xff) or
                ((bytes[offset + 1].toInt() and 0xff) shl 8) or
                ((bytes[offset + 2].toInt() and 0xff) shl 16) or
                ((bytes[offset + 3].toInt() and 0xff) shl 24)
        val d2 = (bytes[offset + 4].toInt() and 0xff) or
                ((bytes[offset + 5].toInt() and 0xff) shl 8)
        val d3 = (bytes[offset + 6].toInt() and 0xff) or
                ((bytes[offset + 7].toInt() and 0xff) shl 8)
        return String.format(
            java.util.Locale.US,
            "%08x-%04x-%04x-%02x%02x-%02x%02x%02x%02x%02x%02x",
            d1, d2, d3,
            bytes[offset + 8], bytes[offset + 9],
            bytes[offset + 10], bytes[offset + 11], bytes[offset + 12],
            bytes[offset + 13], bytes[offset + 14], bytes[offset + 15]
        )
    }

    /** Reads the 8-byte OEM signature at offset 3 of the block device, as root. */
    fun readSignature(path: String): String? = readHeaderInfo(path)?.signature

    /** Returns the persistent volume GUID of the BitLocker device at [path], if detected. */
    fun getVolumeGuid(path: String): String? = readHeaderInfo(path)?.guid

    /** Raw signature check on a byte array (first 512 bytes of a device). */
    fun hasBitLockerSignature(sector0: ByteArray): Boolean {
        if (sector0.size < 11) return false
        val sig = String(sector0, 3, 8, Charsets.US_ASCII)
        return sig == "-FVE-FS-" || sig == "MSWIN4.1"
    }
}
