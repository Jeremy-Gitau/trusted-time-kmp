package com.trustedtime

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.convert
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSLocale
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSURL
import platform.Foundation.NSURLSession
import platform.Foundation.dataTaskWithRequest
import platform.Foundation.setHTTPMethod
import platform.Foundation.timeIntervalSince1970
import platform.posix.CLOCK_MONOTONIC_RAW
import platform.posix.clock_gettime_nsec_np
import kotlin.concurrent.Volatile
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Reads the "Date" header of an HTTPS response from
 * www.apple.com as the trusted time.
 *
 * After sync, time is advanced with CLOCK_MONOTONIC_RAW, a counter that is
 * unaffected by changes to the system clock and keeps running while the
 * device sleeps.
 */
actual class TrustedTimeClock internal constructor(
    private val monotonicNanos: () -> Long,
    private val systemNow: () -> Instant,
    private val fetchServerTimeMs: suspend () -> Long?,
) : Clock {
    actual constructor() : this(::continuousMonotonicNanos, Clock.System::now, ::fetchServerDateMs)

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
            val serverMs = fetchServerTimeMs() ?: return
            state = SyncState(serverMs, monotonicNanos())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
        }
    }

    actual fun isTimeSynced(): Boolean = state != null

    actual fun timeSource(): TimeSource = if (state != null) TimeSource.TRUSTED else TimeSource.SYSTEM_FALLBACK
}

/** Monotonic nanoseconds that, unlike systemUptime, include time the device spent asleep. */
@OptIn(ExperimentalForeignApi::class)
private fun continuousMonotonicNanos(): Long = clock_gettime_nsec_np(CLOCK_MONOTONIC_RAW.convert()).toLong()

/**
 * HEAD request to www.apple.com; returns its "Date" header (UTC) in epoch
 * milliseconds, or null if missing. Cancelling the coroutine cancels the request.
 */
private suspend fun fetchServerDateMs(): Long? =
    suspendCancellableCoroutine { cont ->
        val url = NSURL.URLWithString("https://www.apple.com")!!
        val request =
            NSMutableURLRequest.requestWithURL(url).apply {
                setHTTPMethod("HEAD")
                setTimeoutInterval(5.0)
            }
        val task =
            NSURLSession.sharedSession.dataTaskWithRequest(request) { _, response, _ ->
                val dateStr = (response as? NSHTTPURLResponse)?.valueForHTTPHeaderField("Date")
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
