package com.bitlockerdroid.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SessionTransitionGateTest {
    @Test
    fun prepareClosesAdmissionImmediatelyAndWaitsForExistingWork() {
        val gate = SessionTransitionGate()
        val executor = Executors.newFixedThreadPool(2)
        val admissionEntered = CountDownLatch(1)
        val releaseAdmission = CountDownLatch(1)
        val prepareEntered = CountDownLatch(1)
        val releasePrepare = CountDownLatch(1)
        try {
            val activeAdmission = executor.submit(Callable {
                gate.admit {
                    admissionEntered.countDown()
                    check(releaseAdmission.await(5, TimeUnit.SECONDS))
                    "finished"
                }
            })
            assertTrue(admissionEntered.await(5, TimeUnit.SECONDS))

            val preparation = executor.submit(Callable {
                gate.prepare {
                    prepareEntered.countDown()
                    check(releasePrepare.await(5, TimeUnit.SECONDS))
                    "prepared"
                }
            })
            // Probe until closure is visible. This is bounded and also proves
            // the attempted operation does not enter while the old one drains.
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            var rejected = false
            while (!rejected && System.nanoTime() < deadline) {
                rejected = gate.admit { Unit } is
                    SessionTransitionGate.Admission.Rejected
                if (!rejected) Thread.yield()
            }
            assertTrue("transition did not close admission", rejected)
            assertFalse("prepare ran before active admission finished", prepareEntered.await(100, TimeUnit.MILLISECONDS))

            releaseAdmission.countDown()
            assertEquals("finished", (activeAdmission.get(5, TimeUnit.SECONDS) as SessionTransitionGate.Admission.Accepted).value)
            assertTrue(prepareEntered.await(5, TimeUnit.SECONDS))
            assertTrue(gate.admit { "during" } is SessionTransitionGate.Admission.Rejected)
            releasePrepare.countDown()
            assertEquals("prepared", preparation.get(5, TimeUnit.SECONDS))
            assertTrue(gate.admit { "after" } is SessionTransitionGate.Admission.Rejected)
        } finally {
            releaseAdmission.countDown()
            releasePrepare.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun failedPreparationRestoresAdmission() {
        val gate = SessionTransitionGate()
        try {
            gate.prepare { throw IOException("flush failed") }
            throw AssertionError("preparation should fail")
        } catch (expected: IOException) {
            assertEquals("flush failed", expected.message)
        }

        val admission = gate.admit { 42 }
        assertTrue(admission is SessionTransitionGate.Admission.Accepted)
        assertEquals(42, (admission as SessionTransitionGate.Admission.Accepted).value)
    }

    @Test
    fun admissionExceptionReleasesReadLock() {
        val gate = SessionTransitionGate()
        try {
            gate.admit<Int> { throw IllegalArgumentException("unlock failed") }
            throw AssertionError("admission should fail")
        } catch (expected: IllegalArgumentException) {
            assertEquals("unlock failed", expected.message)
        }

        assertEquals("prepared", gate.prepare { "prepared" })
        assertTrue(gate.admit { Unit } is SessionTransitionGate.Admission.Rejected)
    }
}
