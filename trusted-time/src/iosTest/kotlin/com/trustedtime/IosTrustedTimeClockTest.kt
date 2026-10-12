package com.trustedtime

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class IosTrustedTimeClockTest {
    private var fakeMonotonicNanos = 0L
    private var fakeSystemTime = Instant.fromEpochMilliseconds(0)
    private var fakeServer: suspend () -> Long? = { error("Server not set up for this test") }

    private val clock =
        TrustedTimeClock(
            monotonicNanos = { fakeMonotonicNanos },
            systemNow = { fakeSystemTime },
            fetchServerTimeMs = { fakeServer() },
        )

    @Test
    fun nowAdvancesWithMonotonicTimeAfterSync() =
        runBlocking {
            fakeServer = { 1_700_000_000_000L }
            clock.sync()

            fakeMonotonicNanos += 3_600_000_000_000L // 1 hour passes, e.g. while the device sleeps

            assertEquals(1_700_000_000_000L + 3_600_000, clock.now().toEpochMilliseconds())
        }

    @Test
    fun changingSystemClockAfterSyncDoesNotAffectNow() =
        runBlocking {
            fakeServer = { 1_700_000_000_000L }
            clock.sync()

            // User moves the device clock forward by a year; only 1 real second passes
            fakeSystemTime = Instant.fromEpochMilliseconds(1_700_000_000_000L + 365L * 24 * 60 * 60 * 1000)
            fakeMonotonicNanos += 1_000_000_000L

            assertEquals(1_700_000_000_000L + 1_000, clock.now().toEpochMilliseconds())
            assertEquals(TimeSource.TRUSTED, clock.timeSource())
        }

    @Test
    fun missingDateHeaderLeavesClockUnsynced() =
        runBlocking {
            fakeSystemTime = Instant.fromEpochMilliseconds(42)
            fakeServer = { null }

            clock.sync()

            assertFalse(clock.isTimeSynced())
            assertEquals(42, clock.now().toEpochMilliseconds())
        }

    @Test
    fun failedResyncKeepsPreviousSync() =
        runBlocking {
            fakeServer = { 1_700_000_000_000L }
            clock.sync()

            fakeServer = { throw IllegalStateException("network down") }
            clock.sync()

            assertTrue(clock.isTimeSynced())
            assertEquals(1_700_000_000_000L, clock.now().toEpochMilliseconds())
        }
}
