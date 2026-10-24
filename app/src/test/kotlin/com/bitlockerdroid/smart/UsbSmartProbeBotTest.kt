package com.bitlockerdroid.smart

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

class UsbSmartProbeBotTest {

    @Test
    fun onlyExactRealtek9210IdentityHasAProfile() {
        assertNotNull(UsbSmartProfile.profileFor(0x0bda, 0x9210))
        assertNull(UsbSmartProfile.profileFor(0x0bda, 0x9211))
        assertNull(UsbSmartProfile.profileFor(0x0bdb, 0x9210))
        assertNull(UsbSmartProfile.profileFor(0xffff, 0xffff))
    }

    @Test
    fun knownRealtekProbeUsesOnlyIdentifyThenSmartLogAndHandlesShortTransfers() {
        val identify = validIdentifyController()
        val smartLog = validSmartLog()
        val transport = FakeBotTransport(
            outChunkLimit = 31,
            inChunkLimit = 173
        ) { command ->
            when (command.cdb[3].toInt() and 0xff) {
                0x06 -> successfulResponse(command, identify)
                0x02 -> successfulResponse(command, smartLog)
                else -> throw AssertionError("Unexpected CDB opcode ${command.cdb[3]}")
            }
        }

        val result = UsbSmartProbe.probeSmartWithTransport(
            vendorId = 0x0bda,
            productId = 0x9210,
            deviceNode = "usb://2004/p6",
            transport = transport
        )

        assertTrue(result.isSupported)
        assertEquals("P202 NVMe Controller", result.model)
        assertEquals("NVME123456789", result.serial)
        assertEquals("1.02", result.firmware)
        assertEquals("NVMe SSD", result.diskType)
        assertEquals(23, result.temperatureCelsius)
        assertEquals(95, result.healthPercentage)
        assertEquals("usb://2004/p6", result.deviceNode)
        assertEquals(2, transport.commands.size)
        assertEquals(0, transport.recoveryCount)
        assertEquals(SmartDataIntegrity.NVME_VALIDATED, result.dataIntegrity)
        assertNull(result.failureStage)

        val identifyCommand = transport.commands[0]
        assertEquals(4096, identifyCommand.transferLength)
        assertEquals(0x80, identifyCommand.flags)
        assertEquals(16, identifyCommand.cdbLength)
        assertArrayEquals(
            byteArrayOf(0xe4.toByte(), 0x00, 0x10, 0x06, 0x01) + ByteArray(11),
            identifyCommand.cdb
        )

        val logCommand = transport.commands[1]
        assertEquals(512, logCommand.transferLength)
        assertArrayEquals(
            byteArrayOf(0xe4.toByte(), 0x00, 0x02, 0x02, 0x02) + ByteArray(11),
            logCommand.cdb
        )
    }

    @Test
    fun commandFailedEarlyCswReturnsUnsupportedWithoutTryingAnotherProtocol() {
        val transport = FakeBotTransport { command ->
            failedResponse(command)
        }

        val result = UsbSmartProbe.probeSmartWithTransport(
            vendorId = 0x0bda,
            productId = 0x9210,
            transport = transport
        )

        assertFalse(result.isSupported)
        assertEquals(SmartFailureStage.READ, result.failureStage)
        assertEquals(SmartDataIntegrity.NOT_CHECKED, result.dataIntegrity)
        assertTrue(result.reason.orEmpty().contains("SATA/SAT fallback is not enabled"))
        assertEquals(1, transport.commands.size)
        assertEquals(0x06, transport.commands.single().cdb[3].toInt() and 0xff)
        assertEquals(0, transport.recoveryCount)
    }

    @Test
    fun shortCbwIsNotRetriedAsARemainderAndRecoversOnce() {
        val transport = FakeBotTransport(outChunkLimit = 7) {
            throw AssertionError("A short CBW must not start a command")
        }

        val error = assertThrows(RecoveredProbeException::class.java) {
            UsbSmartProbe.probeSmartWithTransport(0x0bda, 0x9210, transport = transport)
        }

        assertTrue(error.message.orEmpty().contains("CBW must be sent in one complete"))
        assertEquals(1, transport.bulkOutCalls)
        assertEquals(0, transport.commands.size)
        assertEquals(1, transport.recoveryCount)
    }

