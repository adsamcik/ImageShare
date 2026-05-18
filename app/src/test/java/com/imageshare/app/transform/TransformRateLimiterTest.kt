package com.imageshare.app.transform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransformRateLimiterTest {
    @Test fun rollingWindowRejectsOverLimitAndAllowsAfterWindow() {
        val time = MutableTimeSource()
        val limiter = TransformRateLimiter(
            perUidPerMinute = 3,
            maxConcurrentPerUid = 2,
            maxConcurrentProcessWide = 8,
            timeSource = time,
        )

        repeat(3) { limiter.acquire(UID).close() }
        val rejected = runCatching { limiter.acquire(UID) }.exceptionOrNull()
        assertTrue(rejected is TransformError.RateLimited)
        assertEquals("RATE_LIMIT", (rejected as TransformError.RateLimited).code)

        time.now += 60_001L
        limiter.acquire(UID).close()
        assertEquals(1, limiter.snapshotForTests(UID).perUidWindowCount)
    }

    @Test fun concurrentCountersIncrementAndDecrement() {
        val limiter = TransformRateLimiter(
            perUidPerMinute = 10,
            maxConcurrentPerUid = 2,
            maxConcurrentProcessWide = 8,
            timeSource = MutableTimeSource(),
        )

        val first = limiter.acquire(UID)
        val second = limiter.acquire(UID)
        assertEquals(2, limiter.snapshotForTests(UID).perUidInFlight)
        assertEquals(2, limiter.snapshotForTests(UID).processInFlight)

        val rejected = runCatching { limiter.acquire(UID) }.exceptionOrNull()
        assertTrue(rejected is TransformError.RateLimited)
        first.close()
        second.close()
        assertEquals(0, limiter.snapshotForTests(UID).perUidInFlight)
        assertEquals(0, limiter.snapshotForTests(UID).processInFlight)
    }

    @Test fun processWideLimitReturnsSystemBusy() {
        val limiter = TransformRateLimiter(
            perUidPerMinute = 10,
            maxConcurrentPerUid = 2,
            maxConcurrentProcessWide = 2,
            timeSource = MutableTimeSource(),
        )
        val leases = listOf(limiter.acquire(1), limiter.acquire(2))

        val rejected = runCatching { limiter.acquire(3) }.exceptionOrNull()
        assertTrue(rejected is TransformError.SystemBusy)
        assertEquals("SYSTEM_BUSY", (rejected as TransformError.SystemBusy).code)
        leases.forEach { it.close() }
    }

    @Test fun rateLimitCodeIsStable() {
        val error = TransformError.RateLimited(RateLimitCode.RateLimit)
        assertEquals("RATE_LIMIT", error.code)
        assertEquals("ImageShareTransform: RATE_LIMIT: Transform rate limit exceeded", error.fileNotFoundMessage())
    }

    private class MutableTimeSource(var now: Long = 0L) : TransformRateLimiter.TimeSource {
        override fun nowMillis(): Long = now
    }

    private companion object {
        private const val UID = 42
    }
}
