package com.trustedtime

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.apache.commons.net.ntp.NTPUDPClient
import java.net.InetAddress
import java.time.Duration
import kotlin.coroutines.cancellation.CancellationException

/**
 * JVM actual — fetches time from an NTP server using Apache Commons Net.
 *
 * After sync, time is advanced with [System.nanoTime], a monotonic counter
 * that is unaffected by changes to the system clock, so subsequent [now]
 * calls are cheap (no network) and can't be spoofed by changing the clock.
 */
actual class TrustedTimeClock internal constructor(
    private val monotonicNanos: () -> Long,
    private val systemNow: () -> Instant,
    private val fetchNtpTimeMs: suspend () -> Long,
) : Clock {
    actual constructor() : this(System::nanoTime, Clock.System::now, ::queryNtpServer)

    /**
     * Everything recorded at sync, replaced as a single object so a thread
     * calling [now] never sees a half-updated state.
     */
    private class SyncState(
        val trustedEpochMs: Long,
        val monotonicAtSyncNanos: Long,
    )

    @Volatile
    private var state: SyncState? = null

    actual override fun now(): Instant {
        val s = state ?: return systemNow()
        val elapsedMs = (monotonicNanos() - s.monotonicAtSyncNanos) / 1_000_000
        return Instant.fromEpochMilliseconds(s.trustedEpochMs + elapsedMs)
    }

    actual suspend fun sync() {
        try {
            applySync(fetchNtpTimeMs())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Keep the previous sync, if any: it is still valid.
        }
    }

    /** Records [ntpTimeMs] as the trusted time at this instant. */
    internal fun applySync(ntpTimeMs: Long) {
        state = SyncState(ntpTimeMs, monotonicNanos())
    }

    actual fun isTimeSynced(): Boolean = state != null

    actual fun timeSource(): TimeSource = if (state != null) TimeSource.TRUSTED else TimeSource.SYSTEM_FALLBACK
}

private const val NTP_HOST = "pool.ntp.org"
private val NTP_TIMEOUT: Duration = Duration.ofSeconds(5)

private suspend fun queryNtpServer(): Long =
    withContext(Dispatchers.IO) {
        val client = NTPUDPClient()
        try {
            client.setDefaultTimeout(NTP_TIMEOUT)
            client.open()
            client
                .getTime(InetAddress.getByName(NTP_HOST))
                .message.transmitTimeStamp.time
        } finally {
            client.close()
        }
    }
