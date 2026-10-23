package com.bitlockerdroid.smart

import me.jahnen.libaums.core.usb.UsbCommunication
import java.io.IOException
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger

/** BOT failed, recovery succeeded, and a later idle probe may retry safely. */
class RecoveredProbeException(message: String, cause: IOException) : IOException(message, cause)

/** Read-only SMART access for the explicitly supported Realtek RTL9210 bridge. */
object UsbSmartProbe {

    private const val CBW_SIGNATURE = 0x43425355 // "USBC" on the wire
    private const val CSW_SIGNATURE = 0x53425355 // "USBS" on the wire
    private const val CBW_SIZE = 31
    private const val CSW_SIZE = 13
    private const val NVME_IDENTIFY_SIZE = 4096
    private const val NVME_SMART_LOG_SIZE = 512
    private const val NVME_ADMIN_IDENTIFY = 0x06
    private const val NVME_ADMIN_GET_LOG_PAGE = 0x02
    private const val NVME_IDENTIFY_CONTROLLER_CDW10 = 0x01
    private const val NVME_SMART_HEALTH_LOG_ID = 0x02

    private val nextTag = AtomicInteger(1)

    /**
     * Probe only a bridge with a known Realtek identity. Unknown USB devices return before
     * reading any communication property or issuing a USB request.
     */
    fun probeSmart(
        comm: UsbCommunication,
        deviceNode: String? = null,
        vendorId: Int = 0,
        productId: Int = 0
    ): SmartHealthInfo {
        val profile = UsbSmartProfile.profileFor(vendorId, productId)
            ?: return unsupportedUnknownBridge(deviceNode, vendorId, productId)
        return probeProfile(profile, deviceNode, CommunicationBotTransport(comm))
    }

    /** Small transport seam used by JVM tests to exercise BOT without Android USB objects. */
    internal fun probeSmartWithTransport(
        vendorId: Int,
        productId: Int,
        deviceNode: String? = null,
        transport: BotTransport
    ): SmartHealthInfo {
        val profile = UsbSmartProfile.profileFor(vendorId, productId)
            ?: return unsupportedUnknownBridge(deviceNode, vendorId, productId)
        return probeProfile(profile, deviceNode, transport)
    }

    private fun unsupportedUnknownBridge(node: String?, vendorId: Int, productId: Int) =
        SmartHealthInfo.unconfirmed(
            node = node,
            reason = "USB SMART probing is disabled for unrecognized bridge %04x:%04x."
                .format(vendorId and 0xffff, productId and 0xffff)
        )

    private fun probeProfile(
        profile: UsbSmartProfile,
        node: String?,
        transport: BotTransport
    ): SmartHealthInfo {
        val identifyCdb = ByteArray(16).apply {
            this[0] = REALTEK_NVME_CDB.toByte()
            putLe16(this, 1, NVME_IDENTIFY_SIZE)
            this[3] = NVME_ADMIN_IDENTIFY.toByte()
            this[4] = NVME_IDENTIFY_CONTROLLER_CDW10.toByte()
        }
        val identifyResult = executeReadCommand(transport, identifyCdb, NVME_IDENTIFY_SIZE)
        if (identifyResult.commandFailed) {
            return unsupportedForProfile(
                profile,
                node,
                "RTL9210 did not accept NVMe Identify Controller; SATA/SAT fallback is not enabled."
            )
        }

        val identity = parseControllerIdentity(identifyResult.data)
            ?: return unsupportedForProfile(profile, node, "RTL9210 returned an empty or invalid NVMe controller identity.")

        val smartCdb = ByteArray(16).apply {
            this[0] = REALTEK_NVME_CDB.toByte()
            putLe16(this, 1, NVME_SMART_LOG_SIZE)
            this[3] = NVME_ADMIN_GET_LOG_PAGE.toByte()
            this[4] = NVME_SMART_HEALTH_LOG_ID.toByte()
        }
        val smartResult = executeReadCommand(transport, smartCdb, NVME_SMART_LOG_SIZE)
        if (smartResult.commandFailed) {
            return unsupportedForProfile(profile, node, "RTL9210 did not provide the NVMe SMART/Health log.")
        }

        return parseSmartLog(identity, smartResult.data, node)
            ?: unsupportedForProfile(profile, node, "RTL9210 returned an invalid NVMe SMART/Health log.")
    }

