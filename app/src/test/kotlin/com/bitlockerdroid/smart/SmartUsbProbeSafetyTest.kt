package com.bitlockerdroid.smart

import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import kotlinx.coroutines.runBlocking
import me.jahnen.libaums.core.usb.UsbCommunication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.nio.ByteBuffer

class SmartUsbProbeSafetyTest {

    @Test
    fun automaticUsbSupportCheckDoesNotInvokeProbeQuery() = runBlocking {
        var queryCalls = 0
        var unsupportedCalls = 0

        val result = SmartHealthManager.runAutomaticSupportCheck(
            isUsbUri = true,
            query = {
                queryCalls++
                true
            },
            unsupported = {
                unsupportedCalls++
                false
            }
        )

        assertFalse(result)
        assertEquals(0, queryCalls)
        assertEquals(1, unsupportedCalls)
    }

    @Test
    fun nonUsbSupportCheckStillInvokesProvider() = runBlocking {
        var queryCalls = 0

        val result = SmartHealthManager.runAutomaticSupportCheck(
            isUsbUri = false,
            query = {
                queryCalls++
                true
            },
            unsupported = { false }
        )

        assertTrue(result)
        assertEquals(1, queryCalls)
    }

    @Test
    fun unknownUsbBridgeDoesNotIssueCommunicationCommands() {
        val communication = CountingUsbCommunication()

        val result = UsbSmartProbe.probeSmart(communication, "usb://2004/p6", 0x1234, 0x5678)

        assertFalse(result.isSupported)
        assertEquals(SmartOverallStatus.UNCONFIRMED, result.status)
        assertEquals(0, communication.commandCount)
    }

    private class CountingUsbCommunication : UsbCommunication {
        var commandCount = 0

        override val inEndpoint: UsbEndpoint
            get() = error("Probe must not inspect USB endpoints")
        override val outEndpoint: UsbEndpoint
            get() = error("Probe must not inspect USB endpoints")
        override val usbInterface: UsbInterface
            get() = error("Probe must not inspect USB interface")

        override fun bulkOutTransfer(src: ByteBuffer): Int {
            commandCount++
            throw IOException("Unexpected USB command")
        }

        override fun bulkInTransfer(dest: ByteBuffer): Int {
            commandCount++
            throw IOException("Unexpected USB command")
        }

        override fun controlTransfer(
            requestType: Int,
            request: Int,
            value: Int,
            index: Int,
            buffer: ByteArray,
            length: Int
        ): Int {
            commandCount++
            throw IOException("Unexpected USB command")
        }

        override fun resetDevice() {
            commandCount++
            throw IOException("Unexpected USB command")
        }

        override fun clearFeatureHalt(endpoint: UsbEndpoint) {
            commandCount++
            throw IOException("Unexpected USB command")
        }

        override fun close() {
            commandCount++
        }
    }
}
