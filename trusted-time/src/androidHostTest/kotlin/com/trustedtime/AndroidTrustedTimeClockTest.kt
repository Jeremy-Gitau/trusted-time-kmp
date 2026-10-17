package com.trustedtime

import com.google.android.gms.tasks.Task
import com.google.android.gms.time.TrustedTimeClient
import com.google.android.gms.time.trustedtime.OnNewTimeSignalAvailableListener
import com.google.android.gms.time.trustedtime.TimeSignal
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class AndroidTrustedTimeClockTest {
    private var fakeElapsedNanos = 0L
    private var fakeSystemTime = Instant.fromEpochMilliseconds(0)
    private var fakePlayServices: suspend () -> TrustedTimeClient = { error("Play services not available") }
    private var fakeHttps: suspend () -> Long = { error("HTTPS not set up for this test") }
    private var fakeBootId: String? = "count:1"
    private val fakeStore =
        object : SyncStore {
            var saved: String? = null

            override fun load() = saved

            override fun save(value: String) {
                saved = value
            }
        }

    private fun newClock() =
        TrustedTimeClock(
            createPlayServicesClient = { fakePlayServices() },
            elapsedRealtimeNanos = { fakeElapsedNanos },
            systemNow = { fakeSystemTime },
            fetchHttpsTimeMs = { fakeHttps() },
            bootId = { fakeBootId },
            store = fakeStore,
        )

    private val clock = newClock()

    @Test
    fun anAppRestartKeepsTheHttpsSync() =
        runBlocking {
            fakeElapsedNanos = 1_000_000_000L
            fakeHttps = { 1_700_000_000_000L }
            assertTrue(clock.sync())

            fakeElapsedNanos += 600_000_000_000L
            fakeSystemTime = Instant.fromEpochMilliseconds(1_700_000_000_000L - 4 * 3_600_000)
            val restarted = newClock()

            assertEquals(TimeSource.TRUSTED, restarted.timeSource())
            assertEquals(1_700_000_000_000L + 600_000, restarted.now().toEpochMilliseconds())
        }

    @Test
    fun anAppRestartKeepsAPlayServicesReading() =
        runBlocking {
            fakePlayServices = { FakeClient(1_700_000_000_000L) }
            assertTrue(clock.sync())

            fakeElapsedNanos += 60_000_000_000L
            fakePlayServices = { FakeClient(null) }
            val restarted = newClock()

            assertEquals(1_700_000_000_000L + 60_000, restarted.now().toEpochMilliseconds())
        }

    @Test
    fun aRebootDropsTheSavedSync() =
        runBlocking {
            fakeElapsedNanos = 5_000_000_000_000L
            fakeHttps = { 1_700_000_000_000L }
            assertTrue(clock.sync())

            fakeBootId = "count:2"
            fakeSystemTime = Instant.fromEpochMilliseconds(7)
            val restarted = newClock()

            assertEquals(TimeSource.SYSTEM_FALLBACK, restarted.timeSource())
            assertEquals(7, restarted.now().toEpochMilliseconds())
        }

    @Test
    fun aSavedSyncFromLaterInTheBootIsIgnored() =
        runBlocking {
            fakeElapsedNanos = 5_000_000_000_000L
            fakeHttps = { 1_700_000_000_000L }
            assertTrue(clock.sync())

            fakeElapsedNanos = 1_000_000_000L
            val restarted = newClock()

            assertFalse(restarted.isTimeSynced())
        }

    @Test
    fun withoutABootIdNothingIsSaved() =
        runBlocking {
            fakeBootId = null
            fakeHttps = { 1_700_000_000_000L }
            assertTrue(clock.sync())

            assertEquals(null, fakeStore.saved)
            assertFalse(newClock().isTimeSynced())
        }

    @Test
    fun usesPlayServicesWhenItHasASignal() =
        runBlocking {
            fakePlayServices = { FakeClient(1_700_000_000_000L) }
            fakeHttps = { error("must not be called") }

            assertTrue(clock.sync())

            assertEquals(TimeSource.TRUSTED, clock.timeSource())
            assertEquals(1_700_000_000_000L, clock.now().toEpochMilliseconds())
        }

    @Test
    fun fallsBackToHttpsWithoutPlayServices() =
        runBlocking {
            fakeHttps = { 1_700_000_000_000L }

            assertTrue(clock.sync())
            fakeElapsedNanos += 3_600_000_000_000L // an hour passes, including sleep

            assertTrue(clock.isTimeSynced())
            assertEquals(TimeSource.TRUSTED, clock.timeSource())
            assertEquals(1_700_000_000_000L + 3_600_000, clock.now().toEpochMilliseconds())
        }

    @Test
    fun fallsBackToHttpsWhilePlayServicesHasNoSignal() =
        runBlocking {
            fakePlayServices = { FakeClient(null) }
            fakeHttps = { 1_700_000_000_000L }

            assertTrue(clock.sync())

            assertEquals(1_700_000_000_000L, clock.now().toEpochMilliseconds())
        }

    @Test
    fun usesSystemTimeWhenEverythingFails() =
        runBlocking {
            fakeSystemTime = Instant.fromEpochMilliseconds(42)

            assertFalse(clock.sync())

            assertFalse(clock.isTimeSynced())
            assertEquals(TimeSource.SYSTEM_FALLBACK, clock.timeSource())
            assertEquals(42, clock.now().toEpochMilliseconds())
        }

    private class FakeClient(
        private val millis: Long?,
    ) : TrustedTimeClient {
        override fun computeCurrentUnixEpochMillis(): Long? = millis

        override fun computeCurrentInstant(): java.time.Instant = error("unused")

        override fun getLatestTimeSignal(): TimeSignal = error("unused")

        override fun dispose(): Task<Void> = error("unused")

        override fun addTimeSignalListener(listener: OnNewTimeSignalAvailableListener) = error("unused")

        override fun removeTimeSignalListener(listener: OnNewTimeSignalAvailableListener) = error("unused")
    }
}
