package com.trustedtime

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

class JvmTrustedTimeClockTest {
    private var fakeMonotonicNanos = 0L
    private var fakeSystemTime = Instant.fromEpochMilliseconds(0)
    private var fakeNtp: suspend () -> Long = { error("NTP not set up for this test") }

    private val clock =
        TrustedTimeClock(
            monotonicNanos = { fakeMonotonicNanos },
            systemNow = { fakeSystemTime },
            fetchTimeMs = { fakeNtp() },
        )

    @Test
    fun nowAdvancesWithMonotonicTimeAfterSync() {
        val ntpTime = 1_700_000_000_000L
        clock.applySync(ntpTime)

        fakeMonotonicNanos += 5_000_000_000L // 5 seconds pass

        assertEquals(ntpTime + 5_000, clock.now().toEpochMilliseconds())
    }

    @Test
    fun movingSystemClockBackDoesNotAffectNow() {
        val ntpTime = 1_700_000_000_000L
        fakeSystemTime = Instant.fromEpochMilliseconds(ntpTime)
        clock.applySync(ntpTime)

        // User moves the clock back a year, e.g. to extend a trial; 1 real second passes
        fakeSystemTime = Instant.fromEpochMilliseconds(ntpTime - 365L * 24 * 60 * 60 * 1000)
        fakeMonotonicNanos += 1_000_000_000L

        assertEquals(ntpTime + 1_000, clock.now().toEpochMilliseconds())
        assertEquals(TimeSource.TRUSTED, clock.timeSource())
    }

    @Test
    fun movingSystemClockForwardFallsBackToSystemTime() {
        val ntpTime = 1_700_000_000_000L
        fakeSystemTime = Instant.fromEpochMilliseconds(ntpTime)
        clock.applySync(ntpTime)

        val yearAhead = Instant.fromEpochMilliseconds(ntpTime + 365L * 24 * 60 * 60 * 1000)
        fakeSystemTime = yearAhead
        fakeMonotonicNanos += 1_000_000_000L

        assertEquals(TimeSource.SYSTEM_FALLBACK, clock.timeSource())
        assertEquals(yearAhead, clock.now())
        assertTrue(clock.isTimeSynced())
    }

    @Test
    fun sleepFallsBackUntilNextSync() =
        runBlocking {
            val ntpTime = 1_700_000_000_000L
            fakeSystemTime = Instant.fromEpochMilliseconds(ntpTime)
            fakeNtp = { ntpTime }
            clock.sync()

            // 8 hours of sleep: the system clock moves on, the monotonic counter doesn't
            val eightHours = 8L * 60 * 60 * 1000
            fakeSystemTime = Instant.fromEpochMilliseconds(ntpTime + eightHours)
            assertEquals(TimeSource.SYSTEM_FALLBACK, clock.timeSource())

            fakeNtp = { ntpTime + eightHours }
            clock.sync()

            assertEquals(TimeSource.TRUSTED, clock.timeSource())
            assertEquals(ntpTime + eightHours, clock.now().toEpochMilliseconds())
        }

    @Test
    fun smallSystemClockCorrectionsAreAllowed() {
        val hourMs = 60L * 60 * 1000
        assertFalse(systemClockRanAhead(elapsedMs = hourMs, systemElapsedMs = hourMs + 5_000))
        assertTrue(systemClockRanAhead(elapsedMs = hourMs, systemElapsedMs = hourMs + 10_000))
        assertFalse(systemClockRanAhead(elapsedMs = 0, systemElapsedMs = 2_000))
    }

    @Test
    fun worksAnywhereAClockIsExpected() {
        fakeSystemTime = Instant.parse("2020-01-01T00:00:00Z") // device clock set back to 2020
        clock.applySync(Instant.parse("2026-10-12T12:00:00Z").toEpochMilliseconds())

        val asClock: Clock = clock

        assertEquals(LocalDate(2026, 10, 12), asClock.todayIn(TimeZone.UTC))
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

            assertTrue(clock.sync())

            assertTrue(clock.isTimeSynced())
            assertEquals(1_700_000_000_000L, clock.now().toEpochMilliseconds())
        }

    @Test
    fun failedSyncFallsBackToSystemClock() =
        runBlocking {
            fakeNtp = { throw IOException("network down") }

            assertFalse(clock.sync()) // must not throw

            assertFalse(clock.isTimeSynced())
            assertEquals(TimeSource.SYSTEM_FALLBACK, clock.timeSource())
        }

    @Test
    fun failedResyncKeepsPreviousSync() =
        runBlocking {
            fakeNtp = { 1_700_000_000_000L }
            clock.sync()

            fakeNtp = { throw IOException("network down") }
            assertFalse(clock.sync())

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

    @Test
    fun ntpTimeAddsOneWayNetworkDelay() {
        // 100 ms round trip, of which the server spent 20 ms: 40 ms each way
        val serverReceiveMs = 1_700_000_000_000L
        val serverTransmitMs = serverReceiveMs + 20

        assertEquals(serverTransmitMs + 40, ntpTimeAtReceiptMs(serverReceiveMs, serverTransmitMs, roundTripMs = 100))
    }

    @Test
    fun ntpDelayIsNeverNegative() {
        // Server claims more processing time than the whole round trip (bad server timestamps)
        assertEquals(1_000L, ntpTimeAtReceiptMs(serverReceiveMs = 0, serverTransmitMs = 1_000, roundTripMs = 50))
    }

    @Test
    fun fallbackIsUsedWhenPrimaryFails() =
        runBlocking {
            val time = withFallback(primary = { throw IOException("UDP 123 blocked") }, fallback = { 42L })
            assertEquals(42L, time)
        }

    @Test
    fun fallbackIsNotUsedWhenPrimaryWorks() =
        runBlocking {
            val time = withFallback(primary = { 1L }, fallback = { error("should not be called") })
            assertEquals(1L, time)
        }

    @Test
    fun cancellationIsNotTreatedAsAFailure() =
        runBlocking {
            var fallbackCalled = false
            val job =
                launch {
                    withFallback(primary = { awaitCancellation() }, fallback = { fallbackCalled = true })
                }
            yield()
            job.cancelAndJoin()

            assertFalse(fallbackCalled)
        }
}
