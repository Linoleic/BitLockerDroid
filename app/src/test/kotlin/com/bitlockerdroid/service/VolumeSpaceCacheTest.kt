package com.bitlockerdroid.service

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class VolumeSpaceCacheTest {
    @Test fun snapshotDoesNotWaitForFilesystemScanAndRacingMutationCannotRestoreOldValue() {
        val cache = VolumeSpaceCache()
        cache.query { 100L to 80L }
        val entered = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val threads = Executors.newFixedThreadPool(2)
        try {
            val scan = threads.submit<Pair<Long, Long>?> {
                cache.query { entered.countDown(); check(finish.await(3, TimeUnit.SECONDS)); 100L to 70L }
            }
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            assertEquals(100L to 80L, threads.submit<Pair<Long, Long>?> { cache.peek() }.get(1, TimeUnit.SECONDS))
            cache.invalidate()
            assertNull(cache.peek())
            finish.countDown(); scan.get(1, TimeUnit.SECONDS)
            assertNull("Stale result from before mutation must not be cached", cache.peek())
        } finally { finish.countDown(); threads.shutdownNow() }
    }

    @Test fun unknownOrInvalidSpaceIsNeverDisplayedAsUsableCapacity() {
        val cache = VolumeSpaceCache()
        assertNull(cache.peek())
        for (value in listOf(null, 0L to 0L, 100L to -1L, 100L to 101L)) {
            assertNull(cache.query { value }); assertNull(cache.peek())
        }
        assertEquals(100L to 0L, cache.query { 100L to 0L })
        assertEquals(100L to 0L, cache.peek())
    }
}
