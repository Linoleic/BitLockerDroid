package com.bitlockerdroid.service

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class VolumeOperationTrackerTest {
    @Test fun failureRetainsItsActualStageEvenAfterLateCallbacks() {
        val tracker = VolumeOperationTracker()
        val run = tracker.begin("usb://2/1", "usb://2", VolumeOperationKind.EJECT)
        run.stage(VolumeOperationStage.SYNCING_DEVICE)
        run.finish(VolumeOperationOutcome.FAILED, "cache command rejected")
        run.stage(VolumeOperationStage.STOPPING_DEVICE)
        run.finish(VolumeOperationOutcome.DISK_STOPPED)
        val failed = tracker.states.value.getValue("usb://2/1")
        assertEquals(VolumeOperationStage.SYNCING_DEVICE, failed.stage)
        assertEquals(VolumeOperationOutcome.FAILED, failed.outcome)
        assertEquals("cache command rejected", failed.error)
    }

    @Test fun retryCannotBeOverwrittenOrDismissedByAnOlderRun() {
        val tracker = VolumeOperationTracker()
        val old = tracker.begin("/dev/block/sda5", "/dev/block/sda", VolumeOperationKind.EJECT)
        old.finish(VolumeOperationOutcome.FAILED, "busy")
        val id = tracker.states.value.values.single().id
        val current = tracker.begin("/dev/block/sda5", "/dev/block/sda", VolumeOperationKind.CLOSE)
        old.stage(VolumeOperationStage.STOPPING_DEVICE)
        tracker.dismiss("/dev/block/sda5", id)
        val live = tracker.states.value.values.single()
        tracker.dismiss(live.devicePath, live.id)
        assertEquals(VolumeOperationOutcome.RUNNING, tracker.states.value.values.single().outcome)
        current.finish(VolumeOperationOutcome.VOLUME_CLOSED)
        tracker.dismiss(live.devicePath, live.id)
        assertTrue(tracker.states.value.isEmpty())
    }

    @Test fun resultsAreBoundedWithoutDiscardingActiveWork() {
        val tracker = VolumeOperationTracker(retainedResults = 1)
        tracker.begin("active", "disk1", VolumeOperationKind.CLOSE)
        tracker.begin("older", "disk2", VolumeOperationKind.CLOSE).finish(VolumeOperationOutcome.FAILED, "busy")
        tracker.begin("newer", "disk3", VolumeOperationKind.CLOSE).finish(VolumeOperationOutcome.VOLUME_CLOSED)
        assertEquals(setOf("active", "newer"), tracker.states.value.keys)
    }

    @Test fun dismissingABatchResultDoesNotRevealOlderPartitionWarnings() {
        val tracker = VolumeOperationTracker()
        tracker.begin("usb://2/1", "usb://2", VolumeOperationKind.EJECT).finish(VolumeOperationOutcome.DISK_BUSY)
        tracker.begin("usb://2/2", "usb://2", VolumeOperationKind.EJECT).finish(VolumeOperationOutcome.DISK_STOPPED)
        tracker.begin("usb://3/1", "usb://3", VolumeOperationKind.CLOSE)
        val latest = tracker.states.value.getValue("usb://2/2")
        tracker.dismiss(latest.devicePath, latest.id)
        assertEquals(setOf("usb://3/1"), tracker.states.value.keys)
    }

    @Test fun simultaneousDisksDoNotLoseEachOthersUpdates() {
        val tracker = VolumeOperationTracker()
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val jobs = (1..2).map { disk -> executor.submit {
                start.await()
                val run = tracker.begin("usb://$disk/1", "usb://$disk", VolumeOperationKind.EJECT)
                run.stage(VolumeOperationStage.WAITING_FOR_WRITES)
                run.finish(VolumeOperationOutcome.DISK_BUSY)
            } }
            start.countDown()
            jobs.forEach { it.get(2, TimeUnit.SECONDS) }
            assertEquals(setOf("usb://1/1", "usb://2/1"), tracker.states.value.keys)
            assertTrue(tracker.states.value.values.all { it.outcome == VolumeOperationOutcome.DISK_BUSY })
        } finally { executor.shutdownNow() }
    }
}
