@file:OptIn(ExperimentalWasmJsInterop::class)

package com.trustedtime

import kotlinx.coroutines.await
import kotlinx.coroutines.delay
import kotlin.coroutines.cancellation.CancellationException
import kotlin.js.Promise
import kotlin.time.Clock
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Instant

/**
 * Reads the "Date" header of HTTPS responses as the trusted time, timing the requests to find the
 * moment it changes second (see [findServerTime]), and advances it with `performance.now()`, which
 * is unaffected by changes to the system clock.
 *
 * Browsers only let a page read the Date header of a response from its own origin, unless the
 * server sends `Access-Control-Expose-Headers: Date`. The default constructor therefore syncs
 * against the page's own origin; pass another URL to sync against a server that exposes the header.
 *
 * `performance.now()` restarts on every page load, so call [sync] after each load. Where it pauses
 * while the tab is in the background or the device sleeps, the clock falls back to the system time
 * until the next [sync].
 */
actual class TrustedTimeClock internal constructor(
    private val monotonicNanos: () -> Long,
    private val systemNow: () -> Instant,
    private val fetchServerTimeMs: suspend () -> Long?,
    private val sleepNanos: suspend (Long) -> Unit,
) : Clock {
    actual constructor() : this(pageOrigin() ?: HTTPS_TIME_URL)

    /** Syncs against the Date header of [url], which must be same-origin or expose the header. */
    constructor(url: String) : this(
        ::performanceNowNanos,
        Clock.System::now,
        { fetchDateHeaderMs(url) },
        { delay(it.nanoseconds) },
    )

    private class SyncState(
        val trustedEpochMs: Long,
        val monotonicAtSyncNanos: Long,
        val systemAtSyncMs: Long,
    )

    private var state: SyncState? = null

    actual override fun now(): Instant = trustedMillis()?.let(Instant::fromEpochMilliseconds) ?: systemNow()

    actual suspend fun sync(): Boolean =
        try {
            val serverTime = findServerTime(monotonicNanos, fetchServerTimeMs, sleepNanos)
            if (serverTime != null) {
                val sinceReplyMs = (monotonicNanos() - serverTime.monotonicNanos) / 1_000_000
                state =
                    SyncState(
                        serverTime.epochMs,
                        serverTime.monotonicNanos,
                        systemNow().toEpochMilliseconds() - sinceReplyMs,
                    )
            }
            serverTime != null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            false
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

private fun performanceNowNanos(): Long = (performanceNowMs() * 1_000_000).toLong()

@JsFun("() => performance.now()")
private external fun performanceNowMs(): Double

@JsFun(
    """() => {
        const origin = globalThis.location && globalThis.location.origin;
        return origin && origin !== 'null' ? origin : null;
    }""",
)
private external fun pageOrigin(): String?

@JsFun(
    """(url) => fetch(url, { method: 'HEAD', cache: 'no-store' })
        .then(r => { const d = r.headers.get('Date'); const ms = d ? Date.parse(d) : NaN; return isNaN(ms) ? null : ms; })""",
)
private external fun fetchDateHeaderPromise(url: String): Promise<JsNumber?>

/** HEAD request to [url]; returns its "Date" header in epoch milliseconds, or null if missing or unreadable. */
private suspend fun fetchDateHeaderMs(url: String): Long? = fetchDateHeaderPromise(url).await<JsNumber?>()?.toDouble()?.toLong()
