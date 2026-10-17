package com.trustedtime

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.net.ntp.NTPUDPClient
import java.io.File
import java.net.InetAddress
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.prefs.Preferences
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Fetches time from an NTP server using Apache Commons Net, or from an HTTPS
 * Date header where NTP is blocked.
 *
 * After sync, time is advanced with [System.nanoTime], a monotonic counter
 * that is unaffected by changes to the system clock, so subsequent [now]
 * calls are cheap (no network) and can't be spoofed by changing the clock.
 *
 * On macOS and Linux that counter pauses while the computer sleeps, so when
 * the system clock runs ahead of it the clock re-reads the time since boot
 * (see [readBootClock]), which includes sleep and ignores clock changes. The
 * sync is also saved with the time since boot, so it survives an app restart
 * until the computer reboots. Where the time since boot can't be read
 * (Windows), a sleep or a clock moved forward falls back to the system time
 * until the next [sync], and the sync isn't saved.
 */
actual class TrustedTimeClock internal constructor(
    private val monotonicNanos: () -> Long,
    private val systemNow: () -> Instant,
    private val fetchTimeMs: suspend () -> Long,
    private val bootClock: () -> BootReading? = { null },
    private val store: SyncStore? = null,
) : Clock {
    actual constructor() : this(
        System::nanoTime,
        Clock.System::now,
        { withFallback(::queryNtpServer, ::fetchHttpsDateTimeMs) },
        ::readBootClock,
        PreferencesSyncStore,
    )

    private class SyncState(
        val trustedEpochMs: Long,
        val monotonicAtSyncNanos: Long,
        val systemAtSyncMs: Long,
        val sinceBootAtSyncMs: Long?,
    )

    @Volatile
    private var state: SyncState? = restore()

    actual override fun now(): Instant = trustedMillis()?.let(Instant::fromEpochMilliseconds) ?: systemNow()

    actual suspend fun sync(): Boolean =
        try {
            val timeMs = fetchTimeMs()
            val fetchedAtNanos = monotonicNanos()
            withContext(Dispatchers.IO) {
                val reading = bootClock()
                applySync(timeMs + (monotonicNanos() - fetchedAtNanos) / 1_000_000, reading)
            }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }

    internal fun applySync(
        ntpTimeMs: Long,
        reading: BootReading? = null,
    ) {
        state = anchor(ntpTimeMs, reading?.sinceBootMs)
        reading?.let { store?.save("${it.bootId}|$ntpTimeMs|${it.sinceBootMs}") }
    }

    actual fun isTimeSynced(): Boolean = state != null

    actual fun timeSource(): TimeSource = if (trustedMillis() != null) TimeSource.TRUSTED else TimeSource.SYSTEM_FALLBACK

    private fun anchor(
        trustedEpochMs: Long,
        sinceBootMs: Long?,
    ) = SyncState(trustedEpochMs, monotonicNanos(), systemNow().toEpochMilliseconds(), sinceBootMs)

    private fun restore(): SyncState? {
        val parts = store?.load()?.split('|') ?: return null
        if (parts.size != 3) return null
        val trustedEpochMs = parts[1].toLongOrNull() ?: return null
        val savedSinceBootMs = parts[2].toLongOrNull() ?: return null
        val reading = bootClock() ?: return null
        if (reading.bootId != parts[0] || reading.sinceBootMs < savedSinceBootMs) return null
        return anchor(trustedEpochMs + reading.sinceBootMs - savedSinceBootMs, reading.sinceBootMs)
    }

    private fun reanchor(s: SyncState): SyncState? {
        val sinceBootAtSyncMs = s.sinceBootAtSyncMs ?: return null
        val reading = bootClock()
        if (reading == null || reading.sinceBootMs < sinceBootAtSyncMs) {
            state = SyncState(s.trustedEpochMs, s.monotonicAtSyncNanos, s.systemAtSyncMs, null)
            return null
        }
        return anchor(s.trustedEpochMs + reading.sinceBootMs - sinceBootAtSyncMs, reading.sinceBootMs).also { state = it }
    }

    private fun trustedMillis(): Long? {
        val s = state ?: return null
        val elapsedMs = (monotonicNanos() - s.monotonicAtSyncNanos) / 1_000_000
        val systemElapsedMs = systemNow().toEpochMilliseconds() - s.systemAtSyncMs
        if (systemClockRanAhead(elapsedMs, systemElapsedMs)) return reanchor(s)?.trustedEpochMs
        return s.trustedEpochMs + elapsedMs
    }
}

