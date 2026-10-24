package com.bitlockerdroid.service

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class VolumeWriteStateTest {
    @Test fun observableCountsIncludeSyncUntilTheActualTicketFinishes() {
        val state = VolumeWriteState()
        val first = state.begin("/first")
        val second = state.begin("/second")
        state.syncing(first)
        assertEquals(VolumeWriteState.Snapshot(2, 1), state.state.value)
        assertThrows(IOException::class.java) { state.pauseAndDrain(0) }
        assertEquals(VolumeWriteState.Snapshot(2, 1), state.state.value)
        state.finish(first, IOException("sync failed"))
        assertEquals(VolumeWriteState.Snapshot(1, 0, "sync failed"), state.state.value)
        state.finish(second)
        assertEquals(VolumeWriteState.Snapshot(0, 0, "sync failed"), state.state.value)
    }

    @Test fun staleWriteCallbacksCannotChangeNewTicketCountsOrError() {
        val state = VolumeWriteState()
        val old = state.begin("/file")
        state.finish(old, IOException("old failure"))
        val current = state.begin("/file")
        state.syncing(current)
        state.syncing(old)
        state.finish(old)
        assertEquals(VolumeWriteState.Snapshot(1, 1, "old failure"), state.state.value)
        state.finish(current)
        assertEquals(VolumeWriteState.Snapshot(), state.state.value)
    }

    @Test fun samePathOnDifferentVolumesDoesNotBlockOrDrainTheOtherVolume() {
        val first = VolumeWriteState()
        val second = VolumeWriteState()
        val a = first.begin("/same.bin")
        val b = second.begin("/same.bin")
        first.finish(a)
        first.pauseAndDrain(0)
        assertThrows(IOException::class.java) { second.pauseAndDrain(0) }
        second.finish(b)
        second.pauseAndDrain(0)
    }

    @Test fun timedOutEjectRetainsTheWriteAndBlocksNewAdmissionsUntilResumed() {
        val state = VolumeWriteState()
        val ticket = state.begin("/large.bin")
        assertThrows(IOException::class.java) { state.pauseAndDrain(0) }
        assertThrows(IOException::class.java) { state.begin("/new.bin", 0) }
        state.resume()
        assertThrows(IOException::class.java) { state.awaitPath("/large.bin", 0) }
        state.finish(ticket)
        state.pauseAndDrain(0)
    }

    @Test fun failureIsReportedBeforeEjectAndAnExplicitRetryCanClose() {
        val state = VolumeWriteState()
        val ticket = state.begin("/partial.bin")
        state.finish(ticket, IOException("disk full"))
        assertThrows(IOException::class.java) { state.pauseAndDrain(0) }
        state.resume()
        state.pauseAndDrain(0)
    }

    @Test fun successfulRetryClearsAFileFailureWithoutAffectingOtherFiles() {
        val state = VolumeWriteState()
        state.finish(state.begin("/retry.bin"), IOException("short write"))
        state.finish(state.begin("/retry.bin"))
        state.pauseAndDrain(0)
    }

    @Test fun completingAnOldTicketCannotUnregisterANewerWrite() {
        val state = VolumeWriteState()
        val old = state.begin("/same.bin")
        state.finish(old)
        val current = state.begin("/same.bin")
        state.finish(old)
        assertThrows(IOException::class.java) { state.awaitPath("/same.bin", 0) }
        state.finish(current)
        state.awaitPath("/same.bin", 0)
    }

    @Test fun ejectWaitsForActualCompletion() {
        val state = VolumeWriteState()
        val ticket = state.begin("/busy.bin")
        val executor = Executors.newSingleThreadExecutor()
        try {
            val entered = CountDownLatch(1)
            val result = executor.submit {
                entered.countDown()
                state.pauseAndDrain(2000)
            }
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            assertFalse(result.isDone)
            state.finish(ticket)
            result.get(2, TimeUnit.SECONDS)
        } finally { executor.shutdownNow() }
    }
}
