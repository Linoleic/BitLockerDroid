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
import me.jahnen.libaums.core.usb.UsbCommunicationFactory
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future

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

    private const val DAEMON_MAGIC = 0x4249544CL // 'BITL'
    private const val CMD_EXIT = 0
    private const val CMD_READ = 1
    private const val CMD_WRITE = 2
    private const val CMD_SYNC = 3
    private const val CMD_SIZE = 4

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
    )

    class UsbSession(
        val nativeFd: Int,
        val workerPfd: ParcelFileDescriptor,
        val worker: UsbBlockDeviceWorker
    ) : AutoCloseable {
        override fun close() {
            worker.closeWorker()
            try { workerPfd.close() } catch (_: Exception) {}
            try { worker.join(2000) } catch (_: Exception) {}
        }
    }

    /** Cache of active USB device connections by deviceId */
    private val activeDevices = ConcurrentHashMap<Int, UsbDeviceConnectionHolder>()

    /** Cache of discovered BitLocker USB partitions by devicePath */
    private val discoveredPartitions = ConcurrentHashMap<String, UsbPartitionInfo>()

    /** Active worker sessions by devicePath */
    private val activeSessions = ConcurrentHashMap<String, UsbSession>()

    /** Devices probed or system-mounted and confirmed to contain NO BitLocker partitions. Keyed by "vid:pid" */
    private val nonBitLockerDeviceKeys = ConcurrentHashMap.newKeySet<String>()

    /** Devices where USB permission was denied by the user. Keyed by "vid:pid" */
    private val deniedDeviceKeys = ConcurrentHashMap.newKeySet<String>()

    private val scanLock = Any()

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

                    // Discover partitions on this USB drive
                    val partitions = discoverPartitions(holder)

                    if (partitions.isEmpty()) {
                        LogFile.write("app", "UsbStorageManager: device ${device.deviceName} ($devKey) has NO BitLocker partitions. Releasing interface so Android OS can mount it.")
                        nonBitLockerDeviceKeys.add(devKey)
                        closeDevice(device.deviceId)
                        return@synchronized
                    }

                    holder.partitions.clear()
                    holder.partitions.addAll(partitions)

                    for (p in partitions) {
                        discoveredPartitions[p.devicePath] = p
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

    private fun getOrCreateDeviceHolder(usbManager: UsbManager, device: UsbDevice): UsbDeviceConnectionHolder? {
        activeDevices[device.deviceId]?.let { return it }

        val mscInfo = findMassStorageInterface(device) ?: return null
        val (iface, inEp, outEp) = mscInfo

        val conn = usbManager.openDevice(device) ?: run {
            Log.e(TAG, "Failed to open UsbDeviceConnection for ${device.deviceName}")
            return null
        }

        try {
            val comm = try {
                Log.i(TAG, "Initializing PipelinedUsbCommunication for ${device.deviceName}")
                PipelinedUsbCommunication(conn, iface, inEp, outEp)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to initialize PipelinedUsbCommunication, falling back to standard communication", e)
                UsbCommunicationFactory.createUsbCommunication(
                    usbManager, device, iface, outEp, inEp
                )
            }

            // MAX LUN inquiry (Control transfer 161, 254)
            val maxLunBuf = ByteArray(1)
            try {
                comm.controlTransfer(161, 254, 0, iface.id, maxLunBuf, 1)
            } catch (_: Exception) {}

            val scsi = ScsiBlockDevice(comm, 0)
            scsi.init()

            val holder = UsbDeviceConnectionHolder(device, conn, comm, scsi)
            activeDevices[device.deviceId] = holder
            return holder
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize UsbCommunication / ScsiBlockDevice", e)
            try { conn.close() } catch (_: Exception) {}
            return null
        }
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
    private fun discoverPartitions(holder: UsbDeviceConnectionHolder): List<UsbPartitionInfo> {
        val scsi = holder.scsiDevice
        val dev = holder.usbDevice
        val sectorSize = if (scsi.blockSize > 0) scsi.blockSize else 512

        val rawPartitions = mutableListOf<RawPartition>()

        // 1. Read LBA 0 (MBR)
        val mbrBuffer = ByteBuffer.allocate(sectorSize).order(ByteOrder.LITTLE_ENDIAN)
        try {
            scsi.read(0L, mbrBuffer)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read LBA 0", e)
            return emptyList()
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
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing GPT", e)
            }
        }

        // 3. Fallback: Super Floppy (whole-disk filesystem)
        if (rawPartitions.isEmpty()) {
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

                    val devPath = "usb://${dev.deviceId}/p${part.index}"
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
            }
        }

        return bitlockerPartitions
    }

    /**
     * Resolves device hardware info (vendor, model, size) for UI display.
     */
    fun getDeviceInfo(devicePath: String): DeviceIdentity.DeviceInfo? {
        val part = discoveredPartitions[devicePath] ?: return null
        return DeviceIdentity.DeviceInfo(
            vendor = part.vendor,
            model = if (part.partitionIndex > 1) "${part.model} (Part ${part.partitionIndex})" else part.model,
            sizeBytes = part.sectorCount * part.sectorSize
        )
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
    fun openSession(devicePath: String): UsbSession? {
        val part = discoveredPartitions[devicePath] ?: run {
            Log.e(TAG, "openSession: unknown partition $devicePath")
            return null
        }

        val holder = activeDevices[part.deviceId] ?: run {
            Log.e(TAG, "openSession: USB device ${part.deviceId} is not active")
            return null
        }

        // Close any prior active session on this path
        activeSessions.remove(devicePath)?.close()

        val fds = ParcelFileDescriptor.createSocketPair()
        val nativePfd = fds[0]
        val workerPfd = fds[1]

        val rawNativeFd = nativePfd.detachFd() // Native dis_io_destroy will close this

        val driver = PartitionBlockDeviceDriver(
            scsiDevice = holder.scsiDevice,
            startLba = part.startLba,
            sectorCount = part.sectorCount,
            sectorSize = part.sectorSize
        )

        val worker = UsbBlockDeviceWorker(driver, workerPfd)
        worker.start()

        val session = UsbSession(rawNativeFd, workerPfd, worker)
        activeSessions[devicePath] = session
        return session
    }

    /**
     * Closes the active session on a partition.
     */
    fun closeSession(devicePath: String) {
        activeSessions.remove(devicePath)?.close()
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
            session.close()
            activeSessions.remove(path)
            app?.let {
                com.bitlockerdroid.share.LanShareManager.stopSharingForDevice(it, path, null)
            }
            UnlockManager.closeSessionIfPresent(path)
        }

        // Forget partitions
        val toRemoveParts = discoveredPartitions.filter { it.value.deviceId == deviceId }
        for ((path, _) in toRemoveParts) {
            discoveredPartitions.remove(path)
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
        val sectorSize: Int = 512
    ) {
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

    /**
     * Dedicated background worker servicing binary pread/pwrite requests over Unix domain socketpair.
     * Implements a 2-stage Ping-Pong overlapping write pipeline to break the stop-and-wait BOT latency barrier.
     */
    class UsbBlockDeviceWorker(
        val partitionDriver: PartitionBlockDeviceDriver,
        val workerPfd: ParcelFileDescriptor
    ) : Thread("UsbBlockWorker-${partitionDriver.startLba}") {

        @Volatile
        var running = true

        private val writeExecutor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "UsbAsyncWriter-${partitionDriver.startLba}").apply { isDaemon = true }
        }

        private class WriteSlot(val id: Int) {
            var buffer: ByteBuffer = ByteBuffer.allocate(256 * 1024)
            var offset: Long = 0L
            var length: Int = 0
            var future: Future<*>? = null

            fun ensureCapacity(len: Int) {
                if (buffer.capacity() < len) {
                    buffer = ByteBuffer.allocate(maxOf(len, buffer.capacity() * 2))
                }
                buffer.clear()
                buffer.limit(len)
            }
        }

        private val slotA = WriteSlot(0)
        private val slotB = WriteSlot(1)
        private var activeSlot = slotA

        @Volatile
        private var deferredError: Int = 0

        private var readBuf = ByteBuffer.allocate(256 * 1024)

        private fun acquireReadBuf(len: Int): ByteBuffer {
            val existing = readBuf
            return if (existing.capacity() >= len) {
                existing.clear()
                existing.limit(len)
                existing
            } else {
                val newBuf = ByteBuffer.allocate(maxOf(len, existing.capacity() * 2))
                newBuf.limit(len)
                readBuf = newBuf
                newBuf
            }
        }

        private fun drainWrites(): Boolean {
            var ok = true
            try {
                slotA.future?.get()
            } catch (e: Exception) {
                Log.e(TAG, "Error draining slotA in worker", e)
                deferredError = -5
                ok = false
            } finally {
                slotA.future = null
            }

            try {
                slotB.future?.get()
            } catch (e: Exception) {
                Log.e(TAG, "Error draining slotB in worker", e)
                deferredError = -5
                ok = false
            } finally {
                slotB.future = null
            }
            return ok && (deferredError == 0)
        }

        fun closeWorker() {
            running = false
            interrupt()
            try { drainWrites() } catch (_: Exception) {}
            try { writeExecutor.shutdownNow() } catch (_: Exception) {}
        }

        override fun run() {
            Log.i(TAG, "UsbBlockDeviceWorker starting for LBA ${partitionDriver.startLba}, pfd=${workerPfd.fd}")
            val inStream = FileInputStream(workerPfd.fileDescriptor)
            val outStream = FileOutputStream(workerPfd.fileDescriptor)
            val headerBuf = ByteArray(12)
            val statusBuf = ByteArray(4)
            val sizeBuf = ByteArray(8)

            try {
                // Handshake with DAEMON_MAGIC (0x4249544CL little-endian)
                writeIntLE(statusBuf, 0, DAEMON_MAGIC.toInt())
                outStream.write(statusBuf, 0, 4)
                outStream.flush()
                Log.i(TAG, "UsbBlockDeviceWorker handshake sent")

                while (running) {
                    val cmd = inStream.read()
                    if (cmd == -1 || cmd == CMD_EXIT) break

                    when (cmd) {
                        CMD_READ -> {
                            // Ensure in-flight writes are flushed for read-after-write consistency
                            drainWrites()

                            readFully(inStream, headerBuf, 0, 12)
                            val offset = readLongLE(headerBuf, 0)
                            val len = readIntLE(headerBuf, 8)
                            if (len <= 0) break

                            val dataBuf = acquireReadBuf(len)
                            try {
                                partitionDriver.read(offset, dataBuf)
                                dataBuf.flip()
                                writeIntLE(statusBuf, 0, len)
                                outStream.write(statusBuf, 0, 4)
                                outStream.write(dataBuf.array(), dataBuf.arrayOffset(), len)
                                outStream.flush()
                            } catch (e: Exception) {
                                Log.e(TAG, "Worker CMD_READ error at offset $offset len $len", e)
                                writeIntLE(statusBuf, 0, -5) // -EIO
                                outStream.write(statusBuf, 0, 4)
                                outStream.flush()
                            }
                        }
                        CMD_WRITE -> {
                            readFully(inStream, headerBuf, 0, 12)
                            val offset = readLongLE(headerBuf, 0)
                            val len = readIntLE(headerBuf, 8)
                            if (len <= 0) break

                            // 1. Wait for active slot's previous write to complete before reusing its buffer
                            val curSlot = activeSlot
                            curSlot.future?.let { f ->
                                try {
                                    f.get()
                                } catch (e: Exception) {
                                    Log.e(TAG, "Worker deferred write error on slot ${curSlot.id}", e)
                                    deferredError = -5
                                } finally {
                                    curSlot.future = null
                                }
                            }

                            // 2. Read incoming payload from socket into active slot's buffer
                            curSlot.ensureCapacity(len)
                            readFully(inStream, curSlot.buffer.array(), curSlot.buffer.arrayOffset(), len)
                            curSlot.offset = offset
                            curSlot.length = len
                            curSlot.buffer.position(0)
                            curSlot.buffer.limit(len)

                            // 3. Propagate deferred error if earlier background write failed
                            if (deferredError != 0) {
                                val err = deferredError
                                deferredError = 0
                                writeIntLE(statusBuf, 0, err) // -5 (-EIO)
                                outStream.write(statusBuf, 0, 4)
                                outStream.flush()
                            } else {
                                // 4. Dispatch active slot to background async writer thread
                                curSlot.future = writeExecutor.submit {
                                    try {
                                        partitionDriver.write(curSlot.offset, curSlot.buffer)
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Async partition write error at offset ${curSlot.offset} len ${curSlot.length}", e)
                                        deferredError = -5
                                        throw e
                                    }
                                }

                                // 5. Immediately send ACK to C driver so it can start preparing/encrypting the NEXT chunk!
                                writeIntLE(statusBuf, 0, len)
                                outStream.write(statusBuf, 0, 4)
                                outStream.flush()

                                // 6. Ping-pong flip to the other slot
                                activeSlot = if (activeSlot === slotA) slotB else slotA
                            }
                        }
                        CMD_SYNC -> {
                            drainWrites()
                            val err = deferredError
                            deferredError = 0
                            writeIntLE(statusBuf, 0, err)
                            outStream.write(statusBuf, 0, 4)
                            outStream.flush()
                        }
                        CMD_SIZE -> {
                            drainWrites()
                            writeLongLE(sizeBuf, 0, partitionDriver.sizeBytes)
                            outStream.write(sizeBuf, 0, 8)
                            outStream.flush()
                        }
                        else -> {
                            Log.w(TAG, "Unknown worker cmd: $cmd")
                            break
                        }
                    }
                }
            } catch (_: Exception) {
                // Pipe closed during session shutdown
            } finally {
                running = false
                try { drainWrites() } catch (_: Exception) {}
                try { writeExecutor.shutdown() } catch (_: Exception) {}
                try { inStream.close() } catch (_: Exception) {}
                try { outStream.close() } catch (_: Exception) {}
                try { workerPfd.close() } catch (_: Exception) {}
            }
        }

        private fun readFully(inStream: FileInputStream, buf: ByteArray, offset: Int, length: Int) {
            var got = 0
            while (got < length) {
                val r = inStream.read(buf, offset + got, length - got)
                if (r < 0) throw java.io.EOFException("Unexpected EOF on worker socket")
                got += r
            }
        }

        private fun writeIntLE(buf: ByteArray, offset: Int, value: Int) {
            buf[offset] = (value and 0xFF).toByte()
            buf[offset + 1] = ((value ushr 8) and 0xFF).toByte()
            buf[offset + 2] = ((value ushr 16) and 0xFF).toByte()
            buf[offset + 3] = ((value ushr 24) and 0xFF).toByte()
        }

        private fun writeLongLE(buf: ByteArray, offset: Int, value: Long) {
            for (i in 0 until 8) {
                buf[offset + i] = ((value ushr (i * 8)) and 0xFFL).toByte()
            }
        }

        private fun readIntLE(buf: ByteArray, offset: Int): Int {
            return (buf[offset].toInt() and 0xFF) or
                    ((buf[offset + 1].toInt() and 0xFF) shl 8) or
                    ((buf[offset + 2].toInt() and 0xFF) shl 16) or
                    ((buf[offset + 3].toInt() and 0xFF) shl 24)
        }

        private fun readLongLE(buf: ByteArray, offset: Int): Long {
            var res = 0L
            for (i in 0 until 8) {
                res = res or ((buf[offset + i].toLong() and 0xFFL) shl (i * 8))
            }
            return res
        }
    }
}
