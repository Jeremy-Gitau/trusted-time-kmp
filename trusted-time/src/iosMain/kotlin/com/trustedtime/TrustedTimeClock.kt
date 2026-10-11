package com.trustedtime

import kotlinx.cinterop.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import platform.Foundation.*
import platform.posix.*

/**
 * iOS actual — fetches time from NTP using NSURLSession HTTP time headers
 * as a lightweight trusted source. For production you can swap in a
 * full NTP socket implementation or a Swift TrueTime bridge.
 */
actual class TrustedTimeClock actual constructor() {

    private var trustedInstant: Instant? = null
    private var syncedAt: Long = 0L         // local monotonic ms at sync time
    private var synced = false
    private var source = TimeSource.SYSTEM_FALLBACK

    actual fun now(): Instant {
        val trusted = trustedInstant
        return if (trusted != null && synced) {
            source = TimeSource.TRUSTED
            // Advance trusted snapshot by elapsed monotonic time
            val elapsedMs = currentMonotonicMs() - syncedAt
            Instant.fromEpochMilliseconds(trusted.toEpochMilliseconds() + elapsedMs)
        } else {
            source = TimeSource.SYSTEM_FALLBACK
            Clock.System.now()
        }
    }

    actual suspend fun sync() {
        withContext(Dispatchers.Default) {
            try {
                // Use HTTP HEAD to time.cloudflare.com — returns "Date" header (UTC)
                val url = NSURL.URLWithString("https://time.cloudflare.com")!!
                val request = NSMutableURLRequest.requestWithURL(url).apply {
                    setHTTPMethod("HEAD")
                    setTimeoutInterval(5.0)
                }
                var responseDate: NSDate? = null
                val semaphore = dispatch_semaphore_create(0)

                NSURLSession.sharedSession.dataTaskWithRequest(request) { _, response, _ ->
                    val httpResponse = response as? NSHTTPURLResponse
                    val dateStr = httpResponse?.allHeaderFields?.get("Date") as? String
                    if (dateStr != null) {
                        val formatter = NSDateFormatter().apply {
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

    actual fun timeSource(): TimeSource = source

    private fun currentMonotonicMs(): Long =
        (NSProcessInfo.processInfo.systemUptime * 1000).toLong()
}
