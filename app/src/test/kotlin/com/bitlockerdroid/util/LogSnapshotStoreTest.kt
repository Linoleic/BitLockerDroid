package com.bitlockerdroid.util

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LogSnapshotStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun missingEmptyAndOversizedLogsRemainBoundedAndReadable() {
        val directory = temporary.newFolder()
        val store = LogSnapshotStore(directory, maxBytes = 128)
        assertNull(store.snapshot().application)
        repeat(30) { store.append("$it 中文日志\n") }
        val tail = store.snapshot().application!!
        assertTrue(store.applicationFile.length() <= 128)
        assertTrue(tail.endsWith("29 中文日志\n"))
        assertFalse(tail.contains('\uFFFD'))
        store.append("x".repeat(1024))
        assertTrue(store.applicationFile.length() <= 128)
        assertTrue(store.readApplication()!!.contains("Oversized log entry omitted"))
        store.clear()
        assertEquals("", store.snapshot().application)
    }

    @Test fun crashReadIsBoundedWithoutChangingTheRawCrashFile() {
        val directory = temporary.newFolder()
        val file = File(directory, "crash.txt")
        file.writeText("private-prefix\n" + "\tat crash.Line(中文.kt:123)\n".repeat(20000))
        val before = file.readBytes()
        val snapshot = LogSnapshotStore(directory).snapshot()
        assertTrue(snapshot.crash!!.toByteArray().size <= 256 * 1024)
        assertFalse(snapshot.crash.contains("private-prefix"))
        assertFalse(snapshot.crash.contains('\uFFFD'))
        assertTrue(snapshot.notes.any { it.contains("most recent complete lines") })
        assertArrayEquals(before, file.readBytes())
    }

    @Test fun concurrentAppendReadAndClearNeverExposeTornEntriesOrNativeLogLocks() {
        val store = LogSnapshotStore(temporary.newFolder(), maxBytes = 512)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(3)
        try {
            val jobs = listOf(
                executor.submit { start.await(); repeat(200) { store.append("entry-$it-完整\n") } },
                executor.submit { start.await(); repeat(50) { store.clear() } },
                executor.submit {
                    start.await()
                    repeat(200) {
                        val snapshot = store.snapshot()
                        snapshot.application.orEmpty().lineSequence().filter { it.isNotEmpty() }.forEach {
                            assertTrue("Torn line: $it", it.matches(Regex("entry-\\d+-完整")))
                        }
                    }
                }
            )
            start.countDown()
            jobs.forEach { it.get(5, TimeUnit.SECONDS) }
        } finally { executor.shutdownNow() }
    }
}
