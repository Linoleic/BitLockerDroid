package com.bitlockerdroid.service

import java.io.IOException
import java.util.concurrent.TimeUnit

/** One instance belongs to one decrypted session, so paths never alias across volumes. */
class VolumeWriteState {
    class Ticket internal constructor(val path: String)

    private val monitor = Object()
    private val active = mutableMapOf<String, Ticket>()
    private val failures = mutableMapOf<String, Throwable>()
    private var paused = false

    fun begin(path: String, timeoutMs: Long = 3000): Ticket = synchronized(monitor) {
        await(timeoutMs) { !active.containsKey(path) || paused }
        if (paused) throw IOException("Volume is being ejected; new writes are blocked")
        Ticket(path).also { active[path] = it }
    }

    fun finish(ticket: Ticket, failure: Throwable? = null) = synchronized(monitor) {
        if (active[ticket.path] !== ticket) return@synchronized
        if (failure == null) failures.remove(ticket.path) else failures[ticket.path] = failure
        active.remove(ticket.path)
        monitor.notifyAll()
    }

    fun awaitPath(path: String, timeoutMs: Long = 3000) = synchronized(monitor) {
        await(timeoutMs) { !active.containsKey(path) }
    }

    /** On failure the caller must resume admissions; active tickets are never discarded. */
    fun pauseAndDrain(timeoutMs: Long = 8000) = synchronized(monitor) {
        paused = true
        monitor.notifyAll()
        await(timeoutMs) { active.isEmpty() }
        if (failures.isNotEmpty()) {
            val (path, cause) = failures.entries.first()
            // Report failed transfers before ejecting. An explicit retry may flush and close
            // the volume after the user has been told that a file transfer was incomplete.
            failures.clear()
            throw IOException("A file transfer failed ($path); check the file before retrying eject", cause)
        }
    }

    fun resume() = synchronized(monitor) {
        paused = false
        monitor.notifyAll()
    }

    private fun await(timeoutMs: Long, ready: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (!ready()) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) throw IOException("File transfer is still running; wait for it to finish and try again")
            try {
                TimeUnit.NANOSECONDS.timedWait(monitor, remaining)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("Interrupted while waiting for file transfer", e)
            }
        }
    }
}