    @Test
    fun zeroOrInvalidIdentifyFieldsDoNotTriggerSmartOrFallbackCommands() {
        val invalidPages = listOf(
            ByteArray(4096),
            validIdentifyController().also { it[0] = 0; it[1] = 0 },
            validIdentifyController().also { it.fill(0, 4, 24) },
            validIdentifyController().also { it.fill(0, 24, 64) },
            validIdentifyController().also { it.fill(0, 64, 72) }
        )

        invalidPages.forEach { invalidPage ->
            val transport = FakeBotTransport { command ->
                successfulResponse(command, invalidPage)
            }

            val result = UsbSmartProbe.probeSmartWithTransport(
                vendorId = 0x0bda,
                productId = 0x9210,
                transport = transport
            )

            assertFalse(result.isSupported)
            assertEquals(1, transport.commands.size)
            assertEquals(0x06, transport.commands.single().cdb[3].toInt() and 0xff)
        }
    }

    @Test
    fun malformedCswIsRejectedAndRunsOneBotRecovery() {
        val transport = FakeBotTransport { command ->
            successfulResponse(command, validIdentifyController()).also { response ->
                response[response.lastIndex - 12] = 0 // corrupt CSW signature
            }
        }

        val error = assertThrows(RecoveredProbeException::class.java) {
            UsbSmartProbe.probeSmartWithTransport(
                vendorId = 0x0bda,
                productId = 0x9210,
                transport = transport
            )
        }

        assertTrue(error.message.orEmpty().contains("CSW signature"))
        assertEquals(1, transport.recoveryCount)
        assertEquals(1, transport.commands.size)
    }

