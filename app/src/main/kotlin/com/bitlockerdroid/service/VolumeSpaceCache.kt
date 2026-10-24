package com.bitlockerdroid.service

/** Display snapshots never start filesystem scans or wait for a scan already running. */
internal class VolumeSpaceCache {
    private var generation = 0L
    private var cached: Pair<Long, Long>? = null

    @Synchronized fun peek(): Pair<Long, Long>? = cached

    @Synchronized fun invalidate() {
        generation++
        cached = null
    }

    fun query(load: () -> Pair<Long, Long>?): Pair<Long, Long>? {
        val before = synchronized(this) { generation }
        val result = load()?.takeIf { it.first > 0 && it.second in 0..it.first }
        synchronized(this) {
            // A mutation racing a slow query must not republish stale free space.
            if (generation == before) cached = result
        }
        return result
    }
}
