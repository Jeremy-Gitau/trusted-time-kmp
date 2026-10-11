package com.trustedtime.sample

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.trustedtime.TimeSource
import com.trustedtime.TrustedTimeClock
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private val clock = TrustedTimeClock()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val statusText = findViewById<TextView>(R.id.tvStatus)
        val timeText = findViewById<TextView>(R.id.tvTime)
        val syncButton = findViewById<Button>(R.id.btnSync)
        val nowButton = findViewById<Button>(R.id.btnNow)

        syncButton.setOnClickListener {
            lifecycleScope.launch {
                statusText.text = "Syncing..."
                clock.sync()
                statusText.text =
                    if (clock.isTimeSynced()) {
                        "✅ Synced — source: ${clock.timeSource()}"
                    } else {
                        "⚠️ Sync failed — using system clock"
                    }
            }
        }

        nowButton.setOnClickListener {
            val now = clock.now()
            val source = clock.timeSource()
            val label = if (source == TimeSource.TRUSTED) "🔒 Trusted" else "⚠️ System Fallback"
            timeText.text = "$label\n$now"
        }

        // Show fallback time immediately
        timeText.text = "Press 'Get Time' to read the clock"
    }
}
