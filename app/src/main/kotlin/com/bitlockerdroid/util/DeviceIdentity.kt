package com.bitlockerdroid.util

import android.content.Context
import com.bitlockerdroid.R
import java.io.File
import java.util.Locale

/**
 * Resolves USB hardware identity (vendor, model, capacity) directly from sysfs.
 * Ensures the UI never displays raw ephemeral kernel nodes like `/dev/block/vold/...`.
 */
object DeviceIdentity {

    data class DeviceInfo(
        val vendor: String = "",
        val model: String = "",
        val sizeBytes: Long = 0L
    ) {
        val friendlyName: String
            get() = when {
                vendor.isNotBlank() && model.isNotBlank() -> {
                    if (model.startsWith(vendor, ignoreCase = true)) model else "$vendor $model"
                }
                model.isNotBlank() -> model
                vendor.isNotBlank() -> vendor
                else -> {
                    val ctx = try { ContextProvider.app } catch (_: Throwable) { null }
                    ctx?.getString(R.string.usb_storage_device) ?: "USB Storage Device"
                }
            }
    }

    data class PartitionPhysicalInfo(
        val partitionIndex: Int = 0,
        val startSector: Long = 0L,
        val partitionNodeName: String = ""
    )

    private val cache = java.util.concurrent.ConcurrentHashMap<String, DeviceInfo>()
    private val parentDiskCache = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val partitionInfoCache = java.util.concurrent.ConcurrentHashMap<String, PartitionPhysicalInfo>()
    private val usbSpeedCache = java.util.concurrent.ConcurrentHashMap<String, Int>()

    fun clearCache() {
        cache.clear()
        parentDiskCache.clear()
        partitionInfoCache.clear()
        usbSpeedCache.clear()
    }

    fun invalidate(devicePath: String) {
        cache.remove(devicePath)
        parentDiskCache.remove(devicePath)
        partitionInfoCache.remove(devicePath)
        usbSpeedCache.remove(devicePath)
    }

    fun retainOnly(validPaths: Collection<String>) {
        val validSet = validPaths.toSet()
        cache.keys.retainAll(validSet)
        parentDiskCache.keys.retainAll(validSet)
        partitionInfoCache.keys.retainAll(validSet)
        usbSpeedCache.keys.retainAll(validSet)
    }

