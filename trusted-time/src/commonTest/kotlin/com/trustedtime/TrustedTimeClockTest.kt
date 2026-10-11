package com.trustedtime

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

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
        // Should be within 1 minute of system time (even on fallback)
        val diffMs =
            kotlin.math.abs(
                now.toEpochMilliseconds() - systemNow.toEpochMilliseconds(),
            )
        assertTrue(diffMs < 60_000, "Clock drift too large: ${diffMs}ms")
    }
}
