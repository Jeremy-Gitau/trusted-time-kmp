package com.trustedtime

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import com.google.android.gms.tasks.Task
import com.google.android.gms.time.TrustedTime
import com.google.android.gms.time.TrustedTimeClient
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Backed by the Google Play Services TrustedTime API. Where Play services is missing, fails, or has
 * no time signal yet, falls back to the Date header of an HTTPS response, advanced with
 * [SystemClock.elapsedRealtimeNanos], which keeps running while the device sleeps.
 *
 * That counter starts at boot, so each sync is saved and restored when the app restarts, as long
 * as the device hasn't rebooted since. The clock then stays trusted offline after a restart.
 */
actual class TrustedTimeClock internal constructor(
    private val createPlayServicesClient: suspend () -> TrustedTimeClient,
    private val elapsedRealtimeNanos: () -> Long,
    private val systemNow: () -> Instant,
    private val fetchHttpsTimeMs: suspend () -> Long,
    private val bootId: () -> String?,
    private val store: SyncStore?,
) : Clock {
    actual constructor() : this(
        ::createClient,
        SystemClock::elapsedRealtimeNanos,
        Clock.System::now,
        ::fetchHttpsDateTimeMs,
        ::currentBootId,
        SharedPreferencesSyncStore.orNull(),
    )

    private class SavedSync(
        val trustedEpochMs: Long,
        val elapsedAtSyncNanos: Long,
    )

    @Volatile
    private var client: TrustedTimeClient? = null

    @Volatile
    private var savedSync: SavedSync? = restoreSavedSync()

    private fun restoreSavedSync(): SavedSync? {
        val parts = store?.load()?.split('|') ?: return null
        if (parts.size != 3 || parts[0] != bootId()) return null
        val trustedEpochMs = parts[1].toLongOrNull() ?: return null
        val elapsedAtSyncNanos = parts[2].toLongOrNull() ?: return null
        if (elapsedAtSyncNanos > elapsedRealtimeNanos()) return null
        return SavedSync(trustedEpochMs, elapsedAtSyncNanos)
    }

    actual override fun now(): Instant = trustedMillis()?.let(Instant::fromEpochMilliseconds) ?: systemNow()

    actual suspend fun sync(): Boolean {
        try {
            client = createPlayServicesClient()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
        }
        client?.computeCurrentUnixEpochMillis()?.let {
            remember(SavedSync(it, elapsedRealtimeNanos()))
            return true
        }
        return try {
            remember(SavedSync(fetchHttpsTimeMs(), elapsedRealtimeNanos()))
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

    private fun remember(sync: SavedSync) {
        savedSync = sync
        bootId()?.let { store?.save("$it|${sync.trustedEpochMs}|${sync.elapsedAtSyncNanos}") }
    }

    actual fun isTimeSynced(): Boolean = client != null || savedSync != null

    actual fun timeSource(): TimeSource = if (trustedMillis() != null) TimeSource.TRUSTED else TimeSource.SYSTEM_FALLBACK

    // Play services returns null until it has a trusted time signal (e.g. right after boot)
    private fun trustedMillis(): Long? =
        client?.computeCurrentUnixEpochMillis()
            ?: savedSync?.let { it.trustedEpochMs + (elapsedRealtimeNanos() - it.elapsedAtSyncNanos) / 1_000_000 }
}

private class SharedPreferencesSyncStore(
    context: Context,
) : SyncStore {
    private val prefs = context.getSharedPreferences("com.trustedtime", Context.MODE_PRIVATE)

    override fun load(): String? = prefs.getString(KEY, null)

    override fun save(value: String) = prefs.edit().putString(KEY, value).apply()

    companion object {
        private const val KEY = "lastSync"

        fun orNull(): SyncStore? = TrustedTimeInitializer.appContext?.let(::SharedPreferencesSyncStore)
    }
}

/** A different value every time the device boots, or null if it can't be read. */
private fun currentBootId(): String? {
    val context = TrustedTimeInitializer.appContext
    if (context != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        val count = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
        if (count >= 0) return "count:$count"
    }
    return try {
        File("/proc/sys/kernel/random/boot_id")
            .readText()
            .trim()
            .takeIf { it.isNotEmpty() }
            ?.let { "id:$it" }
    } catch (e: Exception) {
        null
    }
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
