package com.trustedtime

import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class WasmTrustedTimeClockTest {
    private var fakeMonotonicNanos = 0L
    private val systemStartMs = 1_700_000_000_000L - 4 * 3_600_000
    private var systemJumpMs = 0L
    private val serverStartMs = 1_700_000_000_250L

    private fun realTimeMs() = serverStartMs + fakeMonotonicNanos / 1_000_000

    private var fakeServer: suspend () -> Long? = {
        fakeMonotonicNanos += 10_000_000
        val headerMs = realTimeMs() / 1_000 * 1_000
        fakeMonotonicNanos += 10_000_000
        headerMs
    }

    private val clock =
        TrustedTimeClock(
            monotonicNanos = { fakeMonotonicNanos },
            systemNow = { Instant.fromEpochMilliseconds(systemStartMs + fakeMonotonicNanos / 1_000_000 + systemJumpMs) },
            fetchServerTimeMs = { fakeServer() },
            sleepNanos = { fakeMonotonicNanos += it },
        )

    private fun assertAboutRealTime(now: Instant) {
        val errorMs = now.toEpochMilliseconds() - realTimeMs()
        assertTrue(abs(errorMs) <= 40, "off by $errorMs ms")
    }

    @Test
    fun syncFindsTheServerTimeDespiteAWrongDeviceClock() =
        runTest {
            assertTrue(clock.sync())

            assertEquals(TimeSource.TRUSTED, clock.timeSource())
            assertAboutRealTime(clock.now())
        }

    @Test
    fun keepsTheTrustedTimeWhenTheDeviceClockIsMovedBack() =
        runTest {
            assertTrue(clock.sync())

            fakeMonotonicNanos += 60_000_000_000L
            systemJumpMs = -3_600_000

            assertEquals(TimeSource.TRUSTED, clock.timeSource())
            assertAboutRealTime(clock.now())
        }

    @Test
    fun fallsBackWhenTheCounterPausedWhileTheTabWasHidden() =
        runTest {
            assertTrue(clock.sync())

            systemJumpMs = 600_000

            assertEquals(TimeSource.SYSTEM_FALLBACK, clock.timeSource())
            assertTrue(clock.isTimeSynced())
        }

    @Test
    fun aFailedSyncLeavesTheSystemTime() =
        runTest {
            fakeServer = { null }

            assertFalse(clock.sync())

            assertFalse(clock.isTimeSynced())
            assertEquals(TimeSource.SYSTEM_FALLBACK, clock.timeSource())
        }

    @Test
    fun aThrowingFetchIsAFailedSync() =
        runTest {
            fakeServer = { error("blocked by CORS") }

            assertFalse(clock.sync())
        }
}
