package com.trustedtime.sample.jvm

import com.trustedtime.TrustedTimeClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds

/**
 * Syncs with NTP, then prints trusted time next to the system clock once a
 * second for a minute. Change your computer's clock while it runs: the system time jumps,
 * the trusted time does not.
 */
fun main() =
    runBlocking {
        val clock = TrustedTimeClock()

        println("Before sync: ${clock.now()}  source=${clock.timeSource()}")
        println("Syncing with NTP...")
        clock.sync()

        if (!clock.isTimeSynced()) {
            println("⚠️ Sync failed — check your network. Using the system clock.")
            return@runBlocking
        }
        println("✅ Synced — source=${clock.timeSource()}\n")

        repeat(60) {
            val trusted = clock.now()
            val system = Clock.System.now()
            val diffMs = system.toEpochMilliseconds() - trusted.toEpochMilliseconds()
            println("trusted=$trusted  system=$system  system-trusted=${diffMs}ms")
            delay(1_000.milliseconds)
        }
    }
