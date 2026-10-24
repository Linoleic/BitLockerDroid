package com.bitlockerdroid.service

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class DeviceEjectionTest {
    private fun parent(path: String) = path.substringBefore("/p")

    @Test fun zeroSessionsStillFinishesTheKnownPhysicalDevice() {
        val events = mutableListOf<String>()
        val result = DeviceEjection.eject("usb://2", emptyList(), listOf("usb://2/p1"), ::parent,
            { events += "close:$it" }, { events += "stop:$it"; "stopped" })
        assertEquals("stopped", result)
        assertEquals(listOf("stop:usb://2"), events)
    }

    @Test fun allTargetSessionsCloseBeforeStopWithoutTouchingAnotherDisk() {
        val events = mutableListOf<String>()
        val paths = listOf("usb://2/p1", "usb://20/p1", "usb://2/p2")
        DeviceEjection.eject("usb://2", paths, paths, ::parent,
            { events += "close:$it" }, { events += "stop:$it" })
        assertEquals(listOf("close:usb://2/p1", "close:usb://2/p2", "stop:usb://2"), events)
    }

    @Test fun failedClosureNeverStopsDiskOrClosesLaterSessions() {
        val events = mutableListOf<String>()
        val paths = listOf("usb://2/p1", "usb://2/p2")
        assertThrows(IOException::class.java) {
            DeviceEjection.eject("usb://2", paths, paths, ::parent,
                { events += it; throw IOException("write still running") }, { events += "STOP" })
        }
        assertEquals(listOf("usb://2/p1"), events)
    }

    @Test fun unknownDiskUntrackedPartitionAndStorageFallbackDoNotPerformAnyIo() {
        for (path in listOf("usb://3", "usb://2/p9", "storage:internal", "")) {
            assertThrows(IllegalArgumentException::class.java) {
                DeviceEjection.eject(path, emptyList(), listOf("usb://2/p1"), ::parent,
                    { fail("Unexpected closure") }, { fail("Unexpected STOP") })
            }
        }
    }

    @Test fun rootAliasAndPhysicalDiskUseTheSameTrackedTarget() {
        val resolve: (String) -> String = { if (it == "/dev/block/vold/public:8,5") "/dev/block/sda" else it }
        val events = mutableListOf<String>()
        DeviceEjection.eject("/dev/block/sda", emptyList(), listOf("/dev/block/vold/public:8,5"), resolve,
            { fail("Already closed") }, { events += it })
        assertEquals(listOf("/dev/block/sda"), events)
    }
}
