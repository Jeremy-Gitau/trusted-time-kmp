package com.trustedtime

import com.trustedtime.sysctl.trustedtime_boot_session_uuid
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSLocale
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequestReloadIgnoringLocalCacheData
import platform.Foundation.NSURLSession
import platform.Foundation.NSUserDefaults
import platform.Foundation.dataTaskWithRequest
import platform.Foundation.setHTTPMethod
import platform.Foundation.timeIntervalSince1970
import platform.posix.CLOCK_MONOTONIC_RAW
import platform.posix.clock_gettime_nsec_np
import kotlin.concurrent.Volatile
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.time.Clock
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Instant

/**
 * Reads the "Date" header of HTTPS responses from www.apple.com as the trusted time, timing the
 * requests to find the moment it changes second (see [findServerTime]).
 *
 * After sync, time is advanced with CLOCK_MONOTONIC_RAW, a counter that is
 * unaffected by changes to the system clock and keeps running while the
 * device sleeps.
 *
 * That counter starts at boot, so the sync is saved and restored when the
 * app restarts, as long as the device hasn't rebooted since.
 */
actual class TrustedTimeClock internal constructor(
    private val monotonicNanos: () -> Long,
    private val systemNow: () -> Instant,
    private val fetchServerTimeMs: suspend () -> Long?,
    private val bootId: () -> String?,
    private val store: SyncStore,
    private val sleepNanos: suspend (Long) -> Unit,
) : Clock {
    actual constructor() : this(
        ::continuousMonotonicNanos,
        Clock.System::now,
        ::fetchServerDateMs,
        ::bootSessionId,
        UserDefaultsSyncStore,
        { delay(it.nanoseconds) },
    )

    private class SyncState(
        val trustedEpochMs: Long,
        val monotonicAtSyncNanos: Long,
    )

    @Volatile
    private var state: SyncState? = restoreSavedSync()

    private fun restoreSavedSync(): SyncState? {
        val parts = store.load()?.split('|') ?: return null
        if (parts.size != 3 || parts[0] != bootId()) return null
        val trustedEpochMs = parts[1].toLongOrNull() ?: return null
        val monotonicAtSyncNanos = parts[2].toLongOrNull() ?: return null
        if (monotonicAtSyncNanos > monotonicNanos()) return null
        return SyncState(trustedEpochMs, monotonicAtSyncNanos)
    }

    actual override fun now(): Instant {
        val s = state ?: return systemNow()
        val elapsedMs = (monotonicNanos() - s.monotonicAtSyncNanos) / 1_000_000
        return Instant.fromEpochMilliseconds(s.trustedEpochMs + elapsedMs)
    }

    actual suspend fun sync(): Boolean {
        try {
            openConnection() ?: return false
            val serverTime = findServerTime(monotonicNanos, fetchServerTimeMs, sleepNanos) ?: return false
            val synced = SyncState(serverTime.epochMs, serverTime.monotonicNanos)
            state = synced
            bootId()?.let { store.save("$it|${synced.trustedEpochMs}|${synced.monotonicAtSyncNanos}") }
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return false
        }
    }

    private suspend fun openConnection() = fetchServerTimeMs()

    actual fun isTimeSynced(): Boolean = state != null

    actual fun timeSource(): TimeSource = if (state != null) TimeSource.TRUSTED else TimeSource.SYSTEM_FALLBACK
}

internal interface SyncStore {
    fun load(): String?

    fun save(value: String)
}

private object UserDefaultsSyncStore : SyncStore {
    private const val KEY = "com.trustedtime.lastSync"

    override fun load(): String? = NSUserDefaults.standardUserDefaults.stringForKey(KEY)

    override fun save(value: String) = NSUserDefaults.standardUserDefaults.setObject(value, forKey = KEY)
}

/** A new ID every time the device boots. */
@OptIn(ExperimentalForeignApi::class)
internal fun bootSessionId(): String? =
    memScoped {
        val buffer = allocArray<ByteVar>(64)
        if (trustedtime_boot_session_uuid(buffer, 64u) != 0) return null
        buffer.toKString()
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
        val url = NSURL.URLWithString(HTTPS_TIME_URL)!!
        val request =
            NSMutableURLRequest.requestWithURL(url).apply {
                setHTTPMethod("HEAD")
                setTimeoutInterval(5.0)
                setCachePolicy(NSURLRequestReloadIgnoringLocalCacheData)
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
