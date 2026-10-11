package com.trustedtime

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
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
import kotlin.concurrent.Volatile
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume

/**
 * iOS actual — fetches time from NTP using NSURLSession HTTP time headers
 * as a lightweight trusted source. For production you can swap in a
 * full NTP socket implementation or a Swift TrueTime bridge.
 */
actual class TrustedTimeClock actual constructor() {
    /**
     * Everything recorded at sync, replaced as a single object so a thread
     * calling [now] never sees a half-updated state.
     */
    private class SyncState(
        val trustedEpochMs: Long,
        val monotonicAtSyncMs: Long,
    )

    @Volatile
    private var state: SyncState? = null

    actual fun now(): Instant {
        val s = state ?: return Clock.System.now()
        // Advance trusted snapshot by elapsed monotonic time
        val elapsedMs = currentMonotonicMs() - s.monotonicAtSyncMs
        return Instant.fromEpochMilliseconds(s.trustedEpochMs + elapsedMs)
    }

    actual suspend fun sync() {
        try {
            val serverMs = fetchServerDateMs() ?: return
            state = SyncState(serverMs, currentMonotonicMs())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Keep the previous sync, if any: it is still valid.
        }
    }

    actual fun isTimeSynced(): Boolean = state != null

    actual fun timeSource(): TimeSource = if (state != null) TimeSource.TRUSTED else TimeSource.SYSTEM_FALLBACK

    private fun currentMonotonicMs(): Long = (NSProcessInfo.processInfo.systemUptime * 1000).toLong()
}

/**
 * HEAD request to time.cloudflare.com; returns its "Date" header (UTC) in epoch
 * milliseconds, or null if missing. Cancelling the coroutine cancels the request.
 */
private suspend fun fetchServerDateMs(): Long? =
    suspendCancellableCoroutine { cont ->
        val url = NSURL.URLWithString("https://time.cloudflare.com")!!
        val request =
            NSMutableURLRequest.requestWithURL(url).apply {
                setHTTPMethod("HEAD")
                setTimeoutInterval(5.0)
            }
        val task =
            NSURLSession.sharedSession.dataTaskWithRequest(request) { _, response, _ ->
                val dateStr = (response as? NSHTTPURLResponse)?.allHeaderFields?.get("Date") as? String
                val formatter =
                    NSDateFormatter().apply {
                        dateFormat = "EEE, dd MMM yyyy HH:mm:ss zzz"
                        locale = NSLocale(localeIdentifier = "en_US_POSIX")
                    }
                val date = dateStr?.let { formatter.dateFromString(it) }
                cont.resume(date?.let { (it.timeIntervalSince1970 * 1000).toLong() })
            }
        cont.invokeOnCancellation { task.cancel() }
        task.resume()
    }
