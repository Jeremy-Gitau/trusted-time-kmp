package com.trustedtime

import android.annotation.SuppressLint
import android.content.Context
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.timetrust.TrustedTime
import com.google.android.gms.timetrust.TrustedTimeClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/**
 * Android actual — backed by Google Play Services TrustedTime API.
 *
 * IMPORTANT: Call [TrustedTimeInitializer.init] from your Application.onCreate()
 * before constructing this class.
 */
actual class TrustedTimeClock actual constructor() {

    private var client: TrustedTimeClient? = null
    private var synced = false
    private var source = TimeSource.SYSTEM_FALLBACK

    actual fun now(): Instant {
        val c = client
        return if (c != null && synced) {
            source = TimeSource.TRUSTED
            Instant.fromEpochMilliseconds(c.computeCurrentUnixEpochMillis())
        } else {
            source = TimeSource.SYSTEM_FALLBACK
            Clock.System.now()
        }
    }

    actual suspend fun sync() {
        withContext(Dispatchers.IO) {
            try {
                val ctx = TrustedTimeInitializer.appContext
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

    actual fun timeSource(): TimeSource = source
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
