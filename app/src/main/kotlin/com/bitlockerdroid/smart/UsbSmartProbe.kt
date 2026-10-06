package com.bitlockerdroid.smart

import android.util.Log
import me.jahnen.libaums.core.usb.UsbCommunication
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Non-Root USB BOT SCSI SAT & NVMe SMART Probe.
 * Sends raw SCSI CDBs encapsulated in 31-byte BOT Command Block Wrappers (CBW).
 */
object UsbSmartProbe {

    private const val TAG = "UsbSmartProbe"
    private const val CBW_SIGNATURE = 0x43425355 // 'USBC'
    private const val CSW_SIGNATURE = 0x53425355 // 'USBS'
    private var tagCounter = 1000

    @Synchronized
    private fun getNextTag(): Int = tagCounter++

    private fun buildCbw(tag: Int, transferLength: Int, cdb: ByteArray): ByteBuffer {
        val buf = ByteBuffer.allocate(31).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(CBW_SIGNATURE)
        buf.putInt(tag)
        buf.putInt(transferLength)
        buf.put(0x80.toByte()) // Direction: IN (Device to Host)
        buf.put(0.toByte())    // LUN: 0
        buf.put(cdb.size.toByte())
        buf.put(cdb)
        // Pad rest of the 16-byte CDB area to 0
        val remainingCdb = 16 - cdb.size
        for (i in 0 until remainingCdb) {
            buf.put(0.toByte())
        }
        buf.flip()
        return buf
    }

    private fun executeScsiIn(
        comm: UsbCommunication,
        cdb: ByteArray,
        expectedLength: Int
    ): Pair<Boolean, ByteArray?> {
        val tag = getNextTag()
        val cbw = buildCbw(tag, expectedLength, cdb)

        try {
            comm.bulkOutTransfer(cbw)

            val dataBuf = ByteBuffer.allocate(expectedLength)
            comm.bulkInTransfer(dataBuf)
            val dataBytes = dataBuf.array()

            val cswBuf = ByteBuffer.allocate(13).order(ByteOrder.LITTLE_ENDIAN)
            comm.bulkInTransfer(cswBuf)
            cswBuf.flip()

            if (cswBuf.remaining() >= 13) {
                val sig = cswBuf.getInt()
                val retTag = cswBuf.getInt()
                val residue = cswBuf.getInt()
                val status = cswBuf.get().toInt() and 0xFF

                if (sig == CSW_SIGNATURE && retTag == tag && status == 0) {
                    return Pair(true, dataBytes)
                }
            }
            return Pair(false, dataBytes)
        } catch (e: Exception) {
            Log.d(TAG, "SCSI transfer exception: ${e.message}")
            return Pair(false, null)
        }
    }

