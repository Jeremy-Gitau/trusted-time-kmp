package com.trustedtime

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TrustedTimeClockTest {

    @Test
    fun `now() returns an instant before sync - uses system fallback`() {
        val clock = TrustedTimeClock()
        val now: Instant = clock.now()
        assertNotNull(now)
        assertTrue(clock.timeSource() == TimeSource.SYSTEM_FALLBACK)
    }

    @Test
    fun `isTimeSynced returns false before sync`() {
        val clock = TrustedTimeClock()
        assertTrue(!clock.isTimeSynced())
    }

    @Test
    fun `now() returns a reasonable timestamp`() {
        val clock = TrustedTimeClock()
        val now = clock.now()
        val systemNow = Clock.System.now()
        // Should be within 1 minute of system time (even on fallback)
        val diffMs = kotlin.math.abs(
            now.toEpochMilliseconds() - systemNow.toEpochMilliseconds()
        )
        assertTrue(diffMs < 60_000, "Clock drift too large: ${diffMs}ms")
    }
}
