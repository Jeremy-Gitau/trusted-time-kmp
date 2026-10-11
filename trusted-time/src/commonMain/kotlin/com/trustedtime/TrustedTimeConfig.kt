package com.trustedtime

/**
 * Configuration for [TrustedTimeClock].
 *
 * @param ntpHost      NTP server to use (default: pool.ntp.org)
 * @param timeoutMs    Timeout in milliseconds for NTP requests (default: 5000)
 * @param autoSync     If true, [TrustedTimeClock.sync] is called automatically
 *                     on first [TrustedTimeClock.now] call (default: false)
 */
data class TrustedTimeConfig(
    val ntpHost: String = "pool.ntp.org",
    val timeoutMs: Int = 5_000,
    val autoSync: Boolean = false
) {
    companion object {
        val Default = TrustedTimeConfig()
    }
}
