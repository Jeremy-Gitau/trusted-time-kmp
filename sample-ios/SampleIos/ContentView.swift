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
                time = "\(label)\n\(now)\n\(describeOffset(offsetMs))"
            }
            .buttonStyle(.bordered)
        }
        .padding()
    }
}

/// For example "System clock is 2 days, 8 seconds behind". offsetMs is trusted minus system time.
private func describeOffset(_ offsetMs: Int64) -> String {
    let size = abs(offsetMs)
    if size < 1000 { return "System clock is correct (within \(size) ms)" }
    let total = size / 1000
    let parts = [(total / 86400, "day"), (total % 86400 / 3600, "hour"), (total % 3600 / 60, "minute"), (total % 60, "second")]
        .filter { $0.0 > 0 }
        .map { $0.0 == 1 ? "1 \($0.1)" : "\($0.0) \($0.1)s" }
    return "System clock is \(parts.joined(separator: ", ")) \(offsetMs > 0 ? "behind" : "ahead")"
}
