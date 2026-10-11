package com.trustedtime

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.apache.commons.net.ntp.NTPUDPClient
import java.net.InetAddress

/**
 * JVM actual — fetches time from an NTP server using Apache Commons Net.
 * Caches the offset between NTP time and system clock so subsequent
 * [now] calls are cheap (no network required).
 */
actual class TrustedTimeClock actual constructor() {

    /** Difference between trusted NTP time and System.currentTimeMillis() at sync time */
    private var offsetMs: Long = 0L
    private var synced = false

    private val ntpHost = TrustedTimeConfig.Default.ntpHost
    private val timeoutMs = TrustedTimeConfig.Default.timeoutMs

    actual fun now(): Instant {
        return if (synced) {
            Instant.fromEpochMilliseconds(System.currentTimeMillis() + offsetMs)
        } else {
            Clock.System.now()
        }
    }

    actual suspend fun sync() {
        withContext(Dispatchers.IO) {
            try {
                val client = NTPUDPClient().apply {
                    defaultTimeout = timeoutMs
                    open()
                }
                val address = InetAddress.getByName(ntpHost)
                val info = client.getTime(address)
                client.close()

                val ntpTimeMs = info.message.transmitTimeStamp.time
                offsetMs = ntpTimeMs - System.currentTimeMillis()
                synced = true
            } catch (e: Exception) {
                synced = false
            }
        }
    }

    actual fun isTimeSynced(): Boolean = synced

    actual fun timeSource(): TimeSource =
        if (synced) TimeSource.TRUSTED else TimeSource.SYSTEM_FALLBACK
}
