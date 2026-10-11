package com.trustedtime

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.apache.commons.net.ntp.NTPUDPClient
import java.net.InetAddress

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
) {
    actual constructor() : this(System::nanoTime, Clock.System::now)

    /** Trusted NTP time at the moment of sync */
    private var trustedEpochMs: Long = 0L

    /** [monotonicNanos] reading taken at the same moment as [trustedEpochMs] */
    private var monotonicAtSyncNanos: Long = 0L
    private var synced = false

    private val ntpHost = TrustedTimeConfig.Default.ntpHost
    private val timeoutMs = TrustedTimeConfig.Default.timeoutMs

    actual fun now(): Instant =
        if (synced) {
            val elapsedMs = (monotonicNanos() - monotonicAtSyncNanos) / 1_000_000
            Instant.fromEpochMilliseconds(trustedEpochMs + elapsedMs)
        } else {
            systemNow()
        }

    actual suspend fun sync() {
        withContext(Dispatchers.IO) {
            try {
                val client =
                    NTPUDPClient().apply {
                        defaultTimeout = timeoutMs
                        open()
                    }
                val address = InetAddress.getByName(ntpHost)
                val info = client.getTime(address)
                client.close()

                applySync(info.message.transmitTimeStamp.time)
            } catch (e: Exception) {
                synced = false
            }
        }
    }

    /** Records [ntpTimeMs] as the trusted time at this instant. */
    internal fun applySync(ntpTimeMs: Long) {
        trustedEpochMs = ntpTimeMs
        monotonicAtSyncNanos = monotonicNanos()
        synced = true
    }

    actual fun isTimeSynced(): Boolean = synced

    actual fun timeSource(): TimeSource = if (synced) TimeSource.TRUSTED else TimeSource.SYSTEM_FALLBACK
}
