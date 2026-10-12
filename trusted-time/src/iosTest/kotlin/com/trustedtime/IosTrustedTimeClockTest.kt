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
    private var fakeBootId: String? = "boot-1"
    private val fakeStore =
        object : SyncStore {
            var saved: String? = null

            override fun load() = saved

            override fun save(value: String) {
                saved = value
            }
        }

    private val dateHeaderMs = 1_700_000_000_000L
    private val syncedMs = dateHeaderMs + 500 // half a second for the header's rounding, no network delay

    private fun newClock() =
        TrustedTimeClock(
            monotonicNanos = { fakeMonotonicNanos },
            systemNow = { fakeSystemTime },
            fetchServerTimeMs = { fakeServer() },
            bootId = { fakeBootId },
            store = fakeStore,
        )

    private val clock = newClock()

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
    fun connectionSetupIsNotCountedAsRoundTrip() =
        runBlocking {
            var requests = 0
            fakeServer = {
                requests++
                fakeMonotonicNanos += if (requests == 1) 2_000_000_000L else 200_000_000L // cold, then warm
                dateHeaderMs
            }

            clock.sync()

            assertEquals(2, requests)
            assertEquals(dateHeaderMs + 500 + 100, clock.now().toEpochMilliseconds())
        }

    @Test
    fun dateHeaderTimeAddsHalfASecondAndHalfTheRoundTrip() {
        assertEquals(1_650L, dateHeaderTimeAtReceiptMs(dateHeaderMs = 1_000, roundTripMs = 300))
    }

    @Test
    fun syncSurvivesAnAppRestart() =
        runBlocking {
            fakeServer = { dateHeaderMs }
            clock.sync()

            fakeMonotonicNanos += 60_000_000_000L // the app is closed and reopened a minute later
            val restarted = newClock()

            assertTrue(restarted.isTimeSynced())
            assertEquals(TimeSource.TRUSTED, restarted.timeSource())
            assertEquals(syncedMs + 60_000, restarted.now().toEpochMilliseconds())
        }

    @Test
    fun savedSyncIsIgnoredAfterAReboot() =
        runBlocking {
            fakeServer = { dateHeaderMs }
            clock.sync()

            fakeBootId = "boot-2"
            val afterReboot = newClock()

            assertFalse(afterReboot.isTimeSynced())
            assertEquals(TimeSource.SYSTEM_FALLBACK, afterReboot.timeSource())
        }

    @Test
    fun savedSyncIsIgnoredIfTheCounterWentBack() =
        runBlocking {
            fakeMonotonicNanos = 5_000_000_000L
            fakeServer = { dateHeaderMs }
            clock.sync()

            fakeMonotonicNanos = 1_000_000_000L
            assertFalse(newClock().isTimeSynced())
        }

    @Test
    fun nothingIsSavedWithoutABootId() =
        runBlocking {
            fakeBootId = null
            fakeServer = { dateHeaderMs }
            clock.sync()

            assertTrue(clock.isTimeSynced())
            assertEquals(null, fakeStore.saved)
        }

    @Test
    fun bootIdCanBeRead() {
        val id = bootSessionId()
        assertTrue(id != null && id.length == 36, "unexpected boot id: $id")
        assertEquals(id, bootSessionId())
    }
}
