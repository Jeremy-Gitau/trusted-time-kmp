package com.trustedtime.sample

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.trustedtime.TimeSource
import com.trustedtime.TrustedTimeClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class MainActivity : AppCompatActivity() {
    private val clock = TrustedTimeClock()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val statusText = findViewById<TextView>(R.id.tvStatus)
        val sourceText = findViewById<TextView>(R.id.tvSource)
        val trustedText = findViewById<TextView>(R.id.tvTrusted)
        val systemText = findViewById<TextView>(R.id.tvSystem)
        val offsetText = findViewById<TextView>(R.id.tvOffset)

        findViewById<Button>(R.id.btnSync).setOnClickListener {
            lifecycleScope.launch {
                statusText.text = "Syncing..."
                clock.sync()
                statusText.text = if (clock.isTimeSynced()) "✅ Synced" else "⚠️ Sync failed — using system clock"
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    val trusted = clock.now()
                    val system = Clock.System.now()
                    sourceText.text = if (clock.timeSource() == TimeSource.TRUSTED) "🔒 Trusted" else "⚠️ System Fallback"
                    trustedText.text = "Trusted: $trusted"
                    systemText.text = "System:  $system"
                    offsetText.text = describeOffset(trusted, system)
                    delay(500.milliseconds)
                }
            }
        }
    }
}

/** For example "System clock is 2 days, 8 seconds behind". */
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