    /**
     * Resolves hardware info (vendor, model, capacity) directly from sysfs.
     * Priority 1: Direct sysfs read (extremely fast, zero process spawns, no su overhead).
     * Priority 2: Root sysfs query (if direct read was blocked by SELinux).
     * Priority 3: UsbManager fallback (if sysfs is unavailable).
     */
    fun queryDeviceInfo(devicePath: String, forceRefresh: Boolean = false): DeviceInfo {
        if (!DevicePathSecurity.isValid(devicePath)) return DeviceInfo()

        if (!forceRefresh) {
            cache[devicePath]?.let { cached ->
                if ((cached.vendor.isNotBlank() || cached.model.isNotBlank()) && cached.sizeBytes > 0) {
                    return cached
                }
            }
        }

        if (devicePath.startsWith("usb://")) {
            val usbInfo = com.bitlockerdroid.usb.UsbStorageManager.getDeviceInfo(devicePath)
            if (usbInfo != null) {
                cache[devicePath] = usbInfo
                return usbInfo
            }
        }

        try {
            var vendor = ""
            var model = ""
            var sizeBytes = 0L

            val fileName = File(devicePath).name
            val majMin = when {
                fileName.startsWith("public:") -> fileName.removePrefix("public:").replace(',', ':')
                fileName.startsWith("disk:") -> fileName.removePrefix("disk:").replace(',', ':')
                else -> null
            }

            // Priority 1: Direct sysfs read (runs in <0.2ms, no root process creation)
            val sysDevFile = when {
                majMin != null -> File("/sys/dev/block/$majMin")
                File("/sys/block/$fileName").exists() -> File("/sys/block/$fileName")
                File("/sys/class/block/$fileName").exists() -> File("/sys/class/block/$fileName")
                else -> null
            }
            if (sysDevFile != null) {
                val canonical = try { sysDevFile.canonicalFile } catch (_: Exception) { sysDevFile }
                if (sizeBytes <= 0L) {
                    try {
                        val sizeFile = File(canonical, "size")
                        if (sizeFile.exists()) {
                            val sectors = sizeFile.readText().trim().toLongOrNull() ?: 0L
                            if (sectors > 0) sizeBytes = sectors * 512L
                        }
                    } catch (_: Exception) {}
                }

                val candidates = listOf(
                    File(canonical, "device"),
                    canonical.parentFile?.let { File(it, "device") },
                    canonical.parentFile?.parentFile?.let { File(it, "device") }
                ).filterNotNull()

                for (devDir in candidates) {
                    if (devDir.isDirectory) {
                        try {
                            val vFile = File(devDir, "vendor")
                            if (vFile.exists()) {
                                val v = vFile.readText().trim()
                                if (v.isNotBlank() && vendor.isBlank()) vendor = v
                            }
                            val mFile = File(devDir, "model")
                            if (mFile.exists()) {
                                val m = mFile.readText().trim()
                                if (m.isNotBlank() && model.isBlank()) model = m
                            }
                            if (vendor.isNotBlank() || model.isNotBlank()) break
                        } catch (_: Exception) {}
                    }
                }
            }

            // Priority 2: Root sysfs query fallback only if direct read was incomplete
            if ((vendor.isBlank() && model.isBlank() || sizeBytes <= 0L) && RootAccess.hasSu()) {
                val devMajMin = majMin ?: run {
                    val lsOut = RootAccess.exec("ls -l '$devicePath' 2>/dev/null")?.second
                    val match = Regex("""(\d+),\s*(\d+)""").find(lsOut ?: "")
                    if (match != null) "${match.groupValues[1]}:${match.groupValues[2]}" else null
                }
                if (devMajMin != null) {
                    val cmd = "cat /sys/dev/block/$devMajMin/size /sys/dev/block/$devMajMin/../device/vendor /sys/dev/block/$devMajMin/../device/model 2>/dev/null"
                    val res = RootAccess.exec(cmd)
                    val lines = res?.second?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() }
                    if (!lines.isNullOrEmpty()) {
                        val sectors = lines.getOrNull(0)?.toLongOrNull() ?: 0L
                        if (sectors > 0 && sizeBytes <= 0L) sizeBytes = sectors * 512L
                        val rootVendor = lines.getOrNull(1).orEmpty()
                        val rootModel = lines.getOrNull(2).orEmpty()
                        if (rootVendor.isNotBlank() && vendor.isBlank()) vendor = rootVendor
                        if (rootModel.isNotBlank() && model.isBlank()) model = rootModel
                    }
                }
            }

            // Priority 3: Fallback to UsbManager only if sysfs could not provide vendor/model
            if (vendor.isBlank() && model.isBlank()) {
                try {
                    val ctx = try { ContextProvider.app } catch (_: Throwable) { null }
                    if (ctx != null) {
                        val usbManager = ctx.getSystemService(android.content.Context.USB_SERVICE) as? android.hardware.usb.UsbManager
                        val massStorageDevice = usbManager?.deviceList?.values?.firstOrNull { dev ->
                            (0 until dev.interfaceCount).any { dev.getInterface(it).interfaceClass == 8 }
                        }
                        if (massStorageDevice != null) {
                            val mfg = massStorageDevice.manufacturerName?.trim().orEmpty()
                            val prod = massStorageDevice.productName?.trim().orEmpty()
                            if (mfg.isNotBlank() || prod.isNotBlank()) {
                                vendor = mfg
                                model = prod
                            }
                        }
                    }
                } catch (_: Exception) {}
            }

            val result = DeviceInfo(vendor, model, sizeBytes)
            if (result.vendor.isNotBlank() || result.model.isNotBlank() || result.sizeBytes > 0L) {
                cache[devicePath] = result
            }
            return result
        } catch (_: Exception) {
            return DeviceInfo()
        }
    }

    private fun isGenericUsbLabel(name: String?, defaultUsb: String): Boolean {
        if (name.isNullOrBlank()) return true
        if (name == defaultUsb) return true
        if (name == "USB Storage Device" || name == "\u0055\u0053\u0042 \u5b58\u50a8\u8bbe\u5907") return true
        return false
    }

    /**
     * Formats a clean, user-friendly display title for a BitLocker drive.
     * Never returns raw node paths like `/dev/block/vold/...`.
     */
    fun getDisplayName(
        label: String? = null,
        deviceName: String? = null,
        guid: String? = null
    ): String {
        if (!label.isNullOrBlank() && label.isNotBlank()) {
            return label
        }
        val ctx = try { ContextProvider.app } catch (_: Throwable) { null }
        val defaultUsb = ctx?.getString(R.string.usb_storage_device) ?: "USB Storage Device"
        if (!deviceName.isNullOrBlank() && !isGenericUsbLabel(deviceName, defaultUsb)) {
            return deviceName
        }
        if (!guid.isNullOrBlank()) {
            return ctx?.getString(R.string.encrypted_volume_format, guid.take(8))
                ?: "BitLocker Volume (${guid.take(8)})"
        }
        return ctx?.getString(R.string.encrypted_usb_drive) ?: "BitLocker Encrypted USB Drive"
    }

    /**
     * Formats a clean subtitle showing capacity, hardware model, and/or persistent Volume GUID.
     * Never returns raw node paths like `/dev/block/vold/...`.
     */
    fun getDisplaySubtitle(
        sizeBytes: Long,
        deviceName: String? = null,
        guid: String? = null
    ): String {
        val parts = mutableListOf<String>()
        val ctx = try { ContextProvider.app } catch (_: Throwable) { null }
        val defaultUsb = ctx?.getString(R.string.usb_storage_device) ?: "USB Storage Device"
        if (!deviceName.isNullOrBlank() && !isGenericUsbLabel(deviceName, defaultUsb)) {
            parts.add(deviceName)
        }
        if (sizeBytes > 0) {
            parts.add(formatSize(sizeBytes))
        }
        if (!guid.isNullOrBlank()) {
            val shortGuid = if (guid.length >= 12) "${guid.take(8)}...${guid.takeLast(4)}" else guid
            val guidText = ctx?.getString(R.string.volume_id_format, shortGuid) ?: "Volume ID: $shortGuid"
            parts.add(guidText)
        }
        val connectedText = ctx?.getString(R.string.device_connected) ?: "Connected"
        return parts.joinToString(" · ").ifBlank { connectedText }
    }

    fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var digitGroups = 0
        var b = bytes.toDouble()
        while (b >= 1024 && digitGroups < units.size - 1) {
            b /= 1024
            digitGroups++
        }
        return String.format(Locale.US, "%.1f %s", b, units[digitGroups])
    }

    /**
     * Resolves the parent physical disk block device node for any partition path.
     * e.g.:
     *   /dev/block/vold/public:8,101 -> /dev/block/sdg
     *   /dev/block/sdg5              -> /dev/block/sdg
     *   /dev/block/nvme0n1p2         -> /dev/block/nvme0n1
     *   /dev/block/mmcblk0p1         -> /dev/block/mmcblk0
     *   usb://0bda:9210/1/1          -> usb://0bda:9210/1
     */
    fun resolveParentDiskNode(devicePath: String): String {
        if (devicePath.isBlank()) return devicePath

        parentDiskCache[devicePath]?.let { return it }

        // 1. USB BOT URL: group by device prefix (usb://deviceId)
        if (devicePath.startsWith("usb://")) {
            val trimmed = devicePath.removePrefix("usb://")
            val parts = trimmed.split('/')
            val resolved = if (parts.size >= 2) "usb://${parts[0]}" else devicePath
            parentDiskCache[devicePath] = resolved
            return resolved
        }

        // 2. Direct symlink target inspection via Os.readlink (bypasses SELinux traversal)
        try {
            val fileName = File(devicePath).name
            val majMin = when {
                fileName.startsWith("public:") -> fileName.removePrefix("public:").replace(',', ':')
                fileName.startsWith("disk:") -> fileName.removePrefix("disk:").replace(',', ':')
                else -> null
            }

            val symlinkCandidates = mutableListOf<String>()
            if (majMin != null) {
                symlinkCandidates.add("/sys/dev/block/$majMin")
            }
            symlinkCandidates.add("/sys/block/$fileName")
            symlinkCandidates.add("/sys/class/block/$fileName")

            for (linkPath in symlinkCandidates) {
                try {
                    val target = android.system.Os.readlink(linkPath)
                    if (target.contains("/block/")) {
                        val diskName = target.substringAfter("/block/").substringBefore('/')
                        if (diskName.isNotBlank()) {
                            val resolved = "/dev/block/$diskName"
                            parentDiskCache[devicePath] = resolved
                            return resolved
                        }
                    }
                } catch (_: Throwable) {}
            }
        } catch (_: Exception) {}

        // 3. Instant Regex fallback
        val sdMatch = Regex("""^(/dev/block/sd[a-z])\d+$""").find(devicePath)
        if (sdMatch != null) {
            val resolved = sdMatch.groupValues[1]
            parentDiskCache[devicePath] = resolved
            return resolved
        }

        val nvmeMatch = Regex("""^(/dev/block/nvme\d+n\d+)p\d+$""").find(devicePath)
        if (nvmeMatch != null) {
            val resolved = nvmeMatch.groupValues[1]
            parentDiskCache[devicePath] = resolved
            return resolved
        }

        val mmcMatch = Regex("""^(/dev/block/mmcblk\d+)p\d+$""").find(devicePath)
        if (mmcMatch != null) {
            val resolved = mmcMatch.groupValues[1]
            parentDiskCache[devicePath] = resolved
            return resolved
        }

        parentDiskCache[devicePath] = devicePath
        return devicePath
    }

    /**
     * Resolves physical partition index, start sector, and clean node name for any partition path.
     * e.g. /dev/block/vold/public:8,101 -> PartitionPhysicalInfo(partitionIndex=5, startSector=4096, partitionNodeName="sdg5")
     */
    fun resolvePartitionInfo(devicePath: String): PartitionPhysicalInfo {
        if (devicePath.isBlank()) return PartitionPhysicalInfo()
        partitionInfoCache[devicePath]?.let { return it }

        // 1. USB BOT URL: usb://deviceId/interface/lun/part or usb://deviceId/part
        if (devicePath.startsWith("usb://")) {
            val trimmed = devicePath.removePrefix("usb://")
            val parts = trimmed.split('/')
            val partIdx = parts.lastOrNull()?.toIntOrNull() ?: 1
            val info = PartitionPhysicalInfo(
                partitionIndex = partIdx,
                startSector = partIdx.toLong() * 2048L,
                partitionNodeName = "p$partIdx"
            )
            partitionInfoCache[devicePath] = info
            return info
        }

        var partIndex = 0
        var startSector = 0L
        var nodeName = ""

        try {
            val fileName = File(devicePath).name
            val majMin = when {
                fileName.startsWith("public:") -> fileName.removePrefix("public:").replace(',', ':')
                fileName.startsWith("disk:") -> fileName.removePrefix("disk:").replace(',', ':')
                else -> null
            }

            if (majMin != null) {
                val pFile = File("/sys/dev/block/$majMin/partition")
                val sFile = File("/sys/dev/block/$majMin/start")
                partIndex = try { if (pFile.exists()) pFile.readText().trim().toIntOrNull() ?: 0 else 0 } catch (_: Exception) { 0 }
                startSector = try { if (sFile.exists()) sFile.readText().trim().toLongOrNull() ?: 0L else 0L } catch (_: Exception) { 0L }

                try {
                    val linkTarget = android.system.Os.readlink("/sys/dev/block/$majMin")
                    if (linkTarget.isNotBlank()) {
                        nodeName = File(linkTarget).name
                    }
                } catch (_: Throwable) {}
            } else {
                val pFile = File("/sys/class/block/$fileName/partition")
                val sFile = File("/sys/class/block/$fileName/start")
                partIndex = try { if (pFile.exists()) pFile.readText().trim().toIntOrNull() ?: 0 else 0 } catch (_: Exception) { 0 }
                startSector = try { if (sFile.exists()) sFile.readText().trim().toLongOrNull() ?: 0L else 0L } catch (_: Exception) { 0L }
                nodeName = fileName
            }
        } catch (_: Exception) {}

        if (nodeName.isBlank()) {
            nodeName = File(devicePath).name
        }
        if (partIndex <= 0) {
            val sdMatch = Regex("""^sd[a-z](\d+)$""").find(nodeName)
            if (sdMatch != null) {
                partIndex = sdMatch.groupValues[1].toIntOrNull() ?: 0
            } else {
                val pMatch = Regex("""p(\d+)$""").find(nodeName)
                if (pMatch != null) {
                    partIndex = pMatch.groupValues[1].toIntOrNull() ?: 0
                }
            }
        }

        val info = PartitionPhysicalInfo(
            partitionIndex = partIndex,
            startSector = startSector,
            partitionNodeName = nodeName
        )
        partitionInfoCache[devicePath] = info
        return info
    }

    fun getCachedUsbSpeed(devicePath: String): Int? {
        return usbSpeedCache[devicePath]
    }

    fun detectUsbSpeed(devicePath: String): Int? {
        if (devicePath.isBlank()) return null
        usbSpeedCache[devicePath]?.let { return it }

        // 1. USB Storage Manager (non-root OTG direct endpoint check)
        if (devicePath.startsWith("usb://")) {
            val speed = com.bitlockerdroid.usb.UsbStorageManager.getUsbSpeedMbps(devicePath)
            if (speed != null && speed > 0) {
                usbSpeedCache[devicePath] = speed
                return speed
            }
        }

        // 2. Direct sysfs upward traversal
        try {
            val fileName = File(devicePath).name
            val majMin = when {
                fileName.startsWith("public:") -> fileName.removePrefix("public:").replace(',', ':')
                fileName.startsWith("disk:") -> fileName.removePrefix("disk:").replace(',', ':')
                else -> null
            }
            val candidates = mutableListOf<File>()
            if (majMin != null) candidates.add(File("/sys/dev/block/$majMin"))
            candidates.add(File("/sys/block/$fileName"))
            candidates.add(File("/sys/class/block/$fileName"))

            for (cand in candidates) {
                if (cand.exists()) {
                    var cur: File? = try { cand.canonicalFile } catch (_: Exception) { null }
                    while (cur != null && cur.path != "/" && cur.path != "/sys") {
                        val speedFile = File(cur, "speed")
                        if (speedFile.exists() && speedFile.canRead()) {
                            val s = speedFile.readText().trim().toIntOrNull()
                            if (s != null && s > 0) {
                                usbSpeedCache[devicePath] = s
                                return s
                            }
                        }
                        cur = cur.parentFile
                    }
                }
            }
        } catch (_: Exception) {}

        // 3. Root sysfs query fallback
        if (RootAccess.hasSu() && DevicePathSecurity.isValid(devicePath)) {
            try {
                val fileName = File(devicePath).name
                val majMin = when {
                    fileName.startsWith("public:") -> fileName.removePrefix("public:").replace(',', ':')
                    fileName.startsWith("disk:") -> fileName.removePrefix("disk:").replace(',', ':')
                    else -> {
                        val lsOut = RootAccess.exec("ls -l '$devicePath' 2>/dev/null")?.second
                        val match = Regex("""(\d+),\s*(\d+)""").find(lsOut ?: "")
                        if (match != null) "${match.groupValues[1]}:${match.groupValues[2]}" else null
                    }
                }
                if (majMin != null) {
                    val cmd = "p=\$(realpath /sys/dev/block/$majMin 2>/dev/null); while [ \"\$p\" != \"/\" -a -n \"\$p\" ]; do if [ -f \"\$p/speed\" ]; then cat \"\$p/speed\"; break; fi; p=\$(dirname \"\$p\"); done"
                    val out = RootAccess.exec(cmd)?.second?.trim()
                    val s = out?.toIntOrNull()
                    if (s != null && s > 0) {
                        usbSpeedCache[devicePath] = s
                        return s
                    }
                }

                // Fallback: check /sys/bus/usb/devices/*/speed
                val allSpeeds = RootAccess.exec("for s in /sys/bus/usb/devices/*/speed; do [ -f \"\$s\" ] && cat \"\$s\"; done 2>/dev/null")?.second
                val list = allSpeeds?.lines()?.mapNotNull { it.trim().toIntOrNull() }?.filter { it > 0 }
                if (!list.isNullOrEmpty()) {
                    val s = list.maxOrNull()
                    if (s != null && s > 0) {
                        usbSpeedCache[devicePath] = s
                        return s
                    }
                }
            } catch (_: Exception) {}
        }

        return null
    }

    fun getCachedUsbProtocolDesc(devicePath: String, context: Context? = null): String {
        val cachedSpeed = usbSpeedCache[devicePath]
        return formatUsbProtocol(cachedSpeed, context)
    }

    fun getUsbProtocolDesc(devicePath: String, context: Context? = null): String {
        val speed = detectUsbSpeed(devicePath)
        return formatUsbProtocol(speed, context)
    }

    fun formatUsbProtocol(speedMbps: Int?, context: Context? = null): String {
        val ctx = context ?: try { ContextProvider.app } catch (_: Throwable) { null }
        return when {
            speedMbps == null -> ctx?.getString(R.string.usb_protocol_unknown) ?: "Unknown / Internal Bus"
            speedMbps >= 20000 -> ctx?.getString(R.string.usb_protocol_gen2x2) ?: "USB 3.2 Gen 2x2 (20 Gbps)"
            speedMbps >= 10000 -> ctx?.getString(R.string.usb_protocol_superspeed_plus) ?: "USB 3.1 / 3.2 Gen 2 (10 Gbps SuperSpeed+)"
            speedMbps >= 5000 -> ctx?.getString(R.string.usb_protocol_superspeed) ?: "USB 3.0 / 3.2 Gen 1 (5 Gbps SuperSpeed)"
            speedMbps == 480 -> ctx?.getString(R.string.usb_protocol_high_speed) ?: "USB 2.0 (480 Mbps High-Speed)"
            speedMbps == 12 -> ctx?.getString(R.string.usb_protocol_full_speed) ?: "USB 1.1 (12 Mbps Full-Speed)"
            else -> ctx?.getString(R.string.usb_protocol_format, speedMbps) ?: "USB ($speedMbps Mbps)"
        }
    }
}
