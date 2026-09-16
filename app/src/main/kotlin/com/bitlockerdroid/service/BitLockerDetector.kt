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
        val guid: String?,
        val recoveryKeyId: String? = null
    )

    data class MetadataGuids(
        val volumeGuid: String?,
        val recoveryKeyId: String?
    )

    /**
     * App-side scan via su: enumerates /dev/block/vold and reads signatures & volume GUIDs as
     * root, then triggers the unlock flow for BitLocker matches.
     * Returns the number of BitLocker volumes found.
     */
    fun scanAndDetect(context: Context): Int {
        var found = 0
        val presentBlockNodes = mutableListOf<String>()
        val useRoot = com.bitlockerdroid.util.PreferenceHelper.useRootAccess && RootAccess.hasSu()
        val detectedGuids = mutableSetOf<String>()

        if (useRoot) {
            VirtualStorageMountManager.ensureUsbStorageBound()
            val nodes = enumerateVoldNodes()
            presentBlockNodes.addAll(nodes)
            LogFile.write("app", "scanAndDetect (Root): ${nodes.size} block nodes")

            for (node in nodes) {
                val headerInfo = readHeaderInfo(node)
                val signature = headerInfo?.signature
                val guid = headerInfo?.guid
                val recoveryKeyId = headerInfo?.recoveryKeyId
                LogFile.write("app", "  node $node sig=$signature guid=$guid rkId=$recoveryKeyId")
                if (signature == "-FVE-FS-" || signature == "MSWIN4.1") {
                    if (!guid.isNullOrBlank() && !detectedGuids.add(guid.lowercase())) {
                        LogFile.write("app", "  node $node duplicate GUID $guid, skipping duplicate registration")
                        continue
                    }
                    LogFile.write("app", "  >>> BitLocker DETECTED (Root): $node (guid=$guid, rkId=$recoveryKeyId)")
                    found++
                    StorageNotificationSuppressor.suppressForVolume(context, node)
                    UnlockManager.onDeviceDetected(context, node, 0, guid, recoveryKeyId)
                } else {
                    StorageNotificationSuppressor.forgetNode(node)
                    UnlockManager.forgetDetected(node)
                    UnlockManager.closeSessionIfPresent(node)
                }
            }
        } else {
            LogFile.write("app", "scanAndDetect: running in non-root USB Host mode (useRoot=$useRoot)")
        }

        // Only scan USB devices via non-root USB Host stack if Root is disabled or found 0 block devices
        val presentUsbNodes = mutableListOf<String>()
        if (!useRoot || presentBlockNodes.isEmpty()) {
            try {
                val usbParts = com.bitlockerdroid.usb.UsbStorageManager.scanUsbDevices(context)
                for (p in usbParts) {
                    presentUsbNodes.add(p.devicePath)
                    if (!p.guid.isNullOrBlank() && !detectedGuids.add(p.guid.lowercase())) {
                        LogFile.write("app", "  USB partition ${p.devicePath} duplicate GUID ${p.guid}, skipping duplicate")
                        continue
                    }
                    LogFile.write("app", "  >>> BitLocker DETECTED (USB Host): ${p.devicePath} (guid=${p.guid}, rkId=${p.recoveryKeyId})")
                    found++
                    UnlockManager.onDeviceDetected(context, p.devicePath, 0, p.guid, p.recoveryKeyId)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in scanUsbDevices", e)
            }
        }

        // Drop detected-but-unlocked or active volumes that are no longer present on ANY bus
        val allPresentNodes = presentBlockNodes + presentUsbNodes
        UnlockManager.forgetDetectedMissing(allPresentNodes)

        LogFile.write("app", "scanAndDetect done, BitLocker found=$found")
        return found
    }

    /** Lists partition nodes under /dev/block/vold and USB SCSI nodes as root. */
    fun enumerateVoldNodes(): List<String> {
        val out = LinkedHashSet<String>()
        val publicMinors = HashSet<String>()

        // 1. Prefer public:* partition nodes (BitLocker headers live there).
        val public = RootAccess.exec("ls -d /dev/block/vold/public:* 2>/dev/null")
        public?.second?.lines()?.forEach { line ->
            val l = line.trim()
            if (l.isNotEmpty()) {
                out.add(l)
                val minorPart = l.substringAfter("public:", "").replace(',', ':')
                if (minorPart.isNotEmpty()) {
                    publicMinors.add(minorPart)
                }
            }
        }

        // 2. Check kernel SCSI block devices that belong to USB (sysfs link contains /usb)
        try {
            val usbDisks = RootAccess.exec("for d in /sys/block/sd*; do if readlink \$d 2>/dev/null | grep -q '/usb'; then ls -d /dev/block/\$(basename \$d)* 2>/dev/null; fi; done")
            usbDisks?.second?.lines()?.forEach { line ->
                val l = line.trim()
                if (l.isNotEmpty()) {
                    val devName = l.substringAfterLast('/')
                    val sysDev = try {
                        val devFile = java.io.File("/sys/class/block/$devName/dev")
                        if (devFile.exists()) devFile.readText().trim() else null
                    } catch (_: Throwable) { null }
                    if (sysDev != null && publicMinors.contains(sysDev)) {
                        // Node is already represented by /dev/block/vold/public:... -> skip alias
                        return@forEach
                    }

                    // Skip whole-disks (e.g. "sdg") that contain partitions ("sdg1", "sdg5", etc.)
                    val isPartitionedDisk = devName.matches(Regex("^sd[a-z]+$")) &&
                            java.io.File("/sys/class/block/$devName").listFiles()?.any {
                                it.name.startsWith(devName) && it.name != devName
                            } == true
                    if (!isPartitionedDisk) {
                        out.add(l)
                    }
                }
            }
        } catch (_: Throwable) {}

        // Only fall back to disk:* nodes if NO partition nodes exist
        // (rare whole-disk encryption without partition table).
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

    /** Reads sector 0 (512 bytes) and extracts signature and true persistent Volume GUID and Recovery Key ID. */
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

        val guids = extractMetadataGuids(path, sig, sector0)
        return BitLockerHeaderInfo(sig, guids.volumeGuid, guids.recoveryKeyId)
    }

    /**
     * Extracts the real unique persistent Volume GUID and Recovery Key Identifier from the BitLocker metadata block.
     * Note: Sector 0 offset 0xa0/0x1a8 only stores the static Microsoft specification GUID
     * (4967d63b-2e29-4ad8-8399-f6a339e3d001), which is identical across ALL BitLocker drives.
     * The actual unique per-volume GUID is located in the dataset header (offset 0x50 of metadata block).
     * The 48-digit Recovery Key Identifier is in the DATUMS_ENTRY_VMK datum with nonce range 0x0800..0x0fff.
     */
    fun extractMetadataGuids(path: String, sig: String, sector0: ByteArray): MetadataGuids {
        if (!com.bitlockerdroid.util.DevicePathSecurity.isValid(path)) {
            return MetadataGuids(null, null)
        }
        return extractMetadataGuidsFromReader(sig, sector0) { offBytes, cntBytes ->
            val skipSectors = offBytes / 512L
            val countSectors = cntBytes / 512
            val metaCmd = "dd if='$path' bs=512 skip=$skipSectors count=$countSectors 2>/dev/null"
            RootAccess.execBytes(metaCmd, 2500)
        }
    }

    fun extractMetadataGuidsFromReader(
        sig: String,
        sector0: ByteArray,
        readBytes: (offsetBytes: Long, countBytes: Int) -> ByteArray?
    ): MetadataGuids {
        var volumeGuid: String? = null
        var recoveryKeyId: String? = null
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
                // Read 4KB (8 sectors) to cover dataset header and top-level datums
                val metaSector = readBytes(metaOffset, 4096)
                if (metaSector != null && metaSector.size >= 0x60) {
                    val metaSig = String(metaSector, 0, 8, Charsets.US_ASCII)
                    if (metaSig == "-FVE-FS-") {
                        // Offset 0x40 is dataset, offset 0x10 within dataset is volume guid -> 0x50
                        val guid = formatGuid(metaSector, 0x50, uppercase = false)
                        if (!guid.isNullOrBlank() &&
                            guid != STATIC_BDE_GUID_TOGO &&
                            guid != STATIC_BDE_GUID_FIXED
                        ) {
                            volumeGuid = guid
                        }

                        // Walk top-level datums to extract the 48-digit Recovery Key Identifier
                        val headerSize = readShortLE(metaSector, 0x48).toInt() and 0xffff
                        val copySize = (readShortLE(metaSector, 0x4c).toInt() and 0xffff).let { if (it > 0) it else metaSector.size }
                        val maxDatumOffset = if (copySize in 0x48..metaSector.size) copySize else metaSector.size

                        var pos = 0x40 + (if (headerSize >= 0x10) headerSize else 0x30)
                        while (pos + 8 <= maxDatumOffset && pos + 8 <= metaSector.size) {
                            val datumSize = readShortLE(metaSector, pos).toInt() and 0xffff
                            if (datumSize < 8) break
                            val entryType = readShortLE(metaSector, pos + 2).toInt() and 0xffff
                            val valueType = readShortLE(metaSector, pos + 4).toInt() and 0xffff

                            // DATUMS_ENTRY_VMK (0x0002) and DATUMS_VALUE_VMK (0x0008)
                            if (entryType == 0x0002 && valueType == 0x0008 && pos + 36 <= metaSector.size) {
                                // Nonce is at offset 24; range is at nonce[10..11] (offset 34)
                                val datumRange = readShortLE(metaSector, pos + 34).toInt() and 0xffff
                                if (datumRange in 0x0800..0x0fff) {
                                    recoveryKeyId = formatGuid(metaSector, pos + 8, uppercase = true)
                                    break
                                }
                            }
                            pos += datumSize
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "failed extracting metadata GUIDs", e)
        }

        // Fallback: If metadata block could not be read, check fallback offsets but filter out static spec GUIDs
        if (volumeGuid == null) {
            val fallbackGuid = if (sig == "-FVE-FS-") formatGuid(sector0, 0xa0, uppercase = false) else formatGuid(sector0, 0x1a8, uppercase = false)
            if (!fallbackGuid.isNullOrBlank() &&
                fallbackGuid != STATIC_BDE_GUID_TOGO &&
                fallbackGuid != STATIC_BDE_GUID_FIXED
            ) {
                volumeGuid = fallbackGuid
            }
        }

        return MetadataGuids(volumeGuid, recoveryKeyId)
    }

    /** Extracts the real unique persistent Volume GUID from the BitLocker metadata block. */
    fun extractVolumeGuid(path: String, sig: String, sector0: ByteArray): String? =
        extractMetadataGuids(path, sig, sector0).volumeGuid

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

    /** Formats a 16-byte Windows GUID into standard UUID string representation. */
    fun formatGuid(bytes: ByteArray, offset: Int, uppercase: Boolean = false): String? {
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
        val formatStr = if (uppercase) {
            "%08X-%04X-%04X-%02X%02X-%02X%02X%02X%02X%02X%02X"
        } else {
            "%08x-%04x-%04x-%02x%02x-%02x%02x%02x%02x%02x%02x"
        }
        return String.format(
            java.util.Locale.US,
            formatStr,
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

    /** Returns the 48-digit recovery key identifier (uppercase GUID) if present in metadata. */
    fun getRecoveryKeyId(path: String): String? = readHeaderInfo(path)?.recoveryKeyId

    /** Raw signature check on a byte array (first 512 bytes of a device). */
    fun hasBitLockerSignature(sector0: ByteArray): Boolean {
        if (sector0.size < 11) return false
        val sig = String(sector0, 3, 8, Charsets.US_ASCII)
        return sig == "-FVE-FS-" || sig == "MSWIN4.1"
    }
}
