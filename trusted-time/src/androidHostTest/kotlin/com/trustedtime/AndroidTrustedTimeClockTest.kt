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

    private val clock =
        TrustedTimeClock(
            createPlayServicesClient = { fakePlayServices() },
            elapsedRealtimeNanos = { fakeElapsedNanos },
            systemNow = { fakeSystemTime },
            fetchHttpsTimeMs = { fakeHttps() },
        )

    @Test
    fun usesPlayServicesWhenItHasASignal() =
        runBlocking {
            fakePlayServices = { FakeClient(1_700_000_000_000L) }
            fakeHttps = { error("must not be called") }

            clock.sync()

            assertEquals(TimeSource.TRUSTED, clock.timeSource())
            assertEquals(1_700_000_000_000L, clock.now().toEpochMilliseconds())
        }

    @Test
    fun fallsBackToHttpsWithoutPlayServices() =
        runBlocking {
            fakeHttps = { 1_700_000_000_000L }

            clock.sync()
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

            clock.sync()

            assertEquals(1_700_000_000_000L, clock.now().toEpochMilliseconds())
        }

    @Test
    fun usesSystemTimeWhenEverythingFails() =
        runBlocking {
            fakeSystemTime = Instant.fromEpochMilliseconds(42)

            clock.sync()

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
