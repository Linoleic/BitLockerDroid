package com.bitlockerdroid.usb

import org.junit.Assert.assertEquals
import org.junit.Test

class UsbPartitionPathSupportTest {
    @Test
    fun parsesUsbPartitionNumberWithoutInventingItsStartSector() {
        data class Case(
            val path: String,
            val expected: UsbPartitionPathInfo?,
        )

        val cases = listOf(
            Case("usb://2003/p5", UsbPartitionPathInfo("2003", 5, 0L)),
            Case("usb://2003/p10", UsbPartitionPathInfo("2003", 10, 0L)),
            Case("usb://0bda:9210/1/5", UsbPartitionPathInfo("0bda:9210", 5, 0L)),
            Case("usb://2003/p0", null),
            Case("usb://2003/not-a-partition", null),
            Case("/dev/block/sda5", null),
        )

        for ((path, expected) in cases) {
            assertEquals("path=$path", expected, UsbPartitionPathSupport.parse(path))
        }
    }

    @Test
    fun usesDiscoveredLbaAndFormatsCanonicalPartitionPaths() {
        assertEquals(
            UsbPartitionPathInfo("2003", 5, 8192L),
            UsbPartitionPathSupport.parse("usb://2003/p5", discoveredStartLba = 8192L),
        )
        assertEquals("usb://2003/p5", UsbPartitionPathSupport.format(2003, 5))
    }

    @Test
    fun onlyACompletedZeroPartitionScanConfirmsAnUnencryptedDevice() {
        data class Case(
            val completed: Boolean,
            val partitionCount: Int,
            val expectedEmpty: Boolean,
        )

        val cases = listOf(
            Case(completed = true, partitionCount = 0, expectedEmpty = true),
            Case(completed = false, partitionCount = 0, expectedEmpty = false),
            Case(completed = true, partitionCount = 1, expectedEmpty = false),
            Case(completed = false, partitionCount = 2, expectedEmpty = false),
        )

        for ((completed, partitionCount, expectedEmpty) in cases) {
            assertEquals(
                "completed=$completed, partitionCount=$partitionCount",
                expectedEmpty,
                UsbPartitionScanPolicy.isConfirmedEmpty(completed, partitionCount),
            )
        }
    }
}
