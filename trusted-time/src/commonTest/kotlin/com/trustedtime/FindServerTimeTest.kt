package com.trustedtime

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FindServerTimeTest {
    private var monotonicNanos = 0L
    private var requests = 0

    /** A server whose time is [serverOffsetMs] ahead of the counter; it writes the time at the middle of the round trip. */
    private fun server(
        serverOffsetMs: Long,
        roundTripMs: Long,
    ): suspend () -> Long? =
        {
            requests++
            monotonicNanos += roundTripMs * 500_000
            val headerMs = (serverOffsetMs + monotonicNanos / 1_000_000) / 1_000 * 1_000
            monotonicNanos += roundTripMs * 500_000
            headerMs
        }

    private fun find(fetch: suspend () -> Long?): ServerTime? =
        runWithoutSuspending {
            findServerTime({ monotonicNanos }, fetch, sleepNanos = { monotonicNanos += it })
        }

    private fun errorMs(
        result: ServerTime,
        serverOffsetMs: Long,
    ): Long = result.epochMs - (serverOffsetMs + result.monotonicNanos / 1_000_000)

    @Test
    fun findsTheServerTimeToWithinTensOfMilliseconds() {
        for (phaseMs in 0L until 1_000L step 37) {
            for (roundTripMs in listOf(10L, 50L, 150L)) {
                monotonicNanos = 0
                val serverOffsetMs = 1_700_000_000_000L + phaseMs
                val result = find(server(serverOffsetMs, roundTripMs))!!
                val error = errorMs(result, serverOffsetMs)
                assertTrue(abs(error) <= 40, "phase $phaseMs ms, round trip $roundTripMs ms: off by $error ms")
            }
        }
    }

    @Test
    fun takesAFewSecondsAndSixRequests() {
        find(server(1_700_000_000_000L, roundTripMs = 50))
        assertEquals(6, requests)
        assertTrue(monotonicNanos < 4_000_000_000L, "took ${monotonicNanos / 1_000_000} ms")
    }

    @Test
    fun dateHeaderTimeAddsHalfASecondAndHalfTheRoundTrip() {
        assertEquals(1_650L, dateHeaderTimeAtReceiptMs(dateHeaderMs = 1_000, roundTripMs = 300))
    }

    @Test
    fun noReplyGivesNoTime() {
        assertNull(find { null })
    }

    @Test
    fun usesTheFirstReplyIfALaterOneFails() {
        val headers = mutableListOf(5_000L, null)
        val result = find { headers.removeAt(0) }!!
        assertEquals(5_500, result.epochMs)
    }

    @Test
    fun usesTheFirstReplyIfTheRepliesDisagree() {
        val headers = mutableListOf(5_000L, 9_000L)
        val result = find { headers.removeAt(0) }!!
        assertEquals(5_500, result.epochMs)
    }
}

private fun <T> runWithoutSuspending(block: suspend () -> T): T {
    var result: Result<T>? = null
    block.startCoroutine(Continuation(EmptyCoroutineContext) { result = it })
    return result!!.getOrThrow()
}
