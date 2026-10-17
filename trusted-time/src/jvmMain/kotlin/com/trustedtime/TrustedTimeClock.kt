package com.trustedtime

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.net.ntp.NTPUDPClient
import java.net.InetAddress
import java.time.Duration
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Fetches time from an NTP server using Apache Commons Net, or from an HTTPS
 * Date header where NTP is blocked.
 *
 * After sync, time is advanced with [System.nanoTime], a monotonic counter
 * that is unaffected by changes to the system clock, so subsequent [now]
 * calls are cheap (no network) and can't be spoofed by changing the clock.
 *
 * On macOS and Linux that counter pauses while the computer sleeps. A sleep
 * looks the same as the clock being moved forward, so either one makes the
 * clock fall back to the system time until the next [sync].
 */
actual class TrustedTimeClock internal constructor(
    private val monotonicNanos: () -> Long,
    private val systemNow: () -> Instant,
    private val fetchTimeMs: suspend () -> Long,
) : Clock {
    actual constructor() : this(
        System::nanoTime,
        Clock.System::now,
        { withFallback(::queryNtpServer, ::fetchHttpsDateTimeMs) },
    )

    private class SyncState(
        val trustedEpochMs: Long,
        val monotonicAtSyncNanos: Long,
        val systemAtSyncMs: Long,
    )

    @Volatile
    private var state: SyncState? = null

    actual override fun now(): Instant = trustedMillis()?.let(Instant::fromEpochMilliseconds) ?: systemNow()

    actual suspend fun sync(): Boolean =
        try {
            applySync(fetchTimeMs())
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }

    internal fun applySync(ntpTimeMs: Long) {
        state = SyncState(ntpTimeMs, monotonicNanos(), systemNow().toEpochMilliseconds())
    }

    actual fun isTimeSynced(): Boolean = state != null

    actual fun timeSource(): TimeSource = if (trustedMillis() != null) TimeSource.TRUSTED else TimeSource.SYSTEM_FALLBACK

    private fun trustedMillis(): Long? {
        val s = state ?: return null
        val elapsedMs = (monotonicNanos() - s.monotonicAtSyncNanos) / 1_000_000
        val systemElapsedMs = systemNow().toEpochMilliseconds() - s.systemAtSyncMs
        if (systemClockRanAhead(elapsedMs, systemElapsedMs)) return null
        return s.trustedEpochMs + elapsedMs
    }
}

private const val NTP_HOST = "pool.ntp.org"
private val NTP_TIMEOUT: Duration = Duration.ofSeconds(5)

private suspend fun queryNtpServer(): Long =
    withContext(Dispatchers.IO) {
        val server = InetAddress.getByName(NTP_HOST)
        val client = NTPUDPClient()
        try {
            client.setDefaultTimeout(NTP_TIMEOUT)
            client.open()
            val startNanos = System.nanoTime()
            val message = client.getTime(server).message
            val roundTripMs = (System.nanoTime() - startNanos) / 1_000_000
            ntpTimeAtReceiptMs(message.receiveTimeStamp.time, message.transmitTimeStamp.time, roundTripMs)
        } finally {
            client.close()
        }
    }

/**
 * The server's time at the moment its reply reached us: the time it sent the reply plus the
 * one-way trip back, estimated as half the round trip minus the time the server spent on it.
 * Only differences are used, so the local wall clock (which can be wrong or changed) isn't.
 */
internal fun ntpTimeAtReceiptMs(
    serverReceiveMs: Long,
    serverTransmitMs: Long,
    roundTripMs: Long,
): Long {
    val serverProcessingMs = serverTransmitMs - serverReceiveMs
    val oneWayMs = ((roundTripMs - serverProcessingMs) / 2).coerceAtLeast(0)
    return serverTransmitMs + oneWayMs
}
