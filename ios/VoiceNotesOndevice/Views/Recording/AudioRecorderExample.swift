import SwiftUI

/// Minimal SwiftUI example demonstrating AudioRecorderService usage
struct AudioRecorderExample: View {

    @StateObject private var recorder = AudioRecorderService()
    @State private var lastRecordingURL: URL?
    @State private var showingPermissionAlert = false

    var body: some View {
        VStack(spacing: 32) {

            // Duration display
            Text(recorder.formattedDuration)
                .font(.system(size: 64, weight: .thin, design: .monospaced))
                .foregroundStyle(recorder.isRecording ? .primary : .secondary)

            // Audio level indicator
            AudioLevelView(level: recorder.audioLevel)
                .frame(height: 60)
                .padding(.horizontal, 40)

            // Start/Stop button
            Button {
                toggleRecording()
            } label: {
                ZStack {
                    Circle()
                        .fill(recorder.isRecording ? Color.red : Color.blue)
                        .frame(width: 80, height: 80)

                    if recorder.isRecording {
                        RoundedRectangle(cornerRadius: 4)
                            .fill(Color.white)
                            .frame(width: 28, height: 28)
                    } else {
                        Circle()
                            .fill(Color.white)
                            .frame(width: 32, height: 32)
                    }
                }
            }
            .buttonStyle(.plain)
            .shadow(color: .black.opacity(0.2), radius: 8, y: 4)

            // Status text
            Text(statusText)
                .font(.caption)
                .foregroundStyle(.secondary)

            // Last recording info
            if let url = lastRecordingURL {
                VStack(spacing: 4) {
                    Text("Last Recording:")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    Text(url.lastPathComponent)
                        .font(.caption2)
                        .foregroundStyle(.tertiary)
                }
                .padding(.top, 20)
            }

            // Error message
            if let error = recorder.errorMessage {
                Text(error)
                    .font(.caption)
                    .foregroundStyle(.red)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal)
            }
        }
        .padding()
        .alert("Microphone Access Required", isPresented: $showingPermissionAlert) {
            Button("Open Settings") {
                if let url = URL(string: UIApplication.openSettingsURLString) {
                    UIApplication.shared.open(url)
                }
            }
            Button("Cancel", role: .cancel) { }
        } message: {
            Text("Please enable microphone access in Settings to record audio.")
        }
        .task {
            // Request permission on appear if not granted
            if !recorder.hasMicrophonePermission {
                _ = await recorder.requestMicrophonePermission()
            }
        }
    }

    private var statusText: String {
        if recorder.isRecording {
            return "Recording at 16kHz mono (Whisper-ready)"
        } else if lastRecordingURL != nil {
            return "Tap to start a new recording"
        } else {
            return "Tap to start recording"
        }
    }

    private func toggleRecording() {
        if recorder.isRecording {
            // Stop recording
            do {
                let result = try recorder.stopRecording()
                lastRecordingURL = result.fileURL
                print("Recording saved: \(result.fileURL)")
                print("Duration: \(result.duration) seconds")
                print("Size: \(ByteCountFormatter.string(fromByteCount: result.fileSize, countStyle: .file))")
            } catch {
                print("Failed to stop recording: \(error)")
            }
        } else {
            // Start recording
            do {
                let url = try recorder.startRecording()
                print("Recording to: \(url)")
            } catch AudioRecorderService.RecorderError.microphonePermissionDenied {
                showingPermissionAlert = true
            } catch {
                print("Failed to start recording: \(error)")
            }
        }
    }
}

// MARK: - Audio Level View

/// Simple audio level visualization
struct AudioLevelView: View {
    let level: Float

    private let barCount = 30

    var body: some View {
        GeometryReader { geometry in
            HStack(spacing: 2) {
                ForEach(0..<barCount, id: \.self) { index in
                    RoundedRectangle(cornerRadius: 1)
                        .fill(barColor(for: index))
                        .frame(width: (geometry.size.width - CGFloat(barCount - 1) * 2) / CGFloat(barCount))
                        .scaleEffect(y: barScale(for: index), anchor: .bottom)
                }
            }
        }
    }

    private func barScale(for index: Int) -> CGFloat {
        let normalizedIndex = Float(index) / Float(barCount - 1)

        // Create a smooth wave pattern
        let wave = sin(normalizedIndex * .pi)
        let levelScale = level * wave

        // Minimum height of 0.1, max of 1.0
        return CGFloat(max(0.1, min(1.0, levelScale + 0.1)))
    }

    private func barColor(for index: Int) -> Color {
        let normalizedIndex = Float(index) / Float(barCount - 1)
        let threshold = level

        if normalizedIndex <= threshold {
            // Active bar - gradient from blue to red
            let intensity = normalizedIndex / max(threshold, 0.01)
            if intensity > 0.8 {
                return .red
            } else if intensity > 0.6 {
                return .orange
            } else {
                return .blue
            }
        } else {
            // Inactive bar
            return .gray.opacity(0.3)
        }
    }
}

// MARK: - Preview

#Preview {
    AudioRecorderExample()
}
