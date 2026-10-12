package com.trustedtime

import android.annotation.SuppressLint
import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.android.gms.time.TrustedTime
import com.google.android.gms.time.TrustedTimeClient
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Clock
import kotlin.time.Instant

/** Backed by the Google Play Services TrustedTime API. */
actual class TrustedTimeClock actual constructor() : Clock {
    /** Non-null once synced; a single reference, so readers never see a half-updated state. */
    @Volatile
    private var client: TrustedTimeClient? = null

    actual override fun now(): Instant = trustedMillis()?.let(Instant::fromEpochMilliseconds) ?: Clock.System.now()

    actual suspend fun sync() {
        try {
            val ctx =
                TrustedTimeInitializer.appContext
                    ?: error("Call TrustedTimeInitializer.init(context) first")
            client = TrustedTime.createClient(ctx).awaitResult()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
        }
    }

    actual fun isTimeSynced(): Boolean = client != null

    actual fun timeSource(): TimeSource = if (trustedMillis() != null) TimeSource.TRUSTED else TimeSource.SYSTEM_FALLBACK

    // Null until Play Services has a trusted time signal (e.g. right after boot)
    private fun trustedMillis(): Long? = client?.computeCurrentUnixEpochMillis()
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
