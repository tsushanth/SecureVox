import SwiftUI

/// View showing transcription progress with details
struct TranscriptionProgressView: View {

    // MARK: - Properties

    let progress: Double
    let estimatedTimeRemaining: TimeInterval?

    // MARK: - Body

    var body: some View {
        VStack(spacing: 20) {
            // Circular progress
            ZStack {
                Circle()
                    .stroke(Color.secondary.opacity(0.2), lineWidth: 8)

                Circle()
                    .trim(from: 0, to: progress)
                    .stroke(Color.accentColor, style: StrokeStyle(lineWidth: 8, lineCap: .round))
                    .rotationEffect(.degrees(-90))
                    .animation(.easeInOut(duration: 0.3), value: progress)

                VStack(spacing: 4) {
                    Text("\(Int(progress * 100))%")
                        .font(.system(size: 32, weight: .semibold, design: .rounded))
                        .monospacedDigit()

                    Text("Complete")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
            .frame(width: 120, height: 120)

            // Status text
            VStack(spacing: 8) {
                Text("Transcribing...")
                    .font(.headline)

                if let remaining = estimatedTimeRemaining {
                    Text("About \(formatTimeRemaining(remaining)) remaining")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }

                Text("Processing entirely on your device")
                    .font(.caption)
                    .foregroundStyle(.tertiary)
            }
        }
        .padding()
    }

    // MARK: - Helpers

    private func formatTimeRemaining(_ time: TimeInterval) -> String {
        if time < 60 {
            return "less than a minute"
        } else if time < 3600 {
            let minutes = Int(time / 60)
            return "\(minutes) minute\(minutes == 1 ? "" : "s")"
        } else {
            let hours = Int(time / 3600)
            let minutes = Int((time.truncatingRemainder(dividingBy: 3600)) / 60)
            if minutes > 0 {
                return "\(hours)h \(minutes)m"
            } else {
                return "\(hours) hour\(hours == 1 ? "" : "s")"
            }
        }
    }
}

// MARK: - Preview

#Preview {
    VStack(spacing: 40) {
        TranscriptionProgressView(progress: 0.25, estimatedTimeRemaining: 180)
        TranscriptionProgressView(progress: 0.75, estimatedTimeRemaining: 45)
    }
}