    private fun unsupportedForProfile(profile: UsbSmartProfile, node: String?, reason: String) =
        SmartHealthInfo.unconfirmed(
            vendor = "0x%04X".format(profile.vendorId),
            product = profile.name,
            node = node,
            reason = reason
        )

    private data class CommandResult(val data: ByteArray, val commandFailed: Boolean)

    /**
     * Issues one BOT data-in command. A status-1 CSW is a complete SCSI command failure and
     * leaves support unconfirmed; malformed BOT or transport failures attempt one
     * BOT reset recovery and are reported as recoverable or unrecovered IOExceptions.
     */
    private fun executeReadCommand(
        transport: BotTransport,
        cdb: ByteArray,
        expectedLength: Int
    ): CommandResult {
        require(cdb.size == 16)
        val tag = nextTag.getAndIncrement()
        val cbw = ByteBuffer.allocate(CBW_SIZE).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(CBW_SIGNATURE)
            putInt(tag)
            putInt(expectedLength)
            put(0x80.toByte()) // data-in
            put(0) // LUN 0
            put(cdb.size.toByte())
            put(cdb)
            while (hasRemaining()) put(0)
            flip()
        }

        try {
            transferCbwOnce(transport, cbw)
            val data = ByteArray(expectedLength)
            var transferred = 0
            var csw: Csw? = null

            while (transferred < expectedLength && csw == null) {
                val chunk = ByteBuffer.allocate(expectedLength - transferred)
                val read = transferOnceIn(transport, chunk)
                val bytes = chunk.array()
                if (read == CSW_SIZE && readLe32(bytes, 0) == CSW_SIGNATURE) {
                    csw = parseCsw(bytes.copyOf(CSW_SIZE), expectedLength, tag, transferred)
                } else {
                    System.arraycopy(bytes, 0, data, transferred, read)
                    transferred += read
                }
            }

            if (csw == null) {
                csw = readCsw(transport, expectedLength, tag, transferred)
            }

            return when (csw.status) {
                0 -> {
                    if (csw.residue != 0 || transferred != expectedLength) {
                        throw IOException(
                            "BOT success CSW has incomplete data (received=$transferred, " +
                                "expected=$expectedLength, residue=${csw.residue})"
                        )
                    }
                    CommandResult(data, commandFailed = false)
                }
                1 -> CommandResult(data, commandFailed = true)
                else -> throw IOException("BOT CSW reported phase error status ${csw.status}")
            }
        } catch (failure: IOException) {
            recoverOnce(transport, failure)
        }
    }

    private data class Csw(val residue: Int, val status: Int)

    private fun readCsw(
        transport: BotTransport,
        expectedLength: Int,
        tag: Int,
        transferred: Int
    ): Csw {
        val buffer = ByteBuffer.allocate(CSW_SIZE)
        transferFullyIn(transport, buffer)
        return parseCsw(buffer.array(), expectedLength, tag, transferred)
    }

    private fun parseCsw(bytes: ByteArray, expectedLength: Int, tag: Int, transferred: Int): Csw {
        if (bytes.size != CSW_SIZE) throw IOException("BOT CSW must be exactly $CSW_SIZE bytes")
        val signature = readLe32(bytes, 0)
        val returnedTag = readLe32(bytes, 4)
        val residue = readLe32(bytes, 8)
        val status = bytes[12].toInt() and 0xff
        if (signature != CSW_SIGNATURE) throw IOException("Invalid BOT CSW signature")
        if (returnedTag != tag) throw IOException("BOT CSW tag mismatch")
        if (residue < 0 || residue != expectedLength - transferred) {
            throw IOException("BOT CSW residue mismatch (residue=$residue, transferred=$transferred)")
        }
        if (status !in 0..2) throw IOException("Invalid BOT CSW status $status")
        return Csw(residue, status)
    }

    private fun transferCbwOnce(transport: BotTransport, buffer: ByteBuffer) {
        val before = buffer.position()
        val count = transport.bulkOutTransfer(buffer)
        if (count != CBW_SIZE || buffer.position() - before != CBW_SIZE) {
            throw IOException(
                "BOT CBW must be sent in one complete bulk OUT transfer " +
                    "(result=$count, advanced=${buffer.position() - before})"
            )
        }
    }

    private fun transferFullyIn(transport: BotTransport, buffer: ByteBuffer) {
        while (buffer.hasRemaining()) {
            val before = buffer.position()
            val count = transport.bulkInTransfer(buffer)
            validateProgress("bulk IN", count, before, buffer.position(), buffer.limit())
        }
    }

    private fun transferOnceIn(transport: BotTransport, buffer: ByteBuffer): Int {
        val before = buffer.position()
        val count = transport.bulkInTransfer(buffer)
        validateProgress("bulk IN", count, before, buffer.position(), buffer.limit())
        return count
    }

    private fun validateProgress(direction: String, count: Int, before: Int, after: Int, limit: Int) {
        val advanced = after - before
        if (count <= 0) throw IOException("$direction made no progress (result=$count)")
        if (count > limit - before || advanced != count) {
            throw IOException("$direction returned an invalid length (result=$count, advanced=$advanced)")
        }
    }

    private fun recoverOnce(transport: BotTransport, original: IOException): Nothing {
        try {
            transport.resetBotAndClearHalts()
        } catch (recoveryFailure: Exception) {
            original.addSuppressed(recoveryFailure)
            throw IOException("BOT recovery failed after transport/protocol error", original)
        }
        throw RecoveredProbeException(
            "BOT transport recovered after ${original.message}; retry when the device is idle",
            original
        )
    }

    private data class ControllerIdentity(
        val vendorId: Int,
        val serial: String,
        val model: String,
        val firmware: String
    )

    private fun parseControllerIdentity(data: ByteArray): ControllerIdentity? {
        if (data.size != NVME_IDENTIFY_SIZE || data.all { it == 0.toByte() }) return null
        val vendorId = readLe16(data, 0)
        if (vendorId == 0 || vendorId == 0xffff) return null
        val serial = nvmeAscii(data, 4, 20) ?: return null
        val model = nvmeAscii(data, 24, 40) ?: return null
        val firmware = nvmeAscii(data, 64, 8) ?: return null
        if (serial.length < 3 || model.length < 3 || firmware.isBlank()) return null
        return ControllerIdentity(vendorId, serial, model, firmware)
    }

    private fun nvmeAscii(data: ByteArray, offset: Int, size: Int): String? {
        val value = String(data, offset, size, StandardCharsets.US_ASCII).trim(' ', '\u0000')
        if (value.isBlank() || value.any { it !in ' '..'~' }) return null
        return value
    }

    private fun parseSmartLog(
        identity: ControllerIdentity,
        data: ByteArray,
        node: String?
    ): SmartHealthInfo? {
        if (data.size != NVME_SMART_LOG_SIZE || data.all { it == 0.toByte() } ||
            data.all { it == 0xff.toByte() }) return null
        val criticalWarning = data[0].toInt() and 0xff
        val kelvin = readLe16(data, 1)
        val availableSpare = data[3].toInt() and 0xff
        val spareThreshold = data[4].toInt() and 0xff
        val percentageUsed = data[5].toInt() and 0xff
        // Keep the same page validation as the native root reader. A zero
        // temperature means unavailable; Percent Used may exceed 100.
        if ((criticalWarning and 0xc0) != 0 || (kelvin != 0 && kelvin !in 200..500) ||
            availableSpare !in 0..100 || spareThreshold !in 0..100) return null

        val status = when {
            criticalWarning != 0 -> SmartOverallStatus.CRITICAL
            availableSpare <= spareThreshold || percentageUsed >= 100 -> SmartOverallStatus.WARNING
            else -> SmartOverallStatus.HEALTHY
        }
        return SmartHealthInfo(
            isSupported = true,
            status = status,
            diskType = "NVMe SSD",
            model = identity.model,
            serial = identity.serial,
            firmware = identity.firmware,
            vendor = "NVMe VID 0x%04X".format(identity.vendorId),
            product = "Realtek RTL9210",
            temperatureCelsius = if (kelvin == 0) null else kelvin - 273,
            healthPercentage = (100 - percentageUsed).coerceIn(0, 100),
            availableSpare = availableSpare,
            spareThreshold = spareThreshold,
            criticalWarning = criticalWarning,
            totalBytesRead = nvmeCounterBytes(data, 32),
            totalBytesWritten = nvmeCounterBytes(data, 48),
            hostReadCommands = nvmeCounter(data, 64),
            hostWriteCommands = nvmeCounter(data, 80),
            controllerBusyMinutes = nvmeCounter(data, 96),
            powerCycles = nvmeCounter(data, 112),
            powerHours = nvmeCounter(data, 128),
            unsafeShutdowns = nvmeCounter(data, 144),
            mediaErrors = nvmeCounter(data, 160),
            errorLogEntries = nvmeCounter(data, 176),
            deviceNode = node,
            rawPageHex = data.joinToString("") { "%02X".format(it.toInt() and 0xff) }
        )
    }

    private fun nvmeCounter(data: ByteArray, offset: Int): Long =
        BigInteger(1, data.copyOfRange(offset, offset + 16).reversedArray()).let {
            if (it > LONG_MAX) Long.MAX_VALUE else it.toLong()
        }

    private fun nvmeCounterBytes(data: ByteArray, offset: Int): Long =
        BigInteger(1, data.copyOfRange(offset, offset + 16).reversedArray())
            .multiply(BigInteger.valueOf(512_000L))
            .let { if (it > LONG_MAX) Long.MAX_VALUE else it.toLong() }

    private fun readLe16(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xff) or ((data[offset + 1].toInt() and 0xff) shl 8)

    private fun readLe32(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xff) or
            ((data[offset + 1].toInt() and 0xff) shl 8) or
            ((data[offset + 2].toInt() and 0xff) shl 16) or
            (data[offset + 3].toInt() shl 24)

    private fun putLe16(data: ByteArray, offset: Int, value: Int) {
        data[offset] = value.toByte()
        data[offset + 1] = (value ushr 8).toByte()
    }

    internal interface BotTransport {
        fun bulkOutTransfer(src: ByteBuffer): Int
        fun bulkInTransfer(dest: ByteBuffer): Int
        fun resetBotAndClearHalts()
    }

    private class CommunicationBotTransport(private val comm: UsbCommunication) : BotTransport {
        override fun bulkOutTransfer(src: ByteBuffer): Int = comm.bulkOutTransfer(src)
        override fun bulkInTransfer(dest: ByteBuffer): Int = comm.bulkInTransfer(dest)

        override fun resetBotAndClearHalts() {
            val result = comm.controlTransfer(
                0x21, // Host-to-device, class, interface
                0xff, // Bulk-Only Mass Storage Reset
                0,
                comm.usbInterface.id,
                ByteArray(0),
                0
            )
            if (result != 0) throw IOException("BOT class reset failed (result=$result)")
            clearEndpointHalt(comm, comm.inEndpoint.address)
            clearEndpointHalt(comm, comm.outEndpoint.address)
        }
    }

    private fun clearEndpointHalt(comm: UsbCommunication, endpointAddress: Int) {
        val result = comm.controlTransfer(
            0x02, // Host-to-device, standard, endpoint
            0x01, // CLEAR_FEATURE
            0, // ENDPOINT_HALT
            endpointAddress,
            ByteArray(0),
            0
        )
        if (result != 0) throw IOException("CLEAR_FEATURE(ENDPOINT_HALT) failed for 0x%02x (result=$result)".format(endpointAddress))
    }

    private val LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE)
    private const val REALTEK_NVME_CDB = 0xe4
}
