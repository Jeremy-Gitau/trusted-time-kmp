package com.trustedtime

import kotlinx.coroutines.await
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.coroutines.cancellation.CancellationException
import kotlin.js.Date

/**
 * JS actual — uses a HEAD request to time.cloudflare.com to read the
 * server "Date" header as trusted time source. Works in both browser and Node.js.
 */
actual class TrustedTimeClock actual constructor() {
    private var trustedMs: Double? = null
    private var syncedPerformanceMs: Double = 0.0
    private var synced = false

    actual fun now(): Instant {
        val trusted = trustedMs
        return if (trusted != null && synced) {
            val elapsedMs = currentPerformanceMs() - syncedPerformanceMs
            Instant.fromEpochMilliseconds((trusted + elapsedMs).toLong())
        } else {
            Clock.System.now()
        }
    }

    actual suspend fun sync() {
        try {
            @Suppress("UNCHECKED_CAST_TO_EXTERNAL_INTERFACE")
            val response =
                kotlinx.browser.window
                    .fetch("https://time.cloudflare.com", js("({ method: 'HEAD' })"))
                    .await()

            val dateHeader = response.headers.get("Date")
            if (dateHeader != null) {
                trustedMs = Date(dateHeader).getTime()
                syncedPerformanceMs = currentPerformanceMs()
                synced = true
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Keep the previous sync, if any: it is still valid.
        }
    }

    actual fun isTimeSynced(): Boolean = synced

    actual fun timeSource(): TimeSource = if (trustedMs != null && synced) TimeSource.TRUSTED else TimeSource.SYSTEM_FALLBACK

    private fun currentPerformanceMs(): Double =
        kotlinx.browser.window.performance
            .now()
}