    fun probeSmart(comm: UsbCommunication, deviceNode: String? = null): SmartHealthInfo {
        try {
            // 1. SCSI INQUIRY (36 bytes)
            var vendor = ""
            var product = ""
            var rev = ""
            val inqCdb = byteArrayOf(0x12, 0, 0, 0, 36, 0)
            val (inqOk, inqData) = executeScsiIn(comm, inqCdb, 36)
            if (inqOk && inqData != null && inqData.size >= 36) {
                vendor = String(inqData, 8, 8, Charsets.US_ASCII).trim()
                product = String(inqData, 16, 16, Charsets.US_ASCII).trim()
                rev = String(inqData, 32, 4, Charsets.US_ASCII).trim()
            }

            // 2. SAT ATA IDENTIFY (512 bytes)
            var model = ""
            var serial = ""
            var firmware = ""
            var diskType = "Unknown"
            val ataIdCdb = byteArrayOf(
                0x85.toByte(), 0x08, 0x0E, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0xEC.toByte(), 0
            )
            val (ataIdOk, ataIdData) = executeScsiIn(comm, ataIdCdb, 512)
            if (ataIdOk && ataIdData != null && ataIdData.size >= 512) {
                val modelChars = CharArray(40)
                for (i in 0 until 20) {
                    modelChars[i * 2] = ataIdData[54 + i * 2 + 1].toInt().toChar()
                    modelChars[i * 2 + 1] = ataIdData[54 + i * 2].toInt().toChar()
                }
                model = String(modelChars).trim()

                val serialChars = CharArray(20)
                for (i in 0 until 10) {
                    serialChars[i * 2] = ataIdData[20 + i * 2 + 1].toInt().toChar()
                    serialChars[i * 2 + 1] = ataIdData[20 + i * 2].toInt().toChar()
                }
                serial = String(serialChars).trim()

                val fwChars = CharArray(8)
                for (i in 0 until 4) {
                    fwChars[i * 2] = ataIdData[46 + i * 2 + 1].toInt().toChar()
                    fwChars[i * 2 + 1] = ataIdData[46 + i * 2].toInt().toChar()
                }
                firmware = String(fwChars).trim()

                val rotRate = (ataIdData[434].toInt() and 0xFF) or ((ataIdData[435].toInt() and 0xFF) shl 8)
                diskType = if (rotRate == 1) "SSD" else if (rotRate > 1) "HDD" else "Unknown"
            }

            // 3. Try Realtek RTL9210 NVMe Tunnel (0xE4, 512 bytes)
            val rtkCdb = byteArrayOf(
                0xE4.toByte(), 0x00, 0x02, 0x02, 0x02, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0
            )
            var (nvmeOk, nvmeData) = executeScsiIn(comm, rtkCdb, 512)

            // 4. Try ASMedia ASM2362 NVMe Tunnel (0xE6) if RTL failed
            if (!nvmeOk || nvmeData == null) {
                val asmCdb = byteArrayOf(
                    0xE6.toByte(), 0x02, 0, 0x02, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0
                )
                val asmRes = executeScsiIn(comm, asmCdb, 512)
                if (asmRes.first && asmRes.second != null) {
                    nvmeOk = true
                    nvmeData = asmRes.second
                }
            }

            if (nvmeOk && nvmeData != null && nvmeData.size >= 512) {
                val critWarn = nvmeData[0].toInt() and 0xFF
                val kelvin = (nvmeData[1].toInt() and 0xFF) or ((nvmeData[2].toInt() and 0xFF) shl 8)
                val tempC = if (kelvin > 273) kelvin - 273 else 0
                val availSpare = nvmeData[3].toInt() and 0xFF
                val spareThresh = nvmeData[4].toInt() and 0xFF
                val percentUsed = nvmeData[5].toInt() and 0xFF
                val healthPct = if (percentUsed <= 100) 100 - percentUsed else 0

                val buf = ByteBuffer.wrap(nvmeData).order(ByteOrder.LITTLE_ENDIAN)
                val unitsRead = buf.getLong(32)
                val unitsWritten = buf.getLong(48)
                val powerCycles = buf.getLong(112)
                val powerHours = buf.getLong(128)
                val unsafeShutdowns = buf.getLong(144)

                val status = if (critWarn == 0 && healthPct >= 10) SmartOverallStatus.HEALTHY
                             else if (healthPct < 10) SmartOverallStatus.CRITICAL
                             else SmartOverallStatus.WARNING

                return SmartHealthInfo(
                    isSupported = true,
                    status = status,
                    diskType = "NVMe SSD",
                    model = model.ifBlank { product },
                    serial = serial.ifBlank { null },
                    firmware = firmware.ifBlank { rev },
                    vendor = vendor.ifBlank { null },
                    product = product.ifBlank { null },
                    temperatureCelsius = tempC,
                    healthPercentage = healthPct,
                    availableSpare = availSpare,
                    spareThreshold = spareThresh,
                    criticalWarning = critWarn,
                    powerCycles = powerCycles,
                    powerHours = powerHours,
                    unsafeShutdowns = unsafeShutdowns,
                    totalBytesRead = unitsRead * 512000L,
                    totalBytesWritten = unitsWritten * 512000L,
                    deviceNode = deviceNode
                )
            }

            // 5. Try SATA ATA SMART READ DATA (0xB0, 0xD0)
            val smartCdb = byteArrayOf(
                0x85.toByte(), 0x08, 0x0E, 0, 0xD0.toByte(), 0, 1, 0, 0, 0, 0x4F, 0, 0xC2.toByte(), 0, 0xB0.toByte(), 0
            )
            val (sataOk, sataData) = executeScsiIn(comm, smartCdb, 512)
            if (sataOk && sataData != null && sataData.size >= 512) {
                var tempC: Int? = null
                var healthPct: Int? = 100
                var powerHours: Long? = null
                var powerCycles: Long? = null
                var reallocated: Long? = null
                var pending: Long? = null
                var uncorrectable: Long? = null
                var bytesWritten: Long? = null

                for (i in 0 until 30) {
                    val off = 2 + i * 12
                    val id = sataData[off].toInt() and 0xFF
                    if (id == 0) continue
                    val current = sataData[off + 3].toInt() and 0xFF
                    val raw = (sataData[off + 5].toLong() and 0xFF) or
                              ((sataData[off + 6].toLong() and 0xFF) shl 8) or
                              ((sataData[off + 7].toLong() and 0xFF) shl 16) or
                              ((sataData[off + 8].toLong() and 0xFF) shl 24) or
                              ((sataData[off + 9].toLong() and 0xFF) shl 32) or
                              ((sataData[off + 10].toLong() and 0xFF) shl 40)

                    when (id) {
                        0x05 -> reallocated = raw
                        0x09 -> powerHours = raw
                        0x0C -> powerCycles = raw
                        0xC2, 0xBE -> tempC = (raw and 0xFF).toInt()
                        0xC5 -> pending = raw
                        0xC6 -> uncorrectable = raw
                        0xE7, 0xA9, 0xB1, 0xE8 -> {
                            if (current in 1..100) healthPct = current
                            else if (raw in 1..100) healthPct = raw.toInt()
                        }
                        0xF1 -> bytesWritten = raw * 512L
                    }
                }

                val status = if ((reallocated ?: 0L) == 0L && (pending ?: 0L) == 0L && (uncorrectable ?: 0L) == 0L) {
                    SmartOverallStatus.HEALTHY
                } else if ((uncorrectable ?: 0L) > 0L) {
                    SmartOverallStatus.CRITICAL
                } else {
                    SmartOverallStatus.WARNING
                }

                return SmartHealthInfo(
                    isSupported = true,
                    status = status,
                    diskType = if (diskType != "Unknown") diskType else "SATA SSD",
                    model = model.ifBlank { product },
                    serial = serial.ifBlank { null },
                    firmware = firmware.ifBlank { rev },
                    vendor = vendor.ifBlank { null },
                    product = product.ifBlank { null },
                    temperatureCelsius = tempC,
                    healthPercentage = healthPct,
                    reallocatedSectors = reallocated,
                    pendingSectors = pending,
                    uncorrectableSectors = uncorrectable,
                    powerCycles = powerCycles,
                    powerHours = powerHours,
                    totalBytesWritten = bytesWritten,
                    deviceNode = deviceNode
                )
            }

            // 6. Controller lacks ATA/NVMe S.M.A.R.T.
            return SmartHealthInfo.unsupported(
                vendor = vendor.ifBlank { null },
                product = product.ifBlank { null },
                node = deviceNode,
                reason = "Storage controller lacks standard ATA/NVMe S.M.A.R.T. telemetry."
            )
        } catch (e: Exception) {
            Log.w(TAG, "probeSmart failed", e)
            return SmartHealthInfo.unsupported(node = deviceNode, reason = e.message)
        }
    }
}
