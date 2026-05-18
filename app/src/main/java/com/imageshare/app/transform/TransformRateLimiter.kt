package com.imageshare.app.transform

import androidx.annotation.VisibleForTesting
import java.util.ArrayDeque

internal class TransformRateLimiter(
    private val perUidPerMinute: Int,
    private val maxConcurrentPerUid: Int,
    private val maxConcurrentProcessWide: Int,
    private val timeSource: TimeSource = SystemTimeSource,
) {
    interface TimeSource {
        fun nowMillis(): Long
    }

    private data class RateState(
        val starts: ArrayDeque<Long> = ArrayDeque(),
        var inFlight: Int = 0,
    )

    private val lock = Any()
    private val states = LinkedHashMap<Int, RateState>()
    private var processInFlight = 0

    fun acquire(uid: Int): Lease {
        synchronized(lock) {
            val now = timeSource.nowMillis()
            sweepAll(now)
            val state = states.getOrPut(uid) { RateState() }
            if (state.inFlight >= maxConcurrentPerUid) {
                throw TransformError.RateLimited(RateLimitCode.RateLimit)
            }
            if (processInFlight >= maxConcurrentProcessWide) {
                throw TransformError.SystemBusy
            }
            if (state.starts.size >= perUidPerMinute) {
                throw TransformError.RateLimited(RateLimitCode.RateLimit)
            }
            state.starts.addLast(now)
            state.inFlight += 1
            processInFlight += 1
            return Lease(uid, this)
        }
    }

    fun recordRequest(uid: Int) {
        synchronized(lock) {
            val now = timeSource.nowMillis()
            sweepAll(now)
            val state = states.getOrPut(uid) { RateState() }
            if (state.starts.size >= perUidPerMinute) {
                throw TransformError.RateLimited(RateLimitCode.RateLimit)
            }
            state.starts.addLast(now)
        }
    }

    fun trim() {
        synchronized(lock) {
            sweepAll(timeSource.nowMillis())
        }
    }

    @VisibleForTesting
    fun resetForTests() {
        synchronized(lock) {
            states.clear()
            processInFlight = 0
        }
    }

    fun snapshotForTests(uid: Int): Snapshot = synchronized(lock) {
        sweepAll(timeSource.nowMillis())
        val state = states[uid]
        Snapshot(
            perUidWindowCount = state?.starts?.size ?: 0,
            perUidInFlight = state?.inFlight ?: 0,
            processInFlight = processInFlight,
        )
    }

    private fun release(uid: Int) {
        synchronized(lock) {
            val state = states[uid] ?: return
            if (state.inFlight > 0) state.inFlight -= 1
            if (processInFlight > 0) processInFlight -= 1
            sweepAll(timeSource.nowMillis())
        }
    }

    private fun sweepAll(nowMillis: Long) {
        val cutoff = nowMillis - WINDOW_MS
        val iterator = states.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val state = entry.value
            while (state.starts.isNotEmpty() && state.starts.first <= cutoff) {
                state.starts.removeFirst()
            }
            if (state.inFlight == 0 && state.starts.isEmpty()) {
                iterator.remove()
            }
        }
    }

    class Lease internal constructor(
        private val uid: Int,
        private val owner: TransformRateLimiter,
    ) : AutoCloseable {
        private var closed = false

        override fun close() {
            if (!closed) {
                closed = true
                owner.release(uid)
            }
        }
    }

    data class Snapshot(
        val perUidWindowCount: Int,
        val perUidInFlight: Int,
        val processInFlight: Int,
    )

    private object SystemTimeSource : TimeSource {
        override fun nowMillis(): Long = System.currentTimeMillis()
    }

    private companion object {
        private const val WINDOW_MS = 60_000L
    }
}
