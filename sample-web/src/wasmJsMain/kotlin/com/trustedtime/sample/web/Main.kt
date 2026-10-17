package com.trustedtime.sample.web

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ComposeViewport
import com.trustedtime.TimeSource
import com.trustedtime.TrustedTimeClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    ComposeViewport("root") {
        MaterialTheme {
            Surface(Modifier.fillMaxSize()) {
                SampleScreen()
            }
        }
    }
}

@Composable
private fun SampleScreen() {
    val clock = remember { if (isDevServer()) TrustedTimeClock("/time") else TrustedTimeClock() }
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("Not synced") }
    var trusted by remember { mutableStateOf(clock.now()) }
    var system by remember { mutableStateOf(Clock.System.now()) }
    var source by remember { mutableStateOf(clock.timeSource()) }

    LaunchedEffect(Unit) {
        while (true) {
            trusted = clock.now()
            system = Clock.System.now()
            source = clock.timeSource()
            delay(500.milliseconds)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("TrustedTime Sample", style = MaterialTheme.typography.headlineSmall)
        Text(status)
        Button(onClick = {
            scope.launch {
                status = "Syncing..."
                status =
                    when {
                        clock.sync() -> "Synced"
                        clock.isTimeSynced() -> "Sync failed, using last sync"
                        else -> "Sync failed, using system clock"
                    }
            }
        }) {
            Text("Sync")
        }
        Text(if (source == TimeSource.TRUSTED) "Trusted" else "System fallback")
        Text("Trusted: $trusted", fontFamily = FontFamily.Monospace)
        Text("System:  $system", fontFamily = FontFamily.Monospace)
        Text(describeOffset(trusted, system))
    }
}

@OptIn(ExperimentalWasmJsInterop::class)
@JsFun("() => globalThis.location.hostname === 'localhost'")
private external fun isDevServer(): Boolean

private fun describeOffset(
    trusted: Instant,
    system: Instant,
): String {
    val offset = system - trusted
    val size = offset.absoluteValue
    if (size < 1.seconds) return "System clock is correct (within ${size.inWholeMilliseconds} ms)"
    val parts =
        size
            .toComponents { days, hours, minutes, seconds, _ ->
                listOf(days to "day", hours.toLong() to "hour", minutes.toLong() to "minute", seconds.toLong() to "second")
            }.filter { (amount, _) -> amount > 0 }
            .joinToString(", ") { (amount, unit) -> if (amount == 1L) "1 $unit" else "$amount ${unit}s" }
    return "System clock is $parts ${if (offset.isPositive()) "ahead" else "behind"}"
}
