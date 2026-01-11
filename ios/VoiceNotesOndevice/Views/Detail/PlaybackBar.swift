import SwiftUI

/// Audio playback control bar with scrubber
struct PlaybackBar: View {

    // MARK: - Properties

    let isPlaying: Bool
    let currentTime: TimeInterval
    let duration: TimeInterval
    let onPlayPause: () -> Void
    let onSeek: (TimeInterval) -> Void
    let onSkipBackward: () -> Void
    let onSkipForward: () -> Void
    var playbackSpeed: Float = 1.0
    var onSpeedChange: ((Float) -> Void)?

    // MARK: - State

    @State private var isDragging = false
    @State private var dragTime: TimeInterval = 0

    // MARK: - Speed Options

    private let speedOptions: [Float] = [0.5, 0.75, 1.0, 1.25, 1.5, 2.0]

    // MARK: - Computed Properties

    private var displayTime: TimeInterval {
        isDragging ? dragTime : currentTime
    }

    private var progress: Double {
        guard duration > 0 else { return 0 }
        return displayTime / duration
    }

    // MARK: - Body

    var body: some View {
        VStack(spacing: 8) {
            // Progress slider
            progressSlider

            // Controls
            HStack(spacing: 24) {
                // Speed button
                if onSpeedChange != nil {
                    Menu {
                        ForEach(speedOptions, id: \.self) { speed in
                            Button {
                                onSpeedChange?(speed)
                            } label: {
                                HStack {
                                    Text(speedLabel(speed))
                                    if speed == playbackSpeed {
                                        Image(systemName: "checkmark")
                                    }
                                }
                            }
                        }
                    } label: {
                        Text(speedLabel(playbackSpeed))
                            .font(.subheadline)
                            .fontWeight(.medium)
                            .padding(.horizontal, 8)
                            .padding(.vertical, 4)
                            .background(Color.secondary.opacity(0.15))
                            .clipShape(Capsule())
                    }
                    .frame(width: 50)
                } else {
                    Spacer().frame(width: 50)
                }

                // Skip backward
                Button {
                    onSkipBackward()
                } label: {
                    Image(systemName: "gobackward.15")
                        .font(.title2)
                }

                // Play/Pause
                Button {
                    onPlayPause()
                } label: {
                    Image(systemName: isPlaying ? "pause.fill" : "play.fill")
                        .font(.title)
                        .frame(width: 44, height: 44)
                }

                // Skip forward
                Button {
                    onSkipForward()
                } label: {
                    Image(systemName: "goforward.15")
                        .font(.title2)
                }

                // Spacer to balance layout
                Spacer().frame(width: 50)
            }
            .foregroundStyle(.primary)
        }
        .padding(.horizontal)
        .padding(.vertical, 12)
        .background(Color(.systemBackground))
    }

    // MARK: - Subviews

    private var progressSlider: some View {
        VStack(spacing: 4) {
            GeometryReader { geometry in
                ZStack(alignment: .leading) {
                    // Background track
                    Capsule()
                        .fill(Color.secondary.opacity(0.2))
                        .frame(height: 4)

                    // Progress track
                    Capsule()
                        .fill(Color.accentColor)
                        .frame(width: geometry.size.width * progress, height: 4)

                    // Thumb
                    Circle()
                        .fill(Color.accentColor)
                        .frame(width: isDragging ? 16 : 12, height: isDragging ? 16 : 12)
                        .offset(x: (geometry.size.width * progress) - (isDragging ? 8 : 6))
                        .animation(.easeInOut(duration: 0.1), value: isDragging)
                }
                .frame(height: 20)
                .contentShape(Rectangle())
                .gesture(
                    DragGesture(minimumDistance: 0)
                        .onChanged { value in
                            isDragging = true
                            let fraction = max(0, min(1, value.location.x / geometry.size.width))
                            dragTime = duration * fraction
                        }
                        .onEnded { value in
                            let fraction = max(0, min(1, value.location.x / geometry.size.width))
                            onSeek(duration * fraction)
                            isDragging = false
                        }
                )
            }
            .frame(height: 20)

            // Time labels
            HStack {
                Text(formatTime(displayTime))
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .monospacedDigit()

                Spacer()

                Text(formatTime(duration))
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .monospacedDigit()
            }
        }
    }

    // MARK: - Helpers

    private func formatTime(_ time: TimeInterval) -> String {
        let hours = Int(time) / 3600
        let minutes = (Int(time) % 3600) / 60
        let seconds = Int(time) % 60

        if hours > 0 {
            return String(format: "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            return String(format: "%d:%02d", minutes, seconds)
        }
    }

    private func speedLabel(_ speed: Float) -> String {
        if speed == 1.0 {
            return "1x"
        } else if speed == floor(speed) {
            return "\(Int(speed))x"
        } else {
            return String(format: "%.2gx", speed)
        }
    }
}

// MARK: - Preview

#Preview {
    VStack {
        Spacer()

        PlaybackBar(
            isPlaying: false,
            currentTime: 45,
            duration: 180,
            onPlayPause: {},
            onSeek: { _ in },
            onSkipBackward: {},
            onSkipForward: {}
        )

        PlaybackBar(
            isPlaying: true,
            currentTime: 3700,
            duration: 7200,
            onPlayPause: {},
            onSeek: { _ in },
            onSkipBackward: {},
            onSkipForward: {}
        )
    }
}
