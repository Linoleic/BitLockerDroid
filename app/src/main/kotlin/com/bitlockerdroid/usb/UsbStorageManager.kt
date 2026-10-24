package com.bitlockerdroid.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import com.bitlockerdroid.service.BitLockerDetector
import com.bitlockerdroid.service.UnlockManager
import com.bitlockerdroid.util.DeviceIdentity
import com.bitlockerdroid.util.LogFile
import me.jahnen.libaums.core.driver.scsi.ScsiBlockDevice
import me.jahnen.libaums.core.usb.UsbCommunication
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap

/**
 * Non-Root USB Mass Storage Manager.
 *
 * Provides userspace USB Host BOT (Bulk-Only Transport) access to BitLocker
 * drives without requiring root / su. Discovers MBR, EBR (extended/logical),
 * and GPT partitions, extracts BitLocker metadata, and bridges decrypted
 * I/O to native dislocker via high-performance Unix domain socketpairs.
 */
object UsbStorageManager {

    private const val TAG = "UsbStorageManager"
    const val ACTION_USB_PERMISSION = "com.bitlockerdroid.USB_PERMISSION"

    data class UsbPartitionInfo(
        val deviceId: Int,
        val partitionIndex: Int,
        val startLba: Long,
        val sectorCount: Long,
        val sectorSize: Int,
        val devicePath: String,
        val guid: String?,
        val recoveryKeyId: String?,
        val vendor: String,
        val model: String
    )

    data class UsbDeviceConnectionHolder(
        val usbDevice: UsbDevice,
        val connection: UsbDeviceConnection,
        val communication: UsbCommunication,
        val scsiDevice: ScsiBlockDevice,
        val partitions: MutableList<UsbPartitionInfo> = mutableListOf()
    ) {
        /** Once BitLocker paths are discovered, keep them stable until physical detach. */
        @Volatile
        var hasCachedBitLockerPartitions: Boolean = false

        @Volatile
        var transportHealthy: Boolean = true
            get() = field && (communication as? PipelinedUsbCommunication)?.isTransportHealthy != false
    }

    class UsbSession(
        val nativeFd: Int,
        val workerPfd: ParcelFileDescriptor,
        val worker: UsbBlockDeviceWorker,
        private val onClose: (() -> Unit)? = null
    ) : AutoCloseable {
        private val isClosed = java.util.concurrent.atomic.AtomicBoolean(false)
        val isTransportHealthy: Boolean get() = worker.partitionDriver.isTransportHealthy

        override fun close() {
            if (!isClosed.compareAndSet(false, true)) return
            try {
                onClose?.invoke()
            } catch (_: Exception) {}
            worker.closeWorker()
            try { workerPfd.close() } catch (_: Exception) {}
            try { worker.join(2000) } catch (_: Exception) {}
        }
    }

    /** Cache of active USB device connections by deviceId */
    private val activeDevices = ConcurrentHashMap<Int, UsbDeviceConnectionHolder>()

    fun getActiveHolder(deviceId: Int): UsbDeviceConnectionHolder? = activeDevices[deviceId]
    fun findHolderForDevice(usbDevice: UsbDevice): UsbDeviceConnectionHolder? = activeDevices[usbDevice.deviceId]
    fun getAnyActiveHolder(): UsbDeviceConnectionHolder? = activeDevices.values.firstOrNull()

    /** A health query must never interrupt a filesystem session or race a new one opening. */
    fun <T> withIdleDeviceProbe(deviceId: Int, probe: (UsbDeviceConnectionHolder) -> T): T? =
        synchronized(activeSessionLock) {
            val holder = activeDevices[deviceId] ?: return@synchronized null
            if (!holder.transportHealthy || activeSessions.keys.any { it.startsWith("usb://$deviceId/") }) {
                return@synchronized null
            }
            synchronized(holder.scsiDevice) { probe(holder) }
        }

    /** Cache of discovered BitLocker USB partitions by devicePath */
    private val discoveredPartitions = ConcurrentHashMap<String, UsbPartitionInfo>()

    /** Active worker sessions by devicePath */
    private val activeSessions = ConcurrentHashMap<String, UsbSession>()
    private val activeSessionLock = Any()

    fun getPartitionInfo(devicePath: String): UsbPartitionInfo? = discoveredPartitions[devicePath]

    /** Devices probed or system-mounted and confirmed to contain NO BitLocker partitions. Keyed by "vid:pid" */
    private val nonBitLockerDeviceKeys = ConcurrentHashMap.newKeySet<String>()

    /** Devices where USB permission was denied by the user. Keyed by "vid:pid" */
    private val deniedDeviceKeys = ConcurrentHashMap.newKeySet<String>()

    private val scanLock = Any()

    private data class PartitionDiscoveryResult(
        val partitions: List<UsbPartitionInfo>,
        val completed: Boolean,
    )

