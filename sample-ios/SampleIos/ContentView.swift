import SwiftUI
import TrustedTime

struct ContentView: View {
    private let clock = TrustedTimeClock()

    @State private var status = "Not synced"

    var body: some View {
        VStack(spacing: 24) {
            Text("TrustedTime Sample")
                .font(.title)

            Text(status)

            Button("Sync") {
                Task {
                    status = "Syncing..."
                    try? await clock.sync()
                    status = clock.isTimeSynced() ? "✅ Synced" : "⚠️ Sync failed — using system clock"
                }
            }
            .buttonStyle(.borderedProminent)

            TimelineView(.periodic(from: .now, by: 0.5)) { _ in
                let trusted = clock.now()
                let system = Date()
                let offsetMs = trusted.toEpochMilliseconds() - Int64(system.timeIntervalSince1970 * 1000)

                VStack(spacing: 16) {
                    Text(clock.timeSource() == .trusted ? "🔒 Trusted" : "⚠️ System Fallback")
                    Text("Trusted: \(trusted)")
                        .font(.footnote.monospaced())
                    Text("System:  \(isoFormatter.string(from: system))")
                        .font(.footnote.monospaced())
                    Text(describeOffset(offsetMs))
                        .multilineTextAlignment(.center)
                }
            }
        }
        .padding()
        .onAppear {
            if clock.isTimeSynced() {
                status = "✅ Synced (restored)"
            }
        }
    }
}

private let isoFormatter: ISO8601DateFormatter = {
    let formatter = ISO8601DateFormatter()
    formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
    return formatter
}()

private func describeOffset(_ offsetMs: Int64) -> String {
    let size = abs(offsetMs)
    if size < 1000 { return "System clock is correct (within \(size) ms)" }
    let total = size / 1000
    let parts = [(total / 86400, "day"), (total % 86400 / 3600, "hour"), (total % 3600 / 60, "minute"), (total % 60, "second")]
        .filter { $0.0 > 0 }
        .map { $0.0 == 1 ? "1 \($0.1)" : "\($0.0) \($0.1)s" }
    return "System clock is \(parts.joined(separator: ", ")) \(offsetMs > 0 ? "behind" : "ahead")"
}
