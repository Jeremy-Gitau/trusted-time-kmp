package com.trustedtime

import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The source of the time returned by [TrustedTimeClock].
 *
 * [TRUSTED]  — time was fetched from a remote NTP / trusted service and is tamper-proof.
 * [SYSTEM_FALLBACK] — remote sync failed; device system clock was used instead.
 */
enum class TimeSource {
    TRUSTED,
    SYSTEM_FALLBACK,
}

/**
 * Cross-platform tamper-proof clock. It is a [Clock], so it can be used anywhere a [Clock] is
 * expected, e.g. in place of [Clock.System].
 *
 * Usage:
 * ```
 * val clock = TrustedTimeClock()
 * clock.sync()                   // call once, e.g. at app start
 *
 * val now: Instant = clock.now()
 * val source: TimeSource = clock.timeSource()
 * val synced: Boolean = clock.isTimeSynced()
 * ```
 */
expect class TrustedTimeClock() : Clock {
    /**
     * Returns the current [Instant].
     * Always returns a value — falls back to system clock if not yet synced.
     */
    override fun now(): Instant

    /**
     * Suspends until a trusted time sync is complete.
     * Call this once at app/SDK initialization.
     */
    suspend fun sync()

    /**
     * Returns true if a successful trusted sync has been performed.
     */
    fun isTimeSynced(): Boolean

    /**
     * Returns the [TimeSource] that [now] would use if called right now.
     * Safe to call before [now]; does not depend on earlier calls.
     */
    fun timeSource(): TimeSource
}
