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
