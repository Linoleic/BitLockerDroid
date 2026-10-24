package com.bitlockerdroid.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class DeviceVolumeClosureTest {
    @Test fun usbClosureKeepsOtherDevicesAndClosesEachSessionOnce() {
        val closed = mutableListOf<String>()
        val count = DeviceVolumeClosure.close(
            "usb://2",
            listOf("usb://2/1", "usb://20/1", "usb://2/2", "usb://2/1"),
            { "usb://" + it.removePrefix("usb://").substringBefore('/') },
            { closed.add(it) }
        )
        assertEquals(2, count)
        assertEquals(listOf("usb://2/1", "usb://2/2"), closed)
    }

    @Test fun rootPartitionAndPhysicalDiskTargetsSelectTheSameDevice() {
        val parents = mapOf(
            "/dev/block/sda" to "/dev/block/sda",
            "/dev/block/sda5" to "/dev/block/sda",
            "/dev/block/vold/public:8,6" to "/dev/block/sda",
            "/dev/block/sdb1" to "/dev/block/sdb"
        )
        val paths = listOf("/dev/block/sda5", "/dev/block/sdb1", "/dev/block/vold/public:8,6")
        for (target in listOf("/dev/block/sda", "/dev/block/sda5")) {
            val closed = mutableListOf<String>()
            assertEquals(2, DeviceVolumeClosure.close(target, paths, { parents.getValue(it) }, { closed.add(it) }))
            assertEquals(listOf(paths[0], paths[2]), closed)
        }
    }

    @Test fun failedFlushStopsBeforeRemainingVolumesAndPreservesTheCause() {
        val failure = IOException("flush failed")
        val attempted = mutableListOf<String>()
        try {
            DeviceVolumeClosure.close("disk", listOf("first", "failed", "last"), { "disk" }) {
                attempted.add(it)
                if (it == "failed") throw failure
            }
            fail("closure should fail")
        } catch (actual: IOException) {
            assertSame(failure, actual)
        }
        assertEquals(listOf("first", "failed"), attempted)
    }

    @Test fun alreadyClosedDeviceDoesNotTouchOtherVolumes() {
        assertEquals(0, DeviceVolumeClosure.close("a", listOf("b"), { it }) {
            fail("another device must remain open")
        })
    }

    @Test fun invalidPhysicalDeviceIsRejectedBeforeAnyClosure() {
        for (target in listOf("", "storage:mounted-volume")) {
            try {
                DeviceVolumeClosure.close(target, listOf("live"), { it }) {
                    fail("invalid selection must not close a session")
                }
                fail("invalid selection should fail")
            } catch (_: IllegalArgumentException) {
                // Expected: an unencrypted Android storage group is not a physical target.
            }
        }
    }
}
