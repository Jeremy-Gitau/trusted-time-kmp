package com.trustedtime

import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class JvmTrustedTimeClockTest {

    private var fakeMonotonicNanos = 0L
    private var fakeSystemTime = Instant.fromEpochMilliseconds(0)

    private val clock = TrustedTimeClock(
        monotonicNanos = { fakeMonotonicNanos },
        systemNow = { fakeSystemTime },
    )

    @Test
    fun nowAdvancesWithMonotonicTimeAfterSync() {
        val ntpTime = 1_700_000_000_000L
        clock.applySync(ntpTime)

        fakeMonotonicNanos += 5_000_000_000L // 5 seconds pass

        assertEquals(ntpTime + 5_000, clock.now().toEpochMilliseconds())
    }

    @Test
    fun changingSystemClockAfterSyncDoesNotAffectNow() {
        val ntpTime = 1_700_000_000_000L
        fakeSystemTime = Instant.fromEpochMilliseconds(ntpTime)
        clock.applySync(ntpTime)

        // User moves the device clock forward by a year; only 1 real second passes
        fakeSystemTime = Instant.fromEpochMilliseconds(ntpTime + 365L * 24 * 60 * 60 * 1000)
        fakeMonotonicNanos += 1_000_000_000L

        assertEquals(ntpTime + 1_000, clock.now().toEpochMilliseconds())
        assertEquals(TimeSource.TRUSTED, clock.timeSource())
    }

    @Test
    fun nowUsesSystemClockBeforeSync() {
        fakeSystemTime = Instant.fromEpochMilliseconds(42)

        assertEquals(42, clock.now().toEpochMilliseconds())
        assertEquals(TimeSource.SYSTEM_FALLBACK, clock.timeSource())
    }
}
