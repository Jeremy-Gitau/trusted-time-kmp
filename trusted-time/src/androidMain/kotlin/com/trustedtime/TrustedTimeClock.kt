package com.trustedtime

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import com.google.android.gms.tasks.Task
import com.google.android.gms.time.TrustedTime
import com.google.android.gms.time.TrustedTimeClient
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Backed by the Google Play Services TrustedTime API. Where Play services is missing, fails, or has
 * no time signal yet, falls back to the Date header of an HTTPS response, advanced with
 * [SystemClock.elapsedRealtimeNanos], which keeps running while the device sleeps.
 */
actual class TrustedTimeClock internal constructor(
    private val createPlayServicesClient: suspend () -> TrustedTimeClient,
    private val elapsedRealtimeNanos: () -> Long,
    private val systemNow: () -> Instant,
    private val fetchHttpsTimeMs: suspend () -> Long,
) : Clock {
    actual constructor() : this(
        ::createClient,
        SystemClock::elapsedRealtimeNanos,
        Clock.System::now,
        ::fetchHttpsDateTimeMs,
    )

    private class HttpsSync(
        val trustedEpochMs: Long,
        val elapsedAtSyncNanos: Long,
    )

    @Volatile
    private var client: TrustedTimeClient? = null

    @Volatile
    private var httpsSync: HttpsSync? = null

    actual override fun now(): Instant = trustedMillis()?.let(Instant::fromEpochMilliseconds) ?: systemNow()

    actual suspend fun sync() {
        try {
            client = createPlayServicesClient()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
        }
        if (client?.computeCurrentUnixEpochMillis() != null) return
        try {
            httpsSync = HttpsSync(fetchHttpsTimeMs(), elapsedRealtimeNanos())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
        }
    }

    actual fun isTimeSynced(): Boolean = client != null || httpsSync != null

    actual fun timeSource(): TimeSource = if (trustedMillis() != null) TimeSource.TRUSTED else TimeSource.SYSTEM_FALLBACK

    // Play services returns null until it has a trusted time signal (e.g. right after boot)
    private fun trustedMillis(): Long? =
        client?.computeCurrentUnixEpochMillis()
            ?: httpsSync?.let { it.trustedEpochMs + (elapsedRealtimeNanos() - it.elapsedAtSyncNanos) / 1_000_000 }
}

private suspend fun createClient(): TrustedTimeClient {
    val ctx =
        TrustedTimeInitializer.appContext
            ?: error("Call TrustedTimeInitializer.init(context) first")
    return TrustedTime.createClient(ctx).awaitResult()
}

/** Suspends until the task completes, without blocking a thread. */
private suspend fun <T> Task<T>.awaitResult(): T =
    suspendCancellableCoroutine { cont ->
        addOnCompleteListener { task ->
            val error = task.exception
            when {
                error != null -> cont.resumeWithException(error)
                task.isCanceled -> cont.cancel()
                else -> cont.resume(task.result)
            }
        }
    }

/**
 * Call [TrustedTimeInitializer.init] once in your Application.onCreate().
 *
 * ```kotlin
 * class MyApp : Application() {
 *     override fun onCreate() {
 *         super.onCreate()
 *         TrustedTimeInitializer.init(this)
 *     }
 * }
 * ```
 */
object TrustedTimeInitializer {
    @SuppressLint("StaticFieldLeak")
    internal var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }
}
