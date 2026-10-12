package com.trustedtime

import kotlin.time.Clock
import kotlin.time.Instant

/** Where the time returned by [TrustedTimeClock.now] comes from. */
enum class TimeSource {
    /** The platform's trusted source; changing the device clock doesn't affect it. */
    TRUSTED,

    /** The device clock: not synced yet, or no trusted signal available. */
    SYSTEM_FALLBACK,
}

/**
 * A clock that can't be changed from the device settings. Call [sync] once, then use it anywhere
 * a [Clock] is expected, e.g. in place of [Clock.System].
 */
expect class TrustedTimeClock() : Clock {
    /** The trusted time, or the device clock if [timeSource] is [TimeSource.SYSTEM_FALLBACK]. */
    override fun now(): Instant

    /** Fetches the trusted time. On failure, keeps the previous sync, if any. */
    suspend fun sync()

    /** Whether a sync has ever succeeded. */
    fun isTimeSynced(): Boolean

    /** The [TimeSource] that [now] would use right now. */
    fun timeSource(): TimeSource
}
