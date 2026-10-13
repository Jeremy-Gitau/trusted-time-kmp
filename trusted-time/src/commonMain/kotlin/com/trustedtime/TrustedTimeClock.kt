package com.trustedtime

import kotlin.time.Clock
import kotlin.time.Instant

/** Where the time returned by [TrustedTimeClock.now] comes from. */
enum class TimeSource {
    TRUSTED,
    SYSTEM_FALLBACK,
}

expect class TrustedTimeClock() : Clock {
    override fun now(): Instant

    suspend fun sync(): Boolean

    fun isTimeSynced(): Boolean

    fun timeSource(): TimeSource
}
