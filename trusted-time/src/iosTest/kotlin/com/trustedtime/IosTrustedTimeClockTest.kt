package com.trustedtime

import kotlinx.coroutines.runBlocking
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class IosTrustedTimeClockTest {
    private var fakeMonotonicNanos = 0L
    private var fakeSystemTime = Instant.fromEpochMilliseconds(0)
    private var fakeBootId: String? = "boot-1"
    private val fakeStore =
        object : SyncStore {
            var saved: String? = null

            override fun load() = saved

            override fun save(value: String) {
                saved = value
            }
        }

    private val serverStartMs = 1_700_000_000_250L

    private fun realTimeMs() = serverStartMs + fakeMonotonicNanos / 1_000_000

    private var requestMs = 20L

    private var fakeServer: suspend () -> Long? = {
        fakeMonotonicNanos += requestMs * 500_000
        val headerMs = realTimeMs() / 1_000 * 1_000
        fakeMonotonicNanos += requestMs * 500_000
        headerMs
    }

    private fun newClock() =
        TrustedTimeClock(
            monotonicNanos = { fakeMonotonicNanos },
            systemNow = { fakeSystemTime },
            fetchServerTimeMs = { fakeServer() },
            bootId = { fakeBootId },
            store = fakeStore,
            sleepNanos = { fakeMonotonicNanos += it },
        )

    private val clock = newClock()

    private fun assertAboutRealTime(now: Instant) {
        val errorMs = now.toEpochMilliseconds() - realTimeMs()
        assertTrue(abs(errorMs) <= 40, "off by $errorMs ms")
    }

    @Test
    fun syncFindsTheServerTime() =
        runBlocking {
            assertTrue(clock.sync())

            assertTrue(clock.isTimeSynced())
            assertAboutRealTime(clock.now())
        }

    @Test
    fun nowAdvancesWithMonotonicTimeAfterSync() =
        runBlocking {
            clock.sync()

            fakeMonotonicNanos += 3_600_000_000_000L // 1 hour passes, e.g. while the device sleeps

            assertAboutRealTime(clock.now())
        }

    @Test
    fun changingSystemClockAfterSyncDoesNotAffectNow() =
        runBlocking {
            clock.sync()

            // User moves the device clock forward by a year; only 1 real second passes
            fakeSystemTime = Instant.fromEpochMilliseconds(serverStartMs + 365L * 24 * 60 * 60 * 1000)
            fakeMonotonicNanos += 1_000_000_000L

            assertAboutRealTime(clock.now())
            assertEquals(TimeSource.TRUSTED, clock.timeSource())
        }

    @Test
    fun connectionSetupIsNotCountedAsRoundTrip() =
        runBlocking {
            var requests = 0
            val warmServer = fakeServer
            fakeServer = {
                if (++requests == 1) fakeMonotonicNanos += 2_000_000_000L // the first request sets up the connection
                warmServer()
            }

            clock.sync()

            assertAboutRealTime(clock.now())
        }

    @Test
    fun missingDateHeaderLeavesClockUnsynced() =
        runBlocking {
            fakeSystemTime = Instant.fromEpochMilliseconds(42)
            fakeServer = { null }

            assertFalse(clock.sync())

            assertFalse(clock.isTimeSynced())
            assertEquals(42, clock.now().toEpochMilliseconds())
        }

    @Test
    fun failedResyncKeepsPreviousSync() =
        runBlocking {
            clock.sync()
            val synced = clock.now()

            fakeServer = { throw IllegalStateException("network down") }

            assertFalse(clock.sync())
            assertTrue(clock.isTimeSynced())
            assertEquals(synced, clock.now())
        }

    @Test
    fun syncSurvivesAnAppRestart() =
        runBlocking {
            clock.sync()

            fakeMonotonicNanos += 60_000_000_000L // the app is closed and reopened a minute later
            val restarted = newClock()

            assertTrue(restarted.isTimeSynced())
            assertEquals(TimeSource.TRUSTED, restarted.timeSource())
            assertEquals(clock.now(), restarted.now())
        }

    @Test
    fun savedSyncIsIgnoredAfterAReboot() =
        runBlocking {
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
            clock.sync()

            fakeMonotonicNanos = 1_000_000_000L
            assertFalse(newClock().isTimeSynced())
        }

    @Test
    fun nothingIsSavedWithoutABootId() =
        runBlocking {
            fakeBootId = null
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
