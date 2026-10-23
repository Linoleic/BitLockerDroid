package com.bitlockerdroid.service

import org.junit.Assert.assertEquals
import org.junit.Test

class RootBlockNodesTest {
    @Test fun lateVoldAliasDoesNotChangeUsbPartitionPath() {
        val usb = listOf("/dev/block/sdg5" to "8:101", "/dev/block/sdg6" to "8:102")
        val before = RootBlockNodes.merge(emptyList(), usb)
        val after = RootBlockNodes.merge(listOf("/dev/block/vold/public:8,101", "/dev/block/vold/public:8,102"), usb)
        assertEquals(before, after)
    }

    @Test fun keepsOtherDisksAndPartitionsWithoutInventingAliases() {
        assertEquals(listOf("/dev/block/vold/public:179,1", "/dev/block/vold/public:8,102", "/dev/block/sdg5", "/dev/block/sdh1"),
            RootBlockNodes.merge(listOf("/dev/block/vold/public:179,1", "/dev/block/vold/public:8,101", "/dev/block/vold/public:8,102"),
                listOf("/dev/block/sdg5" to "8:101", "/dev/block/sdh1" to null)))
    }
}
