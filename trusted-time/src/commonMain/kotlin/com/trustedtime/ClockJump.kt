package com.trustedtime

/**
 * Whether the system clock moved forward by clearly more than the monotonic counter: the computer
 * slept, the counter paused, or the clock was moved forward. Allows 2 s plus 0.1% for the system's
 * own clock corrections.
 */
internal fun systemClockRanAhead(
    elapsedMs: Long,
    systemElapsedMs: Long,
): Boolean = systemElapsedMs - elapsedMs > 2_000 + elapsedMs / 1_000
