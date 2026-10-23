package com.bitlockerdroid.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionAdmissionTest {
    private class Candidate : AutoCloseable {
        var closed = false
        override fun close() {
            closed = true
        }
    }

    @Test
    fun failedReadValidationClosesCandidateAndDoesNotReturnItForPublication() {
        val candidate = Candidate()
        var published = false

        val admission = SessionAdmission.validateBeforePublish(
            candidate,
            validate = { throw IllegalStateException("boot sector unreadable") },
            publish = { published = true },
        )

        assertTrue(admission.isFailure)
        assertTrue(candidate.closed)
        assertFalse(published)
    }

    @Test
    fun successfulValidationKeepsCandidateOpenForPublication() {
        val candidate = Candidate()

        var published = false
        val admission = SessionAdmission.validateBeforePublish(candidate, validate = { }, publish = { published = true })

        assertTrue(admission.isSuccess)
        assertFalse(candidate.closed)
        assertTrue(published)
    }
}
