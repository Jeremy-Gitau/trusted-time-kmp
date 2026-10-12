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

    private val dateHeaderMs = 1_700_000_000_000L
    private val syncedMs = dateHeaderMs + 500 // half a second for the header's rounding, no network delay

    private val clock =
        TrustedTimeClock(
            monotonicNanos = { fakeMonotonicNanos },
            systemNow = { fakeSystemTime },
            fetchServerTimeMs = { fakeServer() },
        )

    @Test
    fun nowAdvancesWithMonotonicTimeAfterSync() =
        runBlocking {
            fakeServer = { dateHeaderMs }
            clock.sync()

            fakeMonotonicNanos += 3_600_000_000_000L // 1 hour passes, e.g. while the device sleeps

            assertEquals(syncedMs + 3_600_000, clock.now().toEpochMilliseconds())
        }

    @Test
    fun changingSystemClockAfterSyncDoesNotAffectNow() =
        runBlocking {
            fakeServer = { dateHeaderMs }
            clock.sync()

            // User moves the device clock forward by a year; only 1 real second passes
            fakeSystemTime = Instant.fromEpochMilliseconds(dateHeaderMs + 365L * 24 * 60 * 60 * 1000)
            fakeMonotonicNanos += 1_000_000_000L

            assertEquals(syncedMs + 1_000, clock.now().toEpochMilliseconds())
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
            fakeServer = { dateHeaderMs }
            clock.sync()

            fakeServer = { throw IllegalStateException("network down") }
            clock.sync()

            assertTrue(clock.isTimeSynced())
            assertEquals(syncedMs, clock.now().toEpochMilliseconds())
        }

    @Test
    fun syncAllowsForTheRoundTrip() =
        runBlocking {
            fakeServer = {
                fakeMonotonicNanos += 200_000_000L // the request takes 200 ms
                dateHeaderMs
            }

            clock.sync()

            assertEquals(dateHeaderMs + 500 + 100, clock.now().toEpochMilliseconds())
        }

    @Test
    fun dateHeaderTimeAddsHalfASecondAndHalfTheRoundTrip() {
        assertEquals(1_650L, dateHeaderTimeAtReceiptMs(dateHeaderMs = 1_000, roundTripMs = 300))
    }
}
