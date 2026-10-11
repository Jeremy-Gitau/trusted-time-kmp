package com.trustedtime

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSLocale
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSURL
import platform.Foundation.NSURLSession
import platform.Foundation.dataTaskWithRequest
import platform.Foundation.setHTTPMethod
import platform.Foundation.timeIntervalSince1970
import platform.darwin.DISPATCH_TIME_FOREVER
import platform.darwin.dispatch_semaphore_create
import platform.darwin.dispatch_semaphore_signal
import platform.darwin.dispatch_semaphore_wait

/**
 * iOS actual — fetches time from NTP using NSURLSession HTTP time headers
 * as a lightweight trusted source. For production you can swap in a
 * full NTP socket implementation or a Swift TrueTime bridge.
 */
actual class TrustedTimeClock actual constructor() {
    private var trustedInstant: Instant? = null
    private var syncedAt: Long = 0L // local monotonic ms at sync time
    private var synced = false

    actual fun now(): Instant {
        val trusted = trustedInstant
        return if (trusted != null && synced) {
            // Advance trusted snapshot by elapsed monotonic time
            val elapsedMs = currentMonotonicMs() - syncedAt
            Instant.fromEpochMilliseconds(trusted.toEpochMilliseconds() + elapsedMs)
        } else {
            Clock.System.now()
        }
    }

    actual suspend fun sync() {
        withContext(Dispatchers.Default) {
            try {
                // Use HTTP HEAD to time.cloudflare.com — returns "Date" header (UTC)
                val url = NSURL.URLWithString("https://time.cloudflare.com")!!
                val request =
                    NSMutableURLRequest.requestWithURL(url).apply {
                        setHTTPMethod("HEAD")
                        setTimeoutInterval(5.0)
                    }
                var responseDate: NSDate? = null
                val semaphore = dispatch_semaphore_create(0)

                NSURLSession.sharedSession
                    .dataTaskWithRequest(request) { _, response, _ ->
                        val httpResponse = response as? NSHTTPURLResponse
                        val dateStr = httpResponse?.allHeaderFields?.get("Date") as? String
                        if (dateStr != null) {
                            val formatter =
                                NSDateFormatter().apply {
                                    dateFormat = "EEE, dd MMM yyyy HH:mm:ss zzz"
                                    locale = NSLocale(localeIdentifier = "en_US_POSIX")
                                }
                            responseDate = formatter.dateFromString(dateStr)
                        }
                        dispatch_semaphore_signal(semaphore)
                    }.resume()

                dispatch_semaphore_wait(semaphore, DISPATCH_TIME_FOREVER)

                val date = responseDate
                if (date != null) {
                    val ms = (date.timeIntervalSince1970 * 1000).toLong()
                    trustedInstant = Instant.fromEpochMilliseconds(ms)
                    syncedAt = currentMonotonicMs()
                    synced = true
                } else {
                    synced = false
                }
            } catch (e: Exception) {
                synced = false
            }
        }
    }

    actual fun isTimeSynced(): Boolean = synced

    actual fun timeSource(): TimeSource = if (trustedInstant != null && synced) TimeSource.TRUSTED else TimeSource.SYSTEM_FALLBACK

    private fun currentMonotonicMs(): Long = (NSProcessInfo.processInfo.systemUptime * 1000).toLong()
}
