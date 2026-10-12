package com.trustedtime

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

class TrustedTimeClockTest {
    @Test
    fun nowBeforeSyncUsesSystemFallback() {
        val clock = TrustedTimeClock()
        val now: Instant = clock.now()
        assertNotNull(now)
        assertTrue(clock.timeSource() == TimeSource.SYSTEM_FALLBACK)
    }

    @Test
    fun isTimeSyncedIsFalseBeforeSync() {
        val clock = TrustedTimeClock()
        assertTrue(!clock.isTimeSynced())
    }

    @Test
    fun timeSourceDoesNotDependOnPriorNowCall() {
        val clock = TrustedTimeClock()
        assertTrue(clock.timeSource() == TimeSource.SYSTEM_FALLBACK)
        clock.now()
        assertTrue(clock.timeSource() == TimeSource.SYSTEM_FALLBACK)
    }

    @Test
    fun nowReturnsReasonableTimestamp() {
        val clock = TrustedTimeClock()
        val now = clock.now()
        val systemNow = Clock.System.now()
        val diffMs =
            kotlin.math.abs(
                now.toEpochMilliseconds() - systemNow.toEpochMilliseconds(),
            )
        assertTrue(diffMs < 60_000, "Clock drift too large: ${diffMs}ms")
    }
}