    /**
     * Checks whether this UsbDevice is currently mounted by Android system (vold)
     * as an unencrypted volume (e.g. FAT32, exFAT, etc.).
     * If mounted by system, it is guaranteed to be an unencrypted drive, and we must
     * NOT request USB host permission or claim the interface.
     */
    fun isDeviceMountedBySystem(context: Context, device: UsbDevice): Boolean {
        val sm = context.getSystemService(Context.STORAGE_SERVICE) as? android.os.storage.StorageManager ?: return false
        val mountedRemovableVolumes = sm.storageVolumes.filter {
            it.isRemovable && (it.state == android.os.Environment.MEDIA_MOUNTED || it.state == android.os.Environment.MEDIA_MOUNTED_READ_ONLY)
        }
        if (mountedRemovableVolumes.isEmpty()) {
            return false
        }

        val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
        val massStorageDevices = usbManager?.deviceList?.values?.filter { isMassStorageDevice(it) } ?: emptyList()

        if (massStorageDevices.size <= 1) {
            LogFile.write("app", "isDeviceMountedBySystem: single USB mass storage device matches mounted volume (${device.deviceName})")
            return true
        }

        // Multiple USB devices connected: correlate via sysfs
        try {
            val devParts = device.deviceName.split('/')
            if (devParts.size >= 2) {
                val targetBus = devParts[devParts.size - 2].toIntOrNull()
                val targetDev = devParts.last().toIntOrNull()

                val sysUsbDir = File("/sys/bus/usb/devices")
                if (sysUsbDir.exists() && sysUsbDir.isDirectory) {
                    val entries = sysUsbDir.listFiles() ?: emptyArray()
                    for (entry in entries) {
                        val busFile = File(entry, "busnum")
                        val devFile = File(entry, "devnum")
                        if (busFile.exists() && devFile.exists()) {
                            val b = busFile.readText().trim().toIntOrNull()
                            val d = devFile.readText().trim().toIntOrNull()
                            if (b == targetBus && d == targetDev) {
                                if (hasMountedBlockUnderSysfs(entry)) {
                                    LogFile.write("app", "isDeviceMountedBySystem: sysfs matched mounted block device for ${device.deviceName}")
                                    return true
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            LogFile.write("app", "isDeviceMountedBySystem sysfs check error: ${e.message}")
        }

        val devDesc = "${device.manufacturerName.orEmpty()} ${device.productName.orEmpty()}".trim().lowercase()
        for (vol in mountedRemovableVolumes) {
            val vDesc = vol.getDescription(context)?.trim()?.lowercase().orEmpty()
            if (vDesc.isNotEmpty() && devDesc.isNotEmpty() && (devDesc.contains(vDesc) || vDesc.contains(devDesc))) {
                LogFile.write("app", "isDeviceMountedBySystem: description matched '$vDesc' with '$devDesc'")
                return true
            }
        }

        return true
    }

    private fun hasMountedBlockUnderSysfs(usbDir: File): Boolean {
        val mountedMinors = mutableSetOf<String>()
        try {
            File("/proc/mounts").forEachLine { line ->
                if (line.contains("/mnt/media_rw/") || line.contains("/storage/")) {
                    val first = line.substringBefore(' ').trim()
                    if (first.contains("public:")) {
                        val minor = first.substringAfter("public:").replace(',', ':')
                        mountedMinors.add(minor)
                    }
                }
            }
        } catch (_: Exception) {}

        fun checkDir(dir: File, depth: Int): Boolean {
            if (depth > 6) return false
            val files = dir.listFiles() ?: return false
            for (f in files) {
                if (f.name == "block" && f.isDirectory) {
                    val blockDirs = f.listFiles() ?: continue
                    for (b in blockDirs) {
                        val devFile = File(b, "dev")
                        if (devFile.exists() && mountedMinors.contains(devFile.readText().trim())) {
                            return true
                        }
                        val subFiles = b.listFiles() ?: continue
                        for (sub in subFiles) {
                            val subDev = File(sub, "dev")
                            if (subDev.exists() && mountedMinors.contains(subDev.readText().trim())) {
                                return true
                            }
                        }
                    }
                } else if (f.isDirectory && !f.name.startsWith("ep_")) {
                    if (checkDir(f, depth + 1)) return true
                }
            }
            return false
        }

        return checkDir(usbDir, 0)
    }

    fun onPermissionDenied(device: UsbDevice) {
        val devKey = "${device.vendorId}:${device.productId}"
        LogFile.write("app", "UsbStorageManager: USB permission denied by user for $devKey (${device.deviceName})")
        deniedDeviceKeys.add(devKey)
    }

    /**
     * Checks if a UsbDevice implements USB Mass Storage Class (BOT).
     */
    fun isMassStorageDevice(device: UsbDevice): Boolean {
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == UsbConstants.USB_CLASS_MASS_STORAGE &&
                iface.interfaceSubclass == 0x06 && // SCSI transparent command set
                iface.interfaceProtocol == 0x50 // Bulk-Only Transport (BOT)
            ) {
                return true
            }
        }
        return false
    }

    /**
     * Finds the primary Mass Storage interface and Bulk IN/OUT endpoints.
     */
    private fun findMassStorageInterface(device: UsbDevice): Triple<UsbInterface, UsbEndpoint, UsbEndpoint>? {
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == UsbConstants.USB_CLASS_MASS_STORAGE &&
                iface.interfaceSubclass == 0x06 &&
                iface.interfaceProtocol == 0x50
            ) {
                var inEp: UsbEndpoint? = null
                var outEp: UsbEndpoint? = null
                for (j in 0 until iface.endpointCount) {
                    val ep = iface.getEndpoint(j)
                    if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                        if (ep.direction == UsbConstants.USB_DIR_IN && inEp == null) {
                            inEp = ep
                        } else if (ep.direction == UsbConstants.USB_DIR_OUT && outEp == null) {
                            outEp = ep
                        }
                    }
                }
                if (inEp != null && outEp != null) {
                    return Triple(iface, inEp, outEp)
                }
            }
        }
        return null
    }

    /**
     * Requests USB permission from the user for the specified device.
     */
    fun requestPermission(context: Context, device: UsbDevice) {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return
        // An explicit retry must also lift scan suppression from an earlier denial.
        deniedDeviceKeys.remove("${device.vendorId}:${device.productId}")
        if (usbManager.hasPermission(device)) return

        LogFile.write("app", "Requesting USB permission for ${device.deviceName} (vid=${device.vendorId}, pid=${device.productId})")
        val intent = Intent(ACTION_USB_PERMISSION).apply {
            setPackage(context.packageName)
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getBroadcast(context, 0, intent, flags)
        usbManager.requestPermission(device, pendingIntent)
    }

    /**
     * Scans all connected USB devices for BitLocker partitions.
     * Returns list of discovered BitLocker partition descriptors.
     */
    fun scanUsbDevices(context: Context): List<UsbPartitionInfo> {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return emptyList()
        val deviceList = try { usbManager.deviceList } catch (e: Exception) {
            Log.e(TAG, "Failed to get USB device list", e)
            return emptyList()
        }

        val results = mutableListOf<UsbPartitionInfo>()
        val currentKeys = deviceList.values.filter { isMassStorageDevice(it) }.map { "${it.vendorId}:${it.productId}" }.toSet()
        val currentDeviceIds = deviceList.values.filter { isMassStorageDevice(it) }.map { it.deviceId }.toSet()

        // Clean up detached devices
        for ((devId, _) in activeDevices) {
            if (!currentDeviceIds.contains(devId)) {
                LogFile.write("app", "UsbStorageManager: device $devId detached, cleaning up")
                closeDevice(devId)
            }
        }
        nonBitLockerDeviceKeys.retainAll(currentKeys)
        deniedDeviceKeys.retainAll(currentKeys)

        for (device in deviceList.values) {
            if (!isMassStorageDevice(device)) continue

            val devKey = "${device.vendorId}:${device.productId}"

            // A successfully identified holder remains authoritative until detach. In particular,
            // do this before VID:PID and Android-mount filters so a later system mount of a sibling
            // partition cannot hide already discovered BitLocker paths.
            val cachedHolder = activeDevices[device.deviceId]
            if (cachedHolder?.hasCachedBitLockerPartitions == true) {
                val cached = synchronized(scanLock) {
                    cachedHolder.partitions.toList().ifEmpty {
                        discoveredPartitions.values.filter { it.deviceId == device.deviceId }
                    }
                }
                results.addAll(cached)
                LogFile.write("app", "UsbStorageManager: reusing ${cached.size} cached BitLocker partitions for USB device ${device.deviceId} until detach")
                continue
            }

            // If already verified to be an unencrypted/non-BitLocker drive, leave it to the Android OS
            if (nonBitLockerDeviceKeys.contains(devKey)) {
                continue
            }

            // If user previously denied USB permission for this drive, do not ask again
            if (deniedDeviceKeys.contains(devKey)) {
                continue
            }

            // Check if Android OS has already mounted this device as an unencrypted volume
            if (isDeviceMountedBySystem(context, device)) {
                LogFile.write("app", "UsbStorageManager: device ${device.deviceName} ($devKey) is mounted by system as unencrypted volume. Leaving to OS.")
                nonBitLockerDeviceKeys.add(devKey)
                continue
            }

            if (!usbManager.hasPermission(device)) {
                LogFile.write("app", "UsbStorageManager: device ${device.deviceName} ($devKey) has no permission, requesting...")
                requestPermission(context, device)
                continue
            }

            synchronized(scanLock) {
                try {
                    val holder = getOrCreateDeviceHolder(usbManager, device)
                    if (holder == null) {
                        LogFile.write("app", "UsbStorageManager: failed to initialize SCSI driver for ${device.deviceName}")
                        return@synchronized
                    }

                    // Do not issue fresh BOT transactions while a native session is using this device.
                    // Return the cached scan so detection keeps these paths present.
                    if (holder.hasCachedBitLockerPartitions || hasActiveSessionForDevice(device.deviceId)) {
                        val cached = holder.partitions.toList().ifEmpty {
                            discoveredPartitions.values.filter { it.deviceId == device.deviceId }
                        }
                        results.addAll(cached)
                        LogFile.write("app", "UsbStorageManager: skipping partition rescan for USB device ${device.deviceId}; reusing ${cached.size} cached BitLocker partitions")
                        return@synchronized
                    }

                    // Discovery shares the SCSI lock with partition workers; partial I/O errors are
                    // tracked separately so they cannot be mistaken for a confirmed empty disk.
                    val discovery = discoverPartitions(holder)

                    if (UsbPartitionScanPolicy.isConfirmedEmpty(discovery.completed, discovery.partitions.size)) {
                        val closedAsEmpty = synchronized(activeSessionLock) {
                            val sessionStartedDuringScan = activeSessions.keys.any {
                                it.startsWith("usb://${device.deviceId}/")
                            }
                            if (sessionStartedDuringScan) {
                                false
                            } else {
                                LogFile.write("app", "UsbStorageManager: device ${device.deviceName} ($devKey) has NO BitLocker partitions. Releasing interface so Android OS can mount it.")
                                nonBitLockerDeviceKeys.add(devKey)
                                closeDevice(device.deviceId)
                                true
                            }
                        }
                        if (!closedAsEmpty) {
                            val cached = holder.partitions.toList().ifEmpty {
                                discoveredPartitions.values.filter { it.deviceId == device.deviceId }
                            }
                            results.addAll(cached)
                            LogFile.write("app", "UsbStorageManager: a USB session started during an empty scan; preserving the active holder and cached partitions for ${device.deviceId}")
                        }
                        return@synchronized
                    }

                    if (!discovery.completed) {
                        LogFile.write("app", "UsbStorageManager: incomplete partition scan for ${device.deviceName} ($devKey); keeping cached partitions and retrying on a later scan")
                    } else {
                        val currentPaths = discovery.partitions.mapTo(HashSet()) { it.devicePath }
                        holder.partitions.filter { it.devicePath !in currentPaths }.forEach { stale ->
                            discoveredPartitions.remove(stale.devicePath)
                            DeviceIdentity.invalidate(stale.devicePath)
                        }
                    }

                    val partitions = if (discovery.completed) {
                        discovery.partitions
                    } else {
                        (discovery.partitions + holder.partitions).distinctBy { it.devicePath }
                    }
                    holder.partitions.clear()
                    holder.partitions.addAll(partitions)
                    if (partitions.isNotEmpty()) {
                        // Discovery itself happens before openSession can see a published path.
                        // Set this before publishing paths to prevent later scans from racing the
                        // native auto-unlock/openSession setup window.
                        holder.hasCachedBitLockerPartitions = true
                    }

                    for (p in partitions) {
                        discoveredPartitions[p.devicePath] = p
                        DeviceIdentity.invalidate(p.devicePath)
                        results.add(p)
                        LogFile.write("app", "UsbStorageManager: BitLocker partition found -> ${p.devicePath} guid=${p.guid} rkId=${p.recoveryKeyId}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error scanning USB device ${device.deviceName}", e)
                }
            }
        }

        return results
    }

    private fun hasActiveSessionForDevice(deviceId: Int): Boolean = synchronized(activeSessionLock) {
        activeSessions.keys.any { it.startsWith("usb://$deviceId/") }
    }

    private fun getOrCreateDeviceHolder(usbManager: UsbManager, device: UsbDevice): UsbDeviceConnectionHolder? {
        activeDevices[device.deviceId]?.let { return it }

        val mscInfo = findMassStorageInterface(device) ?: return null
        val (iface, inEp, outEp) = mscInfo

        val conn = usbManager.openDevice(device) ?: run {
            Log.e(TAG, "Failed to open UsbDeviceConnection for ${device.deviceName}")
            return null
        }

        var comm: UsbCommunication? = null
        try {
            Log.i(TAG, "Initializing PipelinedUsbCommunication for ${device.deviceName}")
            // A failed claim/alternate selection must not be bypassed by opening a
            // second connection. The outer failure handler owns cleanup of conn.
            val communication = PipelinedUsbCommunication(conn, iface, inEp, outEp)
            comm = communication

            // MAX LUN inquiry (Control transfer 161, 254)
            val maxLunBuf = ByteArray(1)
            try {
                communication.controlTransfer(161, 254, 0, iface.id, maxLunBuf, 1)
            } catch (_: Exception) {}

            val scsi = ScsiBlockDevice(communication, 0)
            try {
                scsi.init()
            } catch (initializationError: Exception) {
                // Recovery is only safe before this holder is published and while this device has
                // no worker session. Use the BOT class reset, never UsbCommunication.resetDevice(),
                // which issues a device-wide USB reset in libaums.
                if (hasActiveSessionForDevice(device.deviceId)) {
                    throw initializationError
                }

                Log.w(TAG, "SCSI initialization failed for ${device.deviceName}; attempting one BOT reset and retry", initializationError)
                try {
                    resetBotTransport(communication)
                    scsi.init()
                } catch (recoveryError: Exception) {
                    initializationError.addSuppressed(recoveryError)
                    throw initializationError
                }
            }

            val holder = UsbDeviceConnectionHolder(device, conn, communication, scsi)
            activeDevices[device.deviceId] = holder
            return holder
        } catch (e: Throwable) {
            try { comm?.close() } catch (_: Throwable) {}
            try { conn.close() } catch (_: Throwable) {}
            if (e !is Exception) throw e
            Log.e(TAG, "Failed to initialize UsbCommunication / ScsiBlockDevice", e)
            return null
        }
    }

    /** Runs libaums' standard BOT reset recovery without resetting the whole USB device. */
    private fun resetBotTransport(communication: UsbCommunication) {
        val result = communication.controlTransfer(
            0x21, // Host-to-device, class, interface
            0xFF, // Bulk-Only Mass Storage Reset
            0,
            communication.usbInterface.id,
            ByteArray(2),
            0,
        )
        if (result < 0) {
            throw java.io.IOException("Bulk-Only Mass Storage Reset failed (result=$result)")
        }

        communication.clearFeatureHalt(communication.inEndpoint)
        communication.clearFeatureHalt(communication.outEndpoint)
    }

    private data class RawPartition(
        val index: Int,
        val startLba: Long,
        val sectorCount: Long,
        val sectorSize: Int = 512
    )

    /**
     * Parses MBR, EBR (logical chain), and GPT partition tables to discover all partitions.
     */
    private fun discoverPartitions(holder: UsbDeviceConnectionHolder): PartitionDiscoveryResult =
        synchronized(holder.scsiDevice) {
            discoverPartitionsLocked(holder)
        }

    private fun discoverPartitionsLocked(holder: UsbDeviceConnectionHolder): PartitionDiscoveryResult {
        val scsi = holder.scsiDevice
        val dev = holder.usbDevice
        val sectorSize = if (scsi.blockSize > 0) scsi.blockSize else 512

        val rawPartitions = mutableListOf<RawPartition>()
        var scanCompleted = true

        // 1. Read LBA 0 (MBR)
        val mbrBuffer = ByteBuffer.allocate(sectorSize).order(ByteOrder.LITTLE_ENDIAN)
        try {
            scsi.read(0L, mbrBuffer)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read LBA 0", e)
            return PartitionDiscoveryResult(emptyList(), completed = false)
        }
        mbrBuffer.flip()

        val isMbrValid = mbrBuffer.limit() >= 512 &&
                (mbrBuffer.get(510).toInt() and 0xFF) == 0x55 &&
                (mbrBuffer.get(511).toInt() and 0xFF) == 0xAA

        var hasGpt = false
        var extendedBaseLba = 0L

        if (isMbrValid) {
            for (i in 0 until 4) {
                val off = 446 + i * 16
                val type = mbrBuffer.get(off + 4).toInt() and 0xFF
                val startLba = mbrBuffer.getInt(off + 8).toLong() and 0xFFFFFFFFL
                val count = mbrBuffer.getInt(off + 12).toLong() and 0xFFFFFFFFL

                if (type == 0xEE) {
                    hasGpt = true
                    break
                }
                if (type in listOf(0x05, 0x0F, 0x85)) {
                    extendedBaseLba = startLba
                } else if (type != 0 && count > 0) {
                    rawPartitions.add(RawPartition(i + 1, startLba, count, sectorSize))
                }
            }

            // Follow EBR chain for extended partitions
            if (extendedBaseLba > 0L) {
                var curEbrLba = extendedBaseLba
                var ebrIdx = 5
                val ebrBuf = ByteBuffer.allocate(sectorSize).order(ByteOrder.LITTLE_ENDIAN)

                while (curEbrLba > 0L && ebrIdx < 64) {
                    ebrBuf.clear()
                    try {
                        scsi.read(curEbrLba, ebrBuf)
                        ebrBuf.flip()
                        if ((ebrBuf.get(510).toInt() and 0xFF) != 0x55 || (ebrBuf.get(511).toInt() and 0xFF) != 0xAA) {
                            scanCompleted = false
                            break
                        }

                        // Entry 0: logical partition
                        val pStart = curEbrLba + (ebrBuf.getInt(446 + 8).toLong() and 0xFFFFFFFFL)
                        val pCount = ebrBuf.getInt(446 + 12).toLong() and 0xFFFFFFFFL
                        val pType = ebrBuf.get(446 + 4).toInt() and 0xFF

                        if (pCount > 0 && pType != 0) {
                            rawPartitions.add(RawPartition(ebrIdx, pStart, pCount, sectorSize))
                        }

                        // Entry 1: next EBR (relative to extendedBaseLba)
                        val nextRel = ebrBuf.getInt(462 + 8).toLong() and 0xFFFFFFFFL
                        val nextCount = ebrBuf.getInt(462 + 12).toLong() and 0xFFFFFFFFL
                        if (nextRel == 0L || nextCount == 0L) {
                            break
                        }
                        curEbrLba = extendedBaseLba + nextRel
                        ebrIdx++
                    } catch (e: Exception) {
                        Log.e(TAG, "Error reading EBR at LBA $curEbrLba", e)
                        scanCompleted = false
                        break
                    }
                }
            }
        }

        // 2. Parse GPT if present
        if (hasGpt) {
            rawPartitions.clear()
            val gptBuf = ByteBuffer.allocate(sectorSize).order(ByteOrder.LITTLE_ENDIAN)
            try {
                scsi.read(1L, gptBuf)
                gptBuf.flip()
                val sig = gptBuf.getLong(0)
                // Signature: "EFI PART" (0x5452415020494645L)
                if (sig == 0x5452415020494645L) {
                    val partEntriesLba = gptBuf.getLong(72)
                    val numParts = gptBuf.getInt(80)
                    val partEntrySize = gptBuf.getInt(84)
                    if (numParts <= 0 || numParts > 16384 || partEntrySize < 128 || partEntrySize > 4096) {
                        throw java.io.IOException("Invalid GPT partition entry geometry")
                    }

                    val entriesBuf = ByteBuffer.allocate(numParts * partEntrySize).order(ByteOrder.LITTLE_ENDIAN)
                    val sectorsToRead = (numParts * partEntrySize + sectorSize - 1) / sectorSize
                    for (s in 0 until sectorsToRead) {
                        val sBuf = ByteBuffer.allocate(sectorSize)
                        scsi.read(partEntriesLba + s, sBuf)
                        sBuf.flip()
                        entriesBuf.put(sBuf)
                    }
                    entriesBuf.flip()

                    for (p in 0 until numParts) {
                        entriesBuf.position(p * partEntrySize)
                        val guidLow = entriesBuf.getLong()
                        val guidHigh = entriesBuf.getLong()
                        if (guidLow == 0L && guidHigh == 0L) continue // Unused partition entry

                        entriesBuf.position(p * partEntrySize + 32)
                        val startLba = entriesBuf.getLong()
                        val endLba = entriesBuf.getLong()
                        val count = endLba - startLba + 1
                        if (count > 0) {
                            rawPartitions.add(RawPartition(p + 1, startLba, count, sectorSize))
                        }
                    }
                } else {
                    scanCompleted = false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing GPT", e)
                scanCompleted = false
            }
        }

        // 3. Fallback: Super Floppy (whole-disk filesystem)
        if (rawPartitions.isEmpty() && !hasGpt) {
            rawPartitions.add(RawPartition(1, 0L, scsi.blocks, sectorSize))
        }

        // 4. Test each partition for BitLocker signature & extract metadata
        val vendor = dev.manufacturerName ?: ""
        val model = dev.productName ?: "USB Drive"
        val bitlockerPartitions = mutableListOf<UsbPartitionInfo>()

        for (part in rawPartitions) {
            val pBuf = ByteBuffer.allocate(sectorSize)
            try {
                scsi.read(part.startLba, pBuf)
                val bytes = pBuf.array()
                val sig = String(bytes, 3, 8, Charsets.US_ASCII)

                if (sig == "-FVE-FS-" || sig == "MSWIN4.1") {
                    val guids = BitLockerDetector.extractMetadataGuidsFromReader(sig, bytes) { offBytes, cntBytes ->
                        val readBuf = ByteBuffer.allocate(cntBytes)
                        val skipSectors = offBytes / sectorSize
                        val countSectors = cntBytes / sectorSize
                        var cur = part.startLba + skipSectors
                        var remSectors = countSectors
                        val tmpBuf = ByteBuffer.allocate(sectorSize)

                        while (remSectors > 0) {
                            tmpBuf.clear()
                            scsi.read(cur, tmpBuf)
                            tmpBuf.flip()
                            readBuf.put(tmpBuf)
                            cur++
                            remSectors--
                        }
                        readBuf.array()
                    }

                    val devPath = UsbPartitionPathSupport.format(dev.deviceId, part.index)
                    bitlockerPartitions.add(
                        UsbPartitionInfo(
                            deviceId = dev.deviceId,
                            partitionIndex = part.index,
                            startLba = part.startLba,
                            sectorCount = part.sectorCount,
                            sectorSize = part.sectorSize,
                            devicePath = devPath,
                            guid = guids.volumeGuid,
                            recoveryKeyId = guids.recoveryKeyId,
                            vendor = vendor,
                            model = model
                        )
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error checking partition ${part.index} at LBA ${part.startLba}", e)
                scanCompleted = false
            }
        }

        return PartitionDiscoveryResult(bitlockerPartitions, completed = scanCompleted)
    }

    /**
     * Resolves device hardware info (vendor, model, size) for UI display.
     */
    fun getDeviceInfo(devicePath: String): DeviceIdentity.DeviceInfo? {
        val part = discoveredPartitions[devicePath]
        if (part != null) {
            return DeviceIdentity.DeviceInfo(
                vendor = part.vendor,
                model = if (part.partitionIndex > 1) "${part.model} (Part ${part.partitionIndex})" else part.model,
                sizeBytes = part.sectorCount * part.sectorSize
            )
        }
        if (devicePath.startsWith("usb://")) {
            val devIdStr = devicePath.removePrefix("usb://").substringBefore('/')
            val devId = devIdStr.toIntOrNull()
            if (devId != null) {
                val holder = activeDevices[devId]
                if (holder != null) {
                    val matchingParts = discoveredPartitions.values.filter { it.deviceId == devId }
                    val vendor = matchingParts.firstOrNull()?.vendor ?: holder.usbDevice.manufacturerName ?: ""
                    val model = matchingParts.firstOrNull()?.model ?: holder.usbDevice.productName ?: ""
                    val totalBytes = matchingParts.sumOf { it.sectorCount * it.sectorSize }
                    return DeviceIdentity.DeviceInfo(
                        vendor = vendor,
                        model = model,
                        sizeBytes = totalBytes
                    )
                }
            }
        }
        return null
    }

    /**
     * Resolves physical link speed in Mbps from the mass storage bulk endpoint packet size.
     */
    fun getUsbSpeedMbps(devicePath: String): Int? {
        val part = discoveredPartitions[devicePath] ?: return null
        val holder = activeDevices[part.deviceId] ?: return null
        val msc = findMassStorageInterface(holder.usbDevice) ?: return null
        val inEp = msc.second
        return when (inEp.maxPacketSize) {
            1024 -> 5000 // USB 3.0+ SuperSpeed
            512 -> 480  // USB 2.0 HighSpeed
            64 -> 12    // USB 1.1 FullSpeed
            else -> null
        }
    }

    /**
     * Opens a native dislocker worker session for the specified USB partition.
     * Creates a Unix socketpair and background SCSI I/O worker thread.
     */
    fun openSession(devicePath: String): UsbSession? = synchronized(activeSessionLock) {
        openSessionLocked(devicePath)
    }

    private fun openSessionLocked(devicePath: String): UsbSession? {
        val part = discoveredPartitions[devicePath] ?: run {
            Log.e(TAG, "openSession: unknown partition $devicePath")
            return null
        }

        val holder = activeDevices[part.deviceId] ?: run {
            Log.e(TAG, "openSession: USB device ${part.deviceId} is not active")
            return null
        }
        if (!holder.transportHealthy) {
            Log.w(TAG, "openSession: USB transport needs reconnection for ${part.deviceId}")
            return null
        }

        // A second native owner must never replace a worker serving a live core.
        if (activeSessions.containsKey(devicePath)) {
            Log.w(TAG, "openSession: partition already has an active session: $devicePath")
            return null
        }

        val fds = ParcelFileDescriptor.createSocketPair()
        val nativePfd = fds[0]
        val workerPfd = fds[1]

        val rawNativeFd = nativePfd.detachFd() // Native dis_io_destroy will close this

        val driver = PartitionBlockDeviceDriver(
            scsiDevice = holder.scsiDevice,
            startLba = part.startLba,
            sectorCount = part.sectorCount,
            sectorSize = part.sectorSize,
            transportHealth = { holder.transportHealthy }
        )

        val worker = UsbBlockDeviceWorker(driver, workerPfd)
        worker.start()

        val sessionRef = java.util.concurrent.atomic.AtomicReference<UsbSession?>()
        val session = UsbSession(rawNativeFd, workerPfd, worker) {
            synchronized(activeSessionLock) {
                sessionRef.get()?.let { activeSessions.remove(devicePath, it) }
            }
        }
        sessionRef.set(session)
        activeSessions[devicePath] = session
        return session
    }

    /**
     * Sends a SCSI command with no data phase using standard USB BOT (Bulk-Only Transport)
     * CBW (Command Block Wrapper) and CSW (Command Status Wrapper).
     */
    private fun sendScsiCommandNoData(
        comm: UsbCommunication,
        cdb: ByteArray,
        tag: Int
    ): Boolean {
        return try {
            val cbw = ByteBuffer.allocate(31).order(ByteOrder.LITTLE_ENDIAN)
            cbw.putInt(0x43425355) // 'USBC'
            cbw.putInt(tag)
            cbw.putInt(0)          // Data transfer length: 0
            cbw.put(0.toByte())    // Direction: Out (no data)
            cbw.put(0.toByte())    // LUN: 0
            cbw.put(cdb.size.toByte()) // CDB length
            cbw.put(cdb)
            for (i in cdb.size until 16) {
                cbw.put(0.toByte())
            }
            cbw.flip()

            comm.bulkOutTransfer(cbw)

            val csw = ByteBuffer.allocate(13).order(ByteOrder.LITTLE_ENDIAN)
            comm.bulkInTransfer(csw)
            csw.flip()

            if (csw.remaining() >= 13) {
                val sig = csw.getInt()
                val cswTag = csw.getInt()
                val residue = csw.getInt()
                val status = csw.get().toInt() and 0xFF
                status == 0
            } else {
                false
            }
        } catch (e: Exception) {
            Log.w(TAG, "sendScsiCommandNoData error: ${e.message}")
            false
        }
    }

    private fun exposesUas(holder: UsbDeviceConnectionHolder): Boolean =
        (0 until holder.usbDevice.interfaceCount).any {
            val iface = holder.usbDevice.getInterface(it)
            iface.interfaceClass == 8 && iface.interfaceProtocol == 0x62
        }

    internal fun kernelHandoffResetTarget(): Int? {
        val holder = activeDevices.values.singleOrNull() ?: return null
        if (!holder.hasCachedBitLockerPartitions) return null
        return holder.usbDevice.deviceId.takeIf {
            UsbKernelHandoffPolicy.needsReset(holder.usbDevice.vendorId, holder.usbDevice.productId, exposesUas(holder))
        }
    }

    internal fun validateKernelHandoffTarget(deviceId: Int) {
        val holder = activeDevices[deviceId] ?: throw java.io.IOException("USB device not connected")
        check(activeDevices.size == 1) { "Kernel handoff requires one USB storage connection" }
        check(holder.hasCachedBitLockerPartitions && UsbKernelHandoffPolicy.needsReset(
            holder.usbDevice.vendorId, holder.usbDevice.productId, exposesUas(holder)
        )) { "USB device is not a verified kernel handoff target" }
    }

    /** The transition gate must have drained all scans and volume workers before entry. */
    internal fun resetAndReleaseForKernelHandoff(deviceId: Int) {
        synchronized(scanLock) {
            synchronized(activeSessionLock) {
                validateKernelHandoffTarget(deviceId)
                check(activeSessions.isEmpty()) { "USB volume workers are still active" }
                val holder = activeDevices.getValue(deviceId)
                check(holder.transportHealthy) { "USB transport is not healthy" }
                synchronized(holder.scsiDevice) {
                    val syncCdb = byteArrayOf(0x35.toByte(), 0, 0, 0, 0, 0, 0, 0, 0, 0)
                    check(sendScsiCommandNoData(holder.communication, syncCdb, 0x1101)) {
                        "SCSI cache flush failed; reset aborted"
                    }
                    val communication = holder.communication as? PipelinedUsbCommunication
                        ?: throw java.io.IOException("Unexpected USB transport")
                    communication.resetForKernelHandoff()
                }
                closeDevice(deviceId)
            }
        }
    }

    /** Flushes and stops the physical disk for an explicit safe eject. */
    fun spindownDevice(devicePath: String): Boolean {
        if (!devicePath.startsWith("usb://")) return false
        val devIdStr = devicePath.removePrefix("usb://").substringBefore('/')
        val devId = devIdStr.toIntOrNull() ?: return false
        val holder = activeDevices[devId] ?: return false

        // Check if any other sessions on this physical device are still active
        val hasOtherActiveSessions = activeSessions.keys.any { otherPath ->
            otherPath != devicePath && otherPath.startsWith("usb://$devId/")
        }
        if (hasOtherActiveSessions) {
            Log.i(TAG, "spindownDevice: skipping physical spindown for $devicePath as other sessions are active on device $devId")
            return false
        }

        Log.i(TAG, "spindownDevice: issuing SCSI cache sync and START STOP UNIT to USB device $devId")
        try {
            val comm = holder.communication
            synchronized(holder.scsiDevice) {
                // Keep each complete CBW/CSW exchange atomic with partition worker I/O.
                val syncCdb = byteArrayOf(0x35.toByte(), 0, 0, 0, 0, 0, 0, 0, 0, 0)
                val syncSuccess = sendScsiCommandNoData(comm, syncCdb, 0x1001)
                if (!syncSuccess) {
                    Log.w(TAG, "spindownDevice: SCSI SYNCHRONIZE CACHE returned failure on USB device $devId. Aborting spindown.")
                    return false
                }

                val stopCdb = byteArrayOf(0x1B.toByte(), 0, 0, 0, 0x00.toByte(), 0)
                val stopSuccess = sendScsiCommandNoData(comm, stopCdb, 0x1002)
                if (!stopSuccess) {
                    Log.w(TAG, "spindownDevice: SCSI START STOP UNIT returned error or unsupported on USB device $devId")
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "spindownDevice error during SCSI commands", e)
            return false
        }

        closeDevice(devId)
        return true
    }

    /**
     * Handles physical detachment of a USB device.
     */
    fun onDeviceDetached(device: UsbDevice) {
        val devKey = "${device.vendorId}:${device.productId}"
        LogFile.write("app", "UsbStorageManager: onDeviceDetached $devKey (${device.deviceName})")
        nonBitLockerDeviceKeys.remove(devKey)
        deniedDeviceKeys.remove(devKey)
        closeDevice(device.deviceId)
    }

    private fun closeDevice(deviceId: Int) {
        val app = com.bitlockerdroid.util.ContextProvider.app
        // Close sessions for this device
        val toRemoveSessions = activeSessions.filter { it.key.startsWith("usb://$deviceId/") }
        for ((path, session) in toRemoveSessions) {
            synchronized(activeSessionLock) {
                activeSessions.remove(path, session)
                session.close()
            }
            app?.let {
                com.bitlockerdroid.share.LanShareManager.stopSharingForDevice(it, path, null)
            }
            UnlockManager.closeSessionIfPresent(path)
        }

        // Forget partitions
        val toRemoveParts = discoveredPartitions.filter { it.value.deviceId == deviceId }
        for ((path, _) in toRemoveParts) {
            discoveredPartitions.remove(path)
            DeviceIdentity.invalidate(path)
            UnlockManager.forgetDetected(path)
        }

        activeDevices.remove(deviceId)?.let { holder ->
            try { holder.communication.close() } catch (_: Exception) {}
            try { holder.connection.close() } catch (_: Exception) {}
        }
    }

    /**
     * Slices the underlying USB ScsiBlockDevice for a single partition.
     * Translates partition-relative byte offsets to physical disk LBAs.
     */
    private class ChunkDegradedException(val newChunkSize: Int) : Exception("Degraded chunk size to $newChunkSize")

    class PartitionBlockDeviceDriver(
        val scsiDevice: ScsiBlockDevice,
        val startLba: Long,
        val sectorCount: Long,
        val sectorSize: Int = 512,
        private val transportHealth: () -> Boolean = { true }
    ) {
        val isTransportHealthy: Boolean get() = transportHealth()
        val sizeBytes: Long = sectorCount * sectorSize
        @Volatile
        var maxChunkBytes = 128 * 1024 // 128KB default (256 sectors); adaptively degrades to 64KB -> 16KB if device rejects
        private val reusableChunkBuf = ByteBuffer.allocate(128 * 1024)
        private var reusableTempBuf: ByteBuffer? = null

        private fun downgradeChunkSize(failedChunkBytes: Int): Int {
            val target = when {
                failedChunkBytes > 64 * 1024 -> 64 * 1024
                failedChunkBytes > 16 * 1024 -> 16 * 1024
                else -> 16 * 1024
            }
            if (target < maxChunkBytes) {
                Log.w(TAG, "SCSI transfer rejected at ${failedChunkBytes / 1024}KB, adaptively downgrading maxChunkBytes to ${target / 1024}KB")
                maxChunkBytes = target
            }
            return target
        }

        private fun acquireTempBuf(capacity: Int): ByteBuffer {
            val existing = reusableTempBuf
            return if (existing != null && existing.capacity() >= capacity) {
                existing.clear()
                existing.limit(capacity)
                existing
            } else {
                val newBuf = ByteBuffer.allocate(maxOf(capacity, 128 * 1024))
                newBuf.limit(capacity)
                reusableTempBuf = newBuf
                newBuf
            }
        }

        private fun safeScsiRead(lba: Long, buf: ByteBuffer) {
            synchronized(scsiDevice) {
                buf.position(0)
                try {
                    scsiDevice.read(lba, buf)
                } catch (e: Exception) {
                    if (!isTransportHealthy) throw e
                    if (buf.remaining() > 16 * 1024) {
                        val newMax = downgradeChunkSize(buf.remaining())
                        try { scsiDevice.init() } catch (_: Exception) {}
                        throw ChunkDegradedException(newMax)
                    }
                    Log.w(TAG, "SCSI read error at LBA $lba, reinitializing and retrying...", e)
                    try { scsiDevice.init() } catch (_: Exception) {}
                    buf.position(0)
                    scsiDevice.read(lba, buf)
                }
            }
        }

        private fun safeScsiWrite(lba: Long, buf: ByteBuffer) {
            synchronized(scsiDevice) {
                buf.position(0)
                try {
                    scsiDevice.write(lba, buf)
                } catch (e: Exception) {
                    if (!isTransportHealthy) throw e
                    if (buf.remaining() > 16 * 1024) {
                        val newMax = downgradeChunkSize(buf.remaining())
                        try { scsiDevice.init() } catch (_: Exception) {}
                        throw ChunkDegradedException(newMax)
                    }
                    Log.w(TAG, "SCSI write error at LBA $lba, reinitializing and retrying...", e)
                    try { scsiDevice.init() } catch (_: Exception) {}
                    buf.position(0)
                    scsiDevice.write(lba, buf)
                }
            }
        }

        @Synchronized
        fun read(byteOffset: Long, dest: ByteBuffer) {
            if (!isTransportHealthy) throw java.io.IOException("USB transport failed; reconnect required")
            if (byteOffset >= sizeBytes) return
            val toRead = minOf(dest.remaining().toLong(), sizeBytes - byteOffset).toInt()
            if (toRead <= 0) return

            var currentOffset = byteOffset
            var remainingBytes = toRead

            while (remainingBytes > 0) {
                val lba = startLba + (currentOffset / sectorSize)
                val offInSector = (currentOffset % sectorSize).toInt()

                if (offInSector == 0 && (remainingBytes % sectorSize) == 0) {
                    val thisChunk = minOf(remainingBytes, maxChunkBytes)
                    reusableChunkBuf.clear()
                    reusableChunkBuf.limit(thisChunk)
                    try {
                        safeScsiRead(lba, reusableChunkBuf)
                        reusableChunkBuf.flip()
                        dest.put(reusableChunkBuf)
                        currentOffset += thisChunk
                        remainingBytes -= thisChunk
                    } catch (_: ChunkDegradedException) {
                        continue
                    }
                } else {
                    val startSector = lba
                    val endByte = currentOffset + remainingBytes
                    val endSector = startLba + ((endByte + sectorSize - 1) / sectorSize)
                    val numSectors = (endSector - startSector).toInt()
                    val fullBytes = numSectors * sectorSize
                    val tempBuf = acquireTempBuf(fullBytes)

                    var curLba = startSector
                    var rem = fullBytes
                    var failed = false
                    while (rem > 0) {
                        val thisChunk = minOf(rem, maxChunkBytes)
                        reusableChunkBuf.clear()
                        reusableChunkBuf.limit(thisChunk)
                        try {
                            safeScsiRead(curLba, reusableChunkBuf)
                            reusableChunkBuf.flip()
                            tempBuf.put(reusableChunkBuf)
                            curLba += thisChunk / sectorSize
                            rem -= thisChunk
                        } catch (_: ChunkDegradedException) {
                            failed = true
                            break
                        }
                    }
                    if (failed) {
                        continue
                    }
                    tempBuf.flip()
                    tempBuf.position(offInSector)
                    tempBuf.limit(offInSector + remainingBytes)
                    dest.put(tempBuf)
                    remainingBytes = 0
                }
            }
        }

        @Synchronized
        fun write(byteOffset: Long, src: ByteBuffer) {
            if (!isTransportHealthy) throw java.io.IOException("USB transport failed; reconnect required")
            if (byteOffset >= sizeBytes) return
            val toWrite = minOf(src.remaining().toLong(), sizeBytes - byteOffset).toInt()
            if (toWrite <= 0) return

            var currentOffset = byteOffset
            var remainingBytes = toWrite

            while (remainingBytes > 0) {
                val lba = startLba + (currentOffset / sectorSize)
                val offInSector = (currentOffset % sectorSize).toInt()

                if (offInSector == 0 && (remainingBytes % sectorSize) == 0) {
                    val thisChunk = minOf(remainingBytes, maxChunkBytes)
                    reusableChunkBuf.clear()
                    val oldLimit = src.limit()
                    src.limit(src.position() + thisChunk)
                    reusableChunkBuf.put(src)
                    src.limit(oldLimit)
                    reusableChunkBuf.flip()
                    try {
                        safeScsiWrite(lba, reusableChunkBuf)
                        currentOffset += thisChunk
                        remainingBytes -= thisChunk
                    } catch (_: ChunkDegradedException) {
                        src.position(src.position() - thisChunk)
                        continue
                    }
                } else {
                    val startSector = lba
                    val endByte = currentOffset + remainingBytes
                    val endSector = startLba + ((endByte + sectorSize - 1) / sectorSize)
                    val numSectors = (endSector - startSector).toInt()
                    val fullBytes = numSectors * sectorSize
                    val fullBuf = acquireTempBuf(fullBytes)

                    var curLba = startSector
                    var rem = fullBytes
                    var failed = false
                    while (rem > 0) {
                        val thisChunk = minOf(rem, maxChunkBytes)
                        reusableChunkBuf.clear()
                        reusableChunkBuf.limit(thisChunk)
                        try {
                            safeScsiRead(curLba, reusableChunkBuf)
                            reusableChunkBuf.flip()
                            fullBuf.put(reusableChunkBuf)
                            curLba += thisChunk / sectorSize
                            rem -= thisChunk
                        } catch (_: ChunkDegradedException) {
                            failed = true
                            break
                        }
                    }
                    if (failed) {
                        continue
                    }

                    fullBuf.position(offInSector)
                    val srcStartPos = src.position()
                    val oldLimit = src.limit()
                    src.limit(srcStartPos + remainingBytes)
                    fullBuf.put(src)
                    src.limit(oldLimit)
                    fullBuf.clear()

                    curLba = startSector
                    rem = fullBytes
                    while (rem > 0) {
                        val thisChunk = minOf(rem, maxChunkBytes)
                        reusableChunkBuf.clear()
                        fullBuf.limit(fullBuf.position() + thisChunk)
                        reusableChunkBuf.put(fullBuf)
                        reusableChunkBuf.flip()
                        try {
                            safeScsiWrite(curLba, reusableChunkBuf)
                            curLba += thisChunk / sectorSize
                            rem -= thisChunk
                        } catch (_: ChunkDegradedException) {
                            failed = true
                            break
                        }
                    }
                    if (failed) {
                        src.position(srcStartPos)
                        continue
                    }
                    remainingBytes = 0
                }
            }
        }
    }

    /** Dedicated worker; each response confirms completed partition I/O. */
    class UsbBlockDeviceWorker(
        val partitionDriver: PartitionBlockDeviceDriver,
        val workerPfd: ParcelFileDescriptor
    ) : Thread("UsbBlockWorker-${partitionDriver.startLba}") {
        @Volatile
        var running = true

        fun closeWorker() {
            running = false
            interrupt()
        }

        override fun run() {
            Log.i(TAG, "UsbBlockDeviceWorker starting for LBA ${partitionDriver.startLba}, pfd=${workerPfd.fd}")
            val input = FileInputStream(workerPfd.fileDescriptor)
            val output = FileOutputStream(workerPfd.fileDescriptor)
            try {
                UsbBlockProtocol(
                    partitionDriver.sizeBytes,
                    partitionDriver::read,
                    partitionDriver::write
                ) { command, offset, length, error ->
                    Log.e(TAG, "USB block I/O failed: command=$command offset=$offset length=$length", error)
                }.serve(input, output) { running }
            } catch (error: Exception) {
                if (running) Log.w(TAG, "USB block channel ended", error)
            } finally {
                running = false
                try { input.close() } catch (_: Exception) {}
                try { output.close() } catch (_: Exception) {}
                try { workerPfd.close() } catch (_: Exception) {}
            }
        }
    }
}
