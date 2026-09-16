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
            worker.running = false
            try { workerPfd.close() } catch (_: Exception) {}
            try { worker.join(1000) } catch (_: Exception) {}
        }
    }

    /** Cache of active USB device connections by deviceId */
    private val activeDevices = ConcurrentHashMap<Int, UsbDeviceConnectionHolder>()

    /** Cache of discovered BitLocker USB partitions by devicePath */
    private val discoveredPartitions = ConcurrentHashMap<String, UsbPartitionInfo>()

    /** Active worker sessions by devicePath */
    private val activeSessions = ConcurrentHashMap<String, UsbSession>()

    private val scanLock = Any()

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
        val currentDeviceIds = deviceList.values.filter { isMassStorageDevice(it) }.map { it.deviceId }.toSet()

        // Clean up detached devices
        for ((devId, holder) in activeDevices) {
            if (!currentDeviceIds.contains(devId)) {
                LogFile.write("app", "UsbStorageManager: device $devId detached, cleaning up")
                closeDevice(devId)
            }
        }

        for (device in deviceList.values) {
            if (!isMassStorageDevice(device)) continue

            if (!usbManager.hasPermission(device)) {
                LogFile.write("app", "UsbStorageManager: device ${device.deviceName} has no permission, requesting...")
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
            val comm = UsbCommunicationFactory.createUsbCommunication(
                usbManager, device, iface, outEp, inEp
            )

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
        closeDevice(device.deviceId)
    }

    private fun closeDevice(deviceId: Int) {
        // Close sessions for this device
        val toRemoveSessions = activeSessions.filter { it.key.startsWith("usb://$deviceId/") }
        for ((path, session) in toRemoveSessions) {
            session.close()
            activeSessions.remove(path)
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
    class PartitionBlockDeviceDriver(
        val scsiDevice: ScsiBlockDevice,
        val startLba: Long,
        val sectorCount: Long,
        val sectorSize: Int = 512
    ) {
        val sizeBytes: Long = sectorCount * sectorSize
        private val maxChunkBytes = 16 * 1024 // 16KB matches Linux MAX_USBFS_BUFFER_SIZE

        private fun safeScsiRead(lba: Long, buf: ByteBuffer) {
            synchronized(scsiDevice) {
                buf.position(0)
                try {
                    scsiDevice.read(lba, buf)
                } catch (e: Exception) {
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

            val lba = startLba + (byteOffset / sectorSize)
            val offInSector = (byteOffset % sectorSize).toInt()

            if (offInSector == 0 && (toRead % sectorSize) == 0) {
                var currentLba = lba
                var remainingBytes = toRead
                val chunkBuf = ByteBuffer.allocate(maxChunkBytes * 2)

                while (remainingBytes > 0) {
                    val thisChunk = minOf(remainingBytes, maxChunkBytes)
                    chunkBuf.clear()
                    chunkBuf.limit(thisChunk)
                    safeScsiRead(currentLba, chunkBuf)
                    chunkBuf.flip()
                    dest.put(chunkBuf)
                    val blocksRead = thisChunk / sectorSize
                    currentLba += blocksRead
                    remainingBytes -= thisChunk
                }
            } else {
                val startSector = lba
                val endByte = byteOffset + toRead
                val endSector = startLba + ((endByte + sectorSize - 1) / sectorSize)
                val numSectors = (endSector - startSector).toInt()
                val fullBytes = numSectors * sectorSize
                val tempBuf = ByteBuffer.allocate(fullBytes)

                var curLba = startSector
                var rem = fullBytes
                val chunkBuf = ByteBuffer.allocate(maxChunkBytes * 2)
                while (rem > 0) {
                    val thisChunk = minOf(rem, maxChunkBytes)
                    chunkBuf.clear()
                    chunkBuf.limit(thisChunk)
                    safeScsiRead(curLba, chunkBuf)
                    chunkBuf.flip()
                    tempBuf.put(chunkBuf)
                    curLba += thisChunk / sectorSize
                    rem -= thisChunk
                }
                tempBuf.flip()
                tempBuf.position(offInSector)
                tempBuf.limit(offInSector + toRead)
                dest.put(tempBuf)
            }
        }

        @Synchronized
        fun write(byteOffset: Long, src: ByteBuffer) {
            if (byteOffset >= sizeBytes) return
            val toWrite = minOf(src.remaining().toLong(), sizeBytes - byteOffset).toInt()
            if (toWrite <= 0) return

            val lba = startLba + (byteOffset / sectorSize)
            val offInSector = (byteOffset % sectorSize).toInt()

            if (offInSector == 0 && (toWrite % sectorSize) == 0) {
                var currentLba = lba
                var remainingBytes = toWrite
                val chunkBuf = ByteBuffer.allocate(maxChunkBytes * 2)

                while (remainingBytes > 0) {
                    val thisChunk = minOf(remainingBytes, maxChunkBytes)
                    chunkBuf.clear()
                    val oldLimit = src.limit()
                    src.limit(src.position() + thisChunk)
                    chunkBuf.put(src)
                    src.limit(oldLimit)
                    chunkBuf.flip()
                    safeScsiWrite(currentLba, chunkBuf)
                    val blocksWritten = thisChunk / sectorSize
                    currentLba += blocksWritten
                    remainingBytes -= thisChunk
                }
            } else {
                val startSector = lba
                val endByte = byteOffset + toWrite
                val endSector = startLba + ((endByte + sectorSize - 1) / sectorSize)
                val numSectors = (endSector - startSector).toInt()
                val fullBytes = numSectors * sectorSize
                val fullBuf = ByteBuffer.allocate(fullBytes)

                var curLba = startSector
                var rem = fullBytes
                val chunkBuf = ByteBuffer.allocate(maxChunkBytes * 2)
                while (rem > 0) {
                    val thisChunk = minOf(rem, maxChunkBytes)
                    chunkBuf.clear()
                    chunkBuf.limit(thisChunk)
                    safeScsiRead(curLba, chunkBuf)
                    chunkBuf.flip()
                    fullBuf.put(chunkBuf)
                    curLba += thisChunk / sectorSize
                    rem -= thisChunk
                }

                fullBuf.position(offInSector)
                val oldLimit = src.limit()
                src.limit(src.position() + toWrite)
                fullBuf.put(src)
                src.limit(oldLimit)
                fullBuf.clear()

                curLba = startSector
                rem = fullBytes
                while (rem > 0) {
                    val thisChunk = minOf(rem, maxChunkBytes)
                    chunkBuf.clear()
                    fullBuf.limit(fullBuf.position() + thisChunk)
                    chunkBuf.put(fullBuf)
                    chunkBuf.flip()
                    safeScsiWrite(curLba, chunkBuf)
                    curLba += thisChunk / sectorSize
                    rem -= thisChunk
                }
            }
        }
    }

    /**
     * Dedicated background worker servicing binary pread/pwrite requests over Unix domain socketpair.
     */
    class UsbBlockDeviceWorker(
        val partitionDriver: PartitionBlockDeviceDriver,
        val workerPfd: ParcelFileDescriptor
    ) : Thread("UsbBlockWorker-${partitionDriver.startLba}") {

        @Volatile
        var running = true

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
                    Log.i(TAG, "UsbBlockDeviceWorker received cmd=$cmd")
                    if (cmd == -1 || cmd == CMD_EXIT) break

                    when (cmd) {
                        CMD_READ -> {
                            readFully(inStream, headerBuf, 0, 12)
                            val offset = readLongLE(headerBuf, 0)
                            val len = readIntLE(headerBuf, 8)
                            Log.i(TAG, "UsbBlockDeviceWorker CMD_READ offset=$offset, len=$len")
                            if (len <= 0) break

                            val dataBuf = ByteBuffer.allocate(len)
                            try {
                                partitionDriver.read(offset, dataBuf)
                                dataBuf.flip()
                                writeIntLE(statusBuf, 0, len)
                                outStream.write(statusBuf, 0, 4)
                                outStream.write(dataBuf.array(), dataBuf.arrayOffset(), len)
                                outStream.flush()
                                Log.i(TAG, "UsbBlockDeviceWorker CMD_READ success sent $len bytes")
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

                            val dataBuf = ByteBuffer.allocate(len)
                            readFully(inStream, dataBuf.array(), dataBuf.arrayOffset(), len)
                            try {
                                partitionDriver.write(offset, dataBuf)
                                writeIntLE(statusBuf, 0, len)
                                outStream.write(statusBuf, 0, 4)
                                outStream.flush()
                            } catch (e: Exception) {
                                Log.e(TAG, "Worker CMD_WRITE error at offset $offset len $len", e)
                                writeIntLE(statusBuf, 0, -5) // -EIO
                                outStream.write(statusBuf, 0, 4)
                                outStream.flush()
                            }
                        }
                        CMD_SYNC -> {
                            writeIntLE(statusBuf, 0, 0)
                            outStream.write(statusBuf, 0, 4)
                            outStream.flush()
                        }
                        CMD_SIZE -> {
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
