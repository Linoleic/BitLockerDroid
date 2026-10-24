package com.bitlockerdroid.service

import java.util.concurrent.locks.ReentrantReadWriteLock

/** Coordinates session-producing work with exclusive closure and mode transitions. */
internal class SessionTransitionGate {
    private val lock = ReentrantReadWriteLock(true)
    private val prepareLock = Any()

    @Volatile
    private var admissionClosed = false

    sealed class Admission<out T> {
        data class Accepted<T>(val value: T) : Admission<T>()
        object Rejected : Admission<Nothing>()
    }

    fun <T> admit(block: () -> T): Admission<T> {
        if (admissionClosed) return Admission.Rejected
        val read = lock.readLock()
        read.lock()
        try {
            // Check again after locking: a transition may have started while this
            // caller was waiting behind the writer.
            if (admissionClosed) return Admission.Rejected
            return Admission.Accepted(block())
        } finally {
            read.unlock()
        }
    }

    /** On success, admissions stay closed for the rest of this process. */
    fun <T> prepare(block: () -> T): T = exclusively(keepClosed = true, block)

    /** Drains admitted work, excludes new sessions, and reopens on success or failure. */
    fun <T> withAdmissionsPaused(block: () -> T): T = exclusively(keepClosed = false, block)

    private fun <T> exclusively(keepClosed: Boolean, block: () -> T): T = synchronized(prepareLock) {
        check(!admissionClosed) { "A mode transition is already prepared" }
        // Publish closure before waiting for current admissions to drain. New
        // callers can now reject immediately instead of joining the drain.
        admissionClosed = true
        val write = lock.writeLock()
        write.lock()
        var prepared = false
        try {
            block().also { prepared = keepClosed }
        } finally {
            if (!prepared) admissionClosed = false
            write.unlock()
        }
    }
}

internal object ProcessSessionTransition {
    val gate = SessionTransitionGate()
}
