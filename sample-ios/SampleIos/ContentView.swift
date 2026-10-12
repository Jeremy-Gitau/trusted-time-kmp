import SwiftUI
import TrustedTime

struct ContentView: View {
    private let clock = TrustedTimeClock()

    @State private var status = "Not synced"
    @State private var time = "Press 'Get Time' to read the clock"

    var body: some View {
        VStack(spacing: 24) {
            Text("TrustedTime Sample")
                .font(.title)

            Text(status)

            Text(time)
                .font(.body.monospaced())
                .multilineTextAlignment(.center)

            Button("Sync") {
                Task {
                    status = "Syncing..."
                    try? await clock.sync()
                    status = clock.isTimeSynced()
                        ? "✅ Synced — source: \(clock.timeSource())"
                        : "⚠️ Sync failed — using system clock"
                }
            }
            .buttonStyle(.borderedProminent)

            Button("Get Time") {
                let now = clock.now()
                let label = clock.timeSource() == .trusted ? "🔒 Trusted" : "⚠️ System Fallback"
                let offsetMs = now.toEpochMilliseconds() - Int64(Date().timeIntervalSince1970 * 1000)
                time = "\(label)\n\(now)\nSystem clock is off by \(offsetMs) ms"
            }
            .buttonStyle(.bordered)
        }
        .padding()
    }
}
