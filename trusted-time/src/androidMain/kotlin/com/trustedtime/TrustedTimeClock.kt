package com.trustedtime

import android.annotation.SuppressLint
import android.content.Context
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.time.TrustedTime
import com.google.android.gms.time.TrustedTimeClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/**
 * Android actual — backed by Google Play Services TrustedTime API.
 */
actual class TrustedTimeClock actual constructor() {
    private var client: TrustedTimeClient? = null
    private var synced = false

    actual fun now(): Instant = trustedMillis()?.let(Instant::fromEpochMilliseconds) ?: Clock.System.now()

    actual suspend fun sync() {
        withContext(Dispatchers.IO) {
            try {
                val ctx =
                    TrustedTimeInitializer.appContext
                        ?: error("Call TrustedTimeInitializer.init(context) first")
                val task = TrustedTime.createClient(ctx)
                client = Tasks.await(task)
                synced = true
            } catch (e: Exception) {
                synced = false
            }
        }
    }

    actual fun isTimeSynced(): Boolean = synced

    actual fun timeSource(): TimeSource = if (trustedMillis() != null) TimeSource.TRUSTED else TimeSource.SYSTEM_FALLBACK

    // Null until Play Services has a trusted time signal (e.g. right after boot)
    private fun trustedMillis(): Long? = if (synced) client?.computeCurrentUnixEpochMillis() else null
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
    @SuppressLint("StaticFieldLeak") // application context is safe
    internal var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }
}