    @Test
    fun mismatchedCswTagIsRejectedAndRunsOneBotRecovery() {
        val transport = FakeBotTransport { command ->
            successfulResponse(command, validIdentifyController()).also { response ->
                val cswOffset = response.size - 13
                val wrongTag = command.tag + 1
                ByteBuffer.wrap(response, cswOffset + 4, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(wrongTag)
            }
        }

        val error = assertThrows(RecoveredProbeException::class.java) {
            UsbSmartProbe.probeSmartWithTransport(
                vendorId = 0x0bda,
                productId = 0x9210,
                transport = transport
            )
        }

        assertTrue(error.message.orEmpty().contains("tag mismatch"))
        assertEquals(1, transport.recoveryCount)
    }

    @Test
    fun invalidCswResidueOrPhaseStatusIsRejectedAndRecovered() {
        listOf("residue", "phase status").forEach { fault ->
            val transport = FakeBotTransport { command ->
                successfulResponse(command, validIdentifyController()).also { response ->
                    val cswOffset = response.size - 13
                    when (fault) {
                        "residue" -> ByteBuffer.wrap(response, cswOffset + 8, 4)
                            .order(ByteOrder.LITTLE_ENDIAN).putInt(1)
                        else -> response[cswOffset + 12] = 2
                    }
                }
            }

            assertThrows(RecoveredProbeException::class.java) {
                UsbSmartProbe.probeSmartWithTransport(
                    vendorId = 0x0bda,
                    productId = 0x9210,
                    transport = transport
                )
            }
            assertEquals(1, transport.recoveryCount)
        }
    }

    @Test
    fun invalidSmartPagesRemainUnconfirmedAfterCompleteBotResponses() {
        val invalidPages = listOf(
            ByteArray(512),
            ByteArray(512) { 0xff.toByte() },
            validSmartLog().also { it[0] = 0x40 },
            validSmartLog().also { it[3] = 101 },
            validSmartLog().also { putLe16(it, 1, 501) }
        )
        invalidPages.forEach { page ->
            val transport = FakeBotTransport { command ->
                successfulResponse(command, if (command.transferLength == 4096) validIdentifyController() else page)
            }
            val result = UsbSmartProbe.probeSmartWithTransport(0x0bda, 0x9210, transport = transport)
            assertFalse(result.isSupported)
            assertEquals(SmartOverallStatus.UNCONFIRMED, result.status)
            assertEquals(2, transport.commands.size)
            assertEquals(0, transport.recoveryCount)
        }
    }

    @Test
    fun validLogMayHaveUnknownTemperatureAndLifetimeAboveOneHundred() {
        val page = validSmartLog().also {
            putLe16(it, 1, 0)
            it[0] = 0x20 // persistent memory region warning is a valid NVMe bit
            it[5] = 150.toByte()
        }
        val transport = FakeBotTransport { command ->
            successfulResponse(command, if (command.transferLength == 4096) validIdentifyController() else page)
        }
        val result = UsbSmartProbe.probeSmartWithTransport(0x0bda, 0x9210, transport = transport)
        assertTrue(result.isSupported)
        assertNull(result.temperatureCelsius)
        assertEquals(0, result.healthPercentage)
        assertEquals(0x20, result.criticalWarning)
    }

    @Test
    fun recoveryFailurePropagatesSoTheOwnerCanMarkTheTransportUnhealthy() {
        val transport = FakeBotTransport(
            responseFactory = { command ->
                successfulResponse(command, validIdentifyController()).also { it[it.size - 13] = 0 }
            },
            recoveryFailure = IOException("class reset failed")
        )

        val error = assertThrows(IOException::class.java) {
            UsbSmartProbe.probeSmartWithTransport(0x0bda, 0x9210, transport = transport)
        }

        assertTrue(error.message.orEmpty().contains("recovery failed"))
        assertEquals(1, transport.recoveryCount)
    }

    @Test
    fun wideCountersReachTheResultWithoutSaturationAndMatchRootDecimalStrings() {
        val page = validSmartLog()
        val units = java.math.BigInteger.ONE.shiftLeft(127).add(java.math.BigInteger.ONE)
        for (counter in SmartCounter.entries) {
            page.fill(0, counter.offset, counter.offset + 16)
            page[counter.offset] = 1
            page[counter.offset + 15] = 0x80.toByte()
        }
        val transport = FakeBotTransport { command ->
            successfulResponse(command, if (command.transferLength == 4096) validIdentifyController() else page)
        }
        val usb = UsbSmartProbe.probeSmartWithTransport(0x0bda, 0x9210, transport = transport)
        val rootJson = org.json.JSONObject().put("supported", true).put("status", "HEALTHY").put("disk_type", "NVMe SSD")
        for (counter in SmartCounter.entries) {
            val expected = units.multiply(java.math.BigInteger.valueOf(counter.scale))
            rootJson.put(counter.jsonKey, expected.toString())
            assertEquals(expected, usb.counterValue(counter))
        }
        val root = SmartDataParser.parseJson(rootJson.toString())
        for (counter in SmartCounter.entries) assertEquals(root.counterValue(counter), usb.counterValue(counter))
        assertNull(usb.powerCycles)
        assertNull(usb.totalBytesRead)
        assertEquals(2, transport.commands.size)
        assertEquals(0, transport.recoveryCount)
    }

    @Test
    fun usbProbeUsesUnifiedNvmeSeverityAtThresholds() {
        val cases = listOf(
            listOf(0, 10, 10, 99, SmartOverallStatus.HEALTHY),
            listOf(0, 9, 10, 99, SmartOverallStatus.WARNING),
            listOf(0, 100, 10, 100, SmartOverallStatus.WARNING),
            listOf(0x20, 100, 10, 0, SmartOverallStatus.CRITICAL)
        )
        for (case in cases) {
            val page = validSmartLog()
            page[0] = (case[0] as Int).toByte()
            page[3] = (case[1] as Int).toByte()
            page[4] = (case[2] as Int).toByte()
            page[5] = (case[3] as Int).toByte()
            val transport = FakeBotTransport { command ->
                successfulResponse(command, if (command.transferLength == 4096) validIdentifyController() else page)
            }
            val result = UsbSmartProbe.probeSmartWithTransport(0x0bda, 0x9210, transport = transport)
            assertEquals(case[4], result.status)
        }
    }

    private data class Command(
        val tag: Int,
        val transferLength: Int,
        val flags: Int,
        val cdbLength: Int,
        val cdb: ByteArray
    )

    private class FakeBotTransport(
        private val outChunkLimit: Int = Int.MAX_VALUE,
        private val inChunkLimit: Int = Int.MAX_VALUE,
        private val recoveryFailure: IOException? = null,
        private val responseFactory: (Command) -> ByteArray
    ) : UsbSmartProbe.BotTransport {
        val commands = mutableListOf<Command>()
        var recoveryCount = 0
            private set
        var bulkOutCalls = 0
            private set

        private val currentCbw = ByteArrayOutputStream()
        private var currentResponse = ByteArray(0)
        private var responseOffset = 0

        override fun bulkOutTransfer(src: ByteBuffer): Int {
            bulkOutCalls++
            val count = minOf(src.remaining(), outChunkLimit)
            val bytes = ByteArray(count)
            src.get(bytes)
            currentCbw.write(bytes)
            if (currentCbw.size() == 31) {
                val cbw = currentCbw.toByteArray()
                currentCbw.reset()
                val buffer = ByteBuffer.wrap(cbw).order(ByteOrder.LITTLE_ENDIAN)
                val signature = buffer.int
                if (signature != 0x43425355) throw IOException("bad fake CBW signature")
                val tag = buffer.int
                val transferLength = buffer.int
                val flags = buffer.get().toInt() and 0xff
                buffer.get() // LUN
                val cdbLength = buffer.get().toInt() and 0xff
                val cdb = ByteArray(16)
                buffer.get(cdb)
                val command = Command(tag, transferLength, flags, cdbLength, cdb)
                commands.add(command)
                currentResponse = responseFactory(command)
                responseOffset = 0
            }
            return count
        }

        override fun bulkInTransfer(dest: ByteBuffer): Int {
            if (responseOffset >= currentResponse.size) throw IOException("fake BOT response exhausted")
            val count = minOf(dest.remaining(), inChunkLimit, currentResponse.size - responseOffset)
            dest.put(currentResponse, responseOffset, count)
            responseOffset += count
            return count
        }

        override fun resetBotAndClearHalts() {
            recoveryCount++
            recoveryFailure?.let { throw it }
            currentCbw.reset()
            currentResponse = ByteArray(0)
            responseOffset = 0
        }
    }

    companion object {
        private fun validIdentifyController(): ByteArray = ByteArray(4096).apply {
            putLe16(this, 0, 0x1234) // NVMe controller VID, distinct from the USB bridge VID
            putAscii(this, 4, 20, "NVME123456789")
            putAscii(this, 24, 40, "P202 NVMe Controller")
            putAscii(this, 64, 8, "1.02")
        }

        private fun validSmartLog(): ByteArray = ByteArray(512).apply {
            this[1] = 0x28 // 296 Kelvin
            this[2] = 0x01
            this[3] = 100.toByte()
            this[4] = 10
            this[5] = 5
        }

        private fun successfulResponse(command: Command, data: ByteArray): ByteArray {
            require(data.size == command.transferLength)
            return data + csw(command, residue = 0, status = 0)
        }

        private fun failedResponse(command: Command): ByteArray =
            csw(command, residue = command.transferLength, status = 1)

        private fun csw(command: Command, residue: Int, status: Int): ByteArray =
            ByteBuffer.allocate(13).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(0x53425355)
                .putInt(command.tag)
                .putInt(residue)
                .put(status.toByte())
                .array()

        private fun putLe16(data: ByteArray, offset: Int, value: Int) {
            data[offset] = value.toByte()
            data[offset + 1] = (value ushr 8).toByte()
        }

        private fun putAscii(data: ByteArray, offset: Int, size: Int, value: String) {
            val bytes = value.toByteArray(Charsets.US_ASCII)
            require(bytes.size <= size)
            System.arraycopy(bytes, 0, data, offset, bytes.size)
        }
    }
}
