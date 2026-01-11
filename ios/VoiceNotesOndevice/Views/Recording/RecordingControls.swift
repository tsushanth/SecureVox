import SwiftUI

/// Reusable recording control buttons
struct RecordingControls: View {

    // MARK: - Properties

    let isRecording: Bool
    let isPaused: Bool
    let canRecord: Bool
    let onRecord: () -> Void
    let onPause: () -> Void
    let onResume: () -> Void
    let onStop: () -> Void
    let onCancel: () -> Void

    // MARK: - Body

    var body: some View {
        HStack(spacing: 40) {
            // Cancel button
            if isRecording || isPaused {
                ControlButton(
                    icon: "xmark",
                    color: .red,
                    size: 50,
                    action: onCancel
                )
            } else {
                Color.clear.frame(width: 50, height: 50)
            }

            // Main record/pause button
            mainButton

            // Stop button
            if isRecording || isPaused {
                ControlButton(
                    icon: "stop.fill",
                    color: .blue,
                    size: 50,
                    filled: true,
                    action: onStop
                )
            } else {
                Color.clear.frame(width: 50, height: 50)
            }
        }
    }

    // MARK: - Subviews

    @ViewBuilder
    private var mainButton: some View {
        Button {
            if isRecording {
                onPause()
            } else if isPaused {
                onResume()
            } else {
                onRecord()
            }
        } label: {
            ZStack {
                Circle()
                    .fill(mainButtonColor)
                    .frame(width: 72, height: 72)

                Image(systemName: mainButtonIcon)
                    .font(.system(size: 28, weight: .semibold))
                    .foregroundStyle(.white)
            }
            .shadow(color: mainButtonColor.opacity(0.4), radius: 8, y: 4)
        }
        .disabled(!canRecord && !isRecording && !isPaused)
    }

    private var mainButtonColor: Color {
        if isRecording {
            return .orange
        } else {
            return .red
        }
    }

    private var mainButtonIcon: String {
        if isRecording {
            return "pause.fill"
        } else {
            return "mic.fill"
        }
    }
}

// MARK: - Control Button

private struct ControlButton: View {

    let icon: String
    let color: Color
    var size: CGFloat = 50
    var filled: Bool = false
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: icon)
                .font(.system(size: size * 0.4, weight: .semibold))
                .foregroundStyle(filled ? .white : color)
                .frame(width: size, height: size)
                .background(filled ? color : color.opacity(0.15))
                .clipShape(Circle())
        }
    }
}

// MARK: - Preview

#Preview {
    VStack(spacing: 40) {
        RecordingControls(
            isRecording: false,
            isPaused: false,
            canRecord: true,
            onRecord: {},
            onPause: {},
            onResume: {},
            onStop: {},
            onCancel: {}
        )

        RecordingControls(
            isRecording: true,
            isPaused: false,
            canRecord: true,
            onRecord: {},
            onPause: {},
            onResume: {},
            onStop: {},
            onCancel: {}
        )

        RecordingControls(
            isRecording: false,
            isPaused: true,
            canRecord: true,
            onRecord: {},
            onPause: {},
            onResume: {},
            onStop: {},
            onCancel: {}
        )
    }
    .padding()
}
