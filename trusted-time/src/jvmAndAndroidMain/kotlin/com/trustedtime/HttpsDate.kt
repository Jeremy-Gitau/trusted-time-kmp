package com.trustedtime

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.cancellation.CancellationException

/** HEAD request to [HTTPS_TIME_URL]; returns the server's time when the reply arrived. */
internal suspend fun fetchHttpsDateTimeMs(): Long =
    withContext(Dispatchers.IO) {
        val connection = URL(HTTPS_TIME_URL).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "HEAD"
            connection.useCaches = false
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            connection.connect()
            val startNanos = System.nanoTime()
            connection.responseCode
            val roundTripMs = (System.nanoTime() - startNanos) / 1_000_000
            val dateHeaderMs = connection.getHeaderFieldDate("Date", 0)
            check(dateHeaderMs > 0) { "No Date header" }
            dateHeaderTimeAtReceiptMs(dateHeaderMs, roundTripMs)
        } finally {
            connection.disconnect()
        }
    }

/** Uses [primary], or [fallback] if it fails. Cancellation is never treated as a failure. */
internal suspend fun <T> withFallback(
    primary: suspend () -> T,
    fallback: suspend () -> T,
): T =
    try {
        primary()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        fallback()
    }