/** The computer's boot session, and the time since it booted including sleep. */
internal class BootReading(
    val bootId: String,
    val sinceBootMs: Long,
)

/**
 * On macOS the system clock minus `kern.boottime`, which macOS shifts whenever the clock is
 * changed, so the difference is unaffected by clock changes. On Linux `/proc/uptime`. Null on
 * other systems or if it can't be read.
 */
internal fun readBootClock(): BootReading? {
    val os = System.getProperty("os.name").orEmpty().lowercase()
    return try {
        when {
            os.startsWith("mac") -> readMacBootClock()
            os.startsWith("linux") -> readLinuxBootClock()
            else -> null
        }
    } catch (e: Exception) {
        null
    }
}

private val MAC_BOOT_TIME = Regex("""sec = (\d+), usec = (\d+)""")

private fun readMacBootClock(): BootReading? {
    val process = ProcessBuilder("/usr/sbin/sysctl", "-n", "kern.boottime", "kern.bootsessionuuid").start()
    val lines = process.inputStream.bufferedReader().readLines()
    if (!process.waitFor(2, TimeUnit.SECONDS) || process.exitValue() != 0) return null
    val bootTime = MAC_BOOT_TIME.find(lines.getOrNull(0).orEmpty()) ?: return null
    val bootId = lines.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val bootEpochMs = bootTime.groupValues[1].toLong() * 1_000 + bootTime.groupValues[2].toLong() / 1_000
    return BootReading(bootId, System.currentTimeMillis() - bootEpochMs)
}

private fun readLinuxBootClock(): BootReading? {
    val bootId =
        File("/proc/sys/kernel/random/boot_id")
            .readText()
            .trim()
            .takeIf { it.isNotEmpty() } ?: return null
    val uptimeSeconds =
        File("/proc/uptime")
            .readText()
            .substringBefore(' ')
            .toDouble()
    return BootReading(bootId, (uptimeSeconds * 1_000).toLong())
}

private object PreferencesSyncStore : SyncStore {
    private const val KEY = "lastSync"

    private fun node() = Preferences.userRoot().node("com/trustedtime")

    override fun load(): String? =
        try {
            node().get(KEY, null)
        } catch (e: Exception) {
            null
        }

    override fun save(value: String) {
        try {
            node().apply {
                put(KEY, value)
                flush()
            }
        } catch (e: Exception) {
        }
    }
}

private const val NTP_HOST = "pool.ntp.org"
private val NTP_TIMEOUT: Duration = Duration.ofSeconds(5)

private suspend fun queryNtpServer(): Long =
    withContext(Dispatchers.IO) {
        val server = InetAddress.getByName(NTP_HOST)
        val client = NTPUDPClient()
        try {
            client.setDefaultTimeout(NTP_TIMEOUT)
            client.open()
            val startNanos = System.nanoTime()
            val message = client.getTime(server).message
            val roundTripMs = (System.nanoTime() - startNanos) / 1_000_000
            ntpTimeAtReceiptMs(message.receiveTimeStamp.time, message.transmitTimeStamp.time, roundTripMs)
        } finally {
            client.close()
        }
    }

/**
 * The server's time at the moment its reply reached us: the time it sent the reply plus the
 * one-way trip back, estimated as half the round trip minus the time the server spent on it.
 * Only differences are used, so the local wall clock (which can be wrong or changed) isn't.
 */
internal fun ntpTimeAtReceiptMs(
    serverReceiveMs: Long,
    serverTransmitMs: Long,
    roundTripMs: Long,
): Long {
    val serverProcessingMs = serverTransmitMs - serverReceiveMs
    val oneWayMs = ((roundTripMs - serverProcessingMs) / 2).coerceAtLeast(0)
    return serverTransmitMs + oneWayMs
}
