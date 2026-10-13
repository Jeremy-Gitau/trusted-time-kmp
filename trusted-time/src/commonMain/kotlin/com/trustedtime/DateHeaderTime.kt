package com.trustedtime

/** Its HTTPS responses carry a Date header; used on iOS, and when NTP or Play services fail. */
internal const val HTTPS_TIME_URL = "https://www.apple.com"

/**
 * The server's time when its reply reached us. The Date header is cut down to the whole second, so
 * the server's real time was on average half a second later, plus the one-way trip back, estimated
 * as half the round trip.
 */
internal fun dateHeaderTimeAtReceiptMs(
    dateHeaderMs: Long,
    roundTripMs: Long,
): Long = dateHeaderMs + 500 + roundTripMs / 2

/** The server's time [epochMs] at the moment [monotonicNanos] on the local counter. */
internal class ServerTime(
    val epochMs: Long,
    val monotonicNanos: Long,
)

private const val TICK_SEARCH_STEPS = 5
private const val SECOND_NANOS = 1_000_000_000L

/**
 * The Date header only has whole seconds, but the moment it changes to the next second is exact.
 * Repeats the request, timed to halve the window that moment falls in each time, until it is
 * known to within about 30 ms. The header changes every second, so a point in the window that has
 * already passed is checked a whole number of seconds later. Uses the first reply plus half a
 * second if the replies disagree.
 */
internal suspend fun findServerTime(
    monotonicNanos: () -> Long,
    fetchDateHeaderMs: suspend () -> Long?,
    sleepNanos: suspend (Long) -> Unit,
): ServerTime? {
    var roundTripNanos = 0L

    suspend fun request(): Pair<Long, Long>? {
        val startNanos = monotonicNanos()
        val dateHeaderMs = fetchDateHeaderMs() ?: return null
        roundTripNanos = monotonicNanos() - startNanos
        return dateHeaderMs to startNanos + roundTripNanos / 2
    }

    val (firstHeaderMs, firstAtNanos) = request() ?: return null
    val estimate = ServerTime(firstHeaderMs + 500, firstAtNanos)

    // The header shows firstHeaderMs + 1 s from a moment in (before, after] on the local counter
    var before = firstAtNanos
    var after = firstAtNanos + SECOND_NANOS
    repeat(TICK_SEARCH_STEPS) {
        val middle = (before + after) / 2
        val passed = monotonicNanos() + roundTripNanos / 2 - middle
        val target = if (passed > 0) middle + (passed + SECOND_NANOS - 1) / SECOND_NANOS * SECOND_NANOS else middle
        val waitNanos = target - roundTripNanos / 2 - monotonicNanos()
        if (waitNanos > 0) sleepNanos(waitNanos)

        val (headerMs, atNanos) = request() ?: return estimate
        if ((headerMs - firstHeaderMs) % 1_000 != 0L) return estimate
        val secondsLater = (headerMs - firstHeaderMs) / 1_000
        before = maxOf(before, atNanos - secondsLater * SECOND_NANOS)
        after = minOf(after, atNanos - (secondsLater - 1) * SECOND_NANOS)
        if (before >= after) return estimate
    }
    return ServerTime(firstHeaderMs + 1_000, (before + after) / 2)
}
