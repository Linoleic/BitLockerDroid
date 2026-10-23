package com.bitlockerdroid.service

import java.util.concurrent.locks.ReentrantReadWriteLock

/** Coordinates session-producing work with a one-way process mode transition. */
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
    fun <T> prepare(block: () -> T): T = synchronized(prepareLock) {
        check(!admissionClosed) { "A mode transition is already prepared" }
        // Publish closure before waiting for current admissions to drain. New
        // callers can now reject immediately instead of joining the drain.
        admissionClosed = true
        val write = lock.writeLock()
        write.lock()
        try {
            block()
        } catch (failure: Throwable) {
            admissionClosed = false
            throw failure
        } finally {
            write.unlock()
        }
    }
}

internal object ProcessSessionTransition {
    val gate = SessionTransitionGate()
}
