package com.bitlockerdroid.usb

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbKernelHandoffPolicyTest {
    @Test
    fun verifiedBridgeWithUasNeedsHandoffRecovery() {
        assertTrue(UsbKernelHandoffPolicy.needsReset(0x0bda, 0x9210, true))
    }

    @Test
    fun botOnlyBridgeDoesNotReset() {
        assertFalse(UsbKernelHandoffPolicy.needsReset(0x0bda, 0x9210, false))
    }

    @Test
    fun unknownBridgeDoesNotResetEvenIfItExposesUas() {
        assertFalse(UsbKernelHandoffPolicy.needsReset(0x0bda, 0x9211, true))
        assertFalse(UsbKernelHandoffPolicy.needsReset(0x1234, 0x9210, true))
    }
}
