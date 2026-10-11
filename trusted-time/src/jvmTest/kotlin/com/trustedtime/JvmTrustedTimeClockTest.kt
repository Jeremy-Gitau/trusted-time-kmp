package com.trustedtime

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JvmTrustedTimeClockTest {
    private var fakeMonotonicNanos = 0L
    private var fakeSystemTime = Instant.fromEpochMilliseconds(0)
    private var fakeNtp: suspend () -> Long = { error("NTP not set up for this test") }

    private val clock =
        TrustedTimeClock(
            monotonicNanos = { fakeMonotonicNanos },
            systemNow = { fakeSystemTime },
            fetchNtpTimeMs = { fakeNtp() },
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

    @Test
    fun syncUsesFetchedNtpTime() =
        runBlocking {
            fakeNtp = { 1_700_000_000_000L }

            clock.sync()

            assertTrue(clock.isTimeSynced())
            assertEquals(1_700_000_000_000L, clock.now().toEpochMilliseconds())
        }

    @Test
    fun failedSyncFallsBackToSystemClock() =
        runBlocking {
            fakeNtp = { throw IOException("network down") }

            clock.sync() // must not throw

            assertFalse(clock.isTimeSynced())
            assertEquals(TimeSource.SYSTEM_FALLBACK, clock.timeSource())
        }

    @Test
    fun failedResyncKeepsPreviousSync() =
        runBlocking {
            fakeNtp = { 1_700_000_000_000L }
            clock.sync()

            fakeNtp = { throw IOException("network down") }
            clock.sync()

            assertTrue(clock.isTimeSynced())
            assertEquals(1_700_000_000_000L, clock.now().toEpochMilliseconds())
        }

    @Test
    fun syncCanBeCancelled() =
        runBlocking {
            val started = CompletableDeferred<Unit>()
            fakeNtp = {
                started.complete(Unit)
                awaitCancellation() // simulates a slow network
            }

            var ranAfterSync = false
            val job =
                launch {
                    clock.sync()
                    ranAfterSync = true // must not run: cancellation should propagate
                }
            started.await()
            job.cancelAndJoin()

            assertFalse(ranAfterSync)
            assertFalse(clock.isTimeSynced())
        }
}
