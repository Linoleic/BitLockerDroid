package com.bitlockerdroid.util

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
                else -> "USB 存储设备"
            }
    }

    private val cache = java.util.concurrent.ConcurrentHashMap<String, DeviceInfo>()

    fun clearCache() {
        cache.clear()
    }

    fun invalidate(devicePath: String) {
        cache.remove(devicePath)
    }

    /**
     * Resolves hardware info (vendor, model, capacity) directly from sysfs.
     * Priority 1: Root sysfs for this specific block node (100% accurate SCSI INQUIRY).
     * Priority 2: Direct sysfs read (if permitted by SELinux).
     * Priority 3: UsbManager fallback (if sysfs is unavailable).
     */
    fun queryDeviceInfo(devicePath: String, forceRefresh: Boolean = false): DeviceInfo {
        if (!DevicePathSecurity.isValid(devicePath)) return DeviceInfo()

        if (!forceRefresh) {
            cache[devicePath]?.let { cached ->
                if (cached.friendlyName != "USB 存储设备" && cached.sizeBytes > 0) {
                    return cached
                }
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

            // Priority 1: Root sysfs query for this EXACT block device (accurate SCSI INQUIRY string)
            if (RootAccess.hasSu()) {
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
                        if (sectors > 0) sizeBytes = sectors * 512L
                        val rootVendor = lines.getOrNull(1).orEmpty()
                        val rootModel = lines.getOrNull(2).orEmpty()
                        if (rootVendor.isNotBlank()) vendor = rootVendor
                        if (rootModel.isNotBlank()) model = rootModel
                    }
                }
            }

            // Priority 2: Direct sysfs read (if permitted by SELinux / unprivileged)
            if (vendor.isBlank() && model.isBlank()) {
                val sysDevFile = when {
                    majMin != null -> File("/sys/dev/block/$majMin")
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
        if (!deviceName.isNullOrBlank() && deviceName.isNotBlank() && deviceName != "USB 存储设备") {
            return deviceName
        }
        if (!guid.isNullOrBlank()) {
            return "BitLocker 加密卷 (${guid.take(8)})"
        }
        return "BitLocker 加密 U 盘"
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
        if (!deviceName.isNullOrBlank() && deviceName.isNotBlank() && deviceName != "USB 存储设备") {
            parts.add(deviceName)
        }
        if (sizeBytes > 0) {
            parts.add(formatSize(sizeBytes))
        }
        if (!guid.isNullOrBlank()) {
            val shortGuid = if (guid.length >= 12) "${guid.take(8)}...${guid.takeLast(4)}" else guid
            parts.add("卷 ID: $shortGuid")
        }
        return parts.joinToString(" · ").ifBlank { "已连接" }
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
}
