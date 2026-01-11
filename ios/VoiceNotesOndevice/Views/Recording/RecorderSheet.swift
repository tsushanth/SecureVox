import SwiftUI

/// Sheet view for recording audio
struct RecorderSheet: View {

    // MARK: - Environment

    @Environment(\.dismiss) private var dismiss
    @Environment(\.modelContext) private var modelContext

    // MARK: - Properties

    let onComplete: (Recording) -> Void

    // MARK: - State

    @StateObject private var viewModel = RecorderViewModel()
    @State private var showingCancelConfirmation = false

    // MARK: - Body

    var body: some View {
        NavigationStack {
            VStack(spacing: 32) {
                Spacer()

                // Duration display
                Text(viewModel.formattedDuration)
                    .font(.system(size: 64, weight: .light, design: .monospaced))
                    .foregroundStyle(viewModel.state == .recording ? .primary : .secondary)

                // Waveform visualization
                WaveformView(level: viewModel.audioLevel, isActive: viewModel.state == .recording)
                    .frame(height: 100)
                    .padding(.horizontal)

                // Status text
                statusText

                Spacer()

                // Controls
                controlButtons
                    .padding(.bottom, 40)
            }
            .navigationTitle("Recording")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("Cancel") {
                        if viewModel.state != .idle {
                            showingCancelConfirmation = true
                        } else {
                            dismiss()
                        }
                    }
                }
            }
            .confirmationDialog(
                "Cancel Recording?",
                isPresented: $showingCancelConfirmation,
                titleVisibility: .visible
            ) {
                Button("Discard Recording", role: .destructive) {
                    viewModel.cancelRecording()
                    dismiss()
                }
                Button("Keep Recording", role: .cancel) { }
            } message: {
                Text("Your recording will be lost.")
            }
            .task {
                await viewModel.requestPermission()
            }
            .alert("Error", isPresented: .constant(viewModel.errorMessage != nil)) {
                Button("OK") { viewModel.errorMessage = nil }
            } message: {
                Text(viewModel.errorMessage ?? "")
            }
        }
        .interactiveDismissDisabled(viewModel.state != .idle)
    }

    // MARK: - Subviews

    @ViewBuilder
    private var statusText: some View {
        switch viewModel.state {
        case .idle:
            if viewModel.hasPermission {
                Text("Tap to start recording")
                    .foregroundStyle(.secondary)
            } else {
                Text("Microphone permission required")
                    .foregroundStyle(.red)
            }

        case .recording:
            HStack(spacing: 8) {
                Circle()
                    .fill(.red)
                    .frame(width: 8, height: 8)
                Text("Recording")
            }
            .foregroundStyle(.red)

        case .saving:
            HStack(spacing: 8) {
                ProgressView()
                Text("Saving...")
            }
            .foregroundStyle(.secondary)
        }
    }

    @ViewBuilder
    private var controlButtons: some View {
        HStack(spacing: 40) {
            // Cancel/Delete button (left)
            if viewModel.canStop {
                Button {
                    showingCancelConfirmation = true
                } label: {
                    Image(systemName: "xmark")
                        .font(.title)
                        .foregroundStyle(.red)
                        .frame(width: 60, height: 60)
                        .background(Color.red.opacity(0.15))
                        .clipShape(Circle())
                }
            } else {
                Color.clear.frame(width: 60, height: 60)
            }

            // Main record button (center)
            Button {
                handleMainButtonTap()
            } label: {
                mainButtonContent
            }
            .disabled(viewModel.state == .saving)

            // Stop button (right)
            if viewModel.canStop {
                Button {
                    stopAndSave()
                } label: {
                    Image(systemName: "stop.fill")
                        .font(.title)
                        .foregroundStyle(.white)
                        .frame(width: 60, height: 60)
                        .background(Color.blue)
                        .clipShape(Circle())
                }
            } else {
                Color.clear.frame(width: 60, height: 60)
            }
        }
    }

    @ViewBuilder
    private var mainButtonContent: some View {
        switch viewModel.state {
        case .idle:
            Circle()
                .fill(.red)
                .frame(width: 80, height: 80)
                .overlay {
                    Image(systemName: "mic.fill")
                        .font(.title)
                        .foregroundStyle(.white)
                }
                .shadow(radius: 4)

        case .recording:
            Circle()
                .fill(.red)
                .frame(width: 80, height: 80)
                .overlay {
                    RoundedRectangle(cornerRadius: 4)
                        .fill(.white)
                        .frame(width: 24, height: 24)
                }
                .shadow(radius: 4)

        case .saving:
            Circle()
                .fill(.gray)
                .frame(width: 80, height: 80)
                .overlay {
                    ProgressView()
                        .tint(.white)
                }
                .shadow(radius: 4)
        }
    }

    // MARK: - Actions

    private func handleMainButtonTap() {
        switch viewModel.state {
        case .idle:
            // Check permission and start recording
            if viewModel.hasPermission {
                viewModel.startRecording()
            } else {
                // Request permission first
                Task {
                    await viewModel.requestPermission()
                    if viewModel.hasPermission {
                        viewModel.startRecording()
                    }
                }
            }
        case .recording:
            stopAndSave()
        case .saving:
            break
        }
    }

    private func stopAndSave() {
        guard let result = viewModel.stopRecording() else { return }

        // Create recording in database
        let recording = Recording(
            title: generateTitle(),
            duration: result.duration,
            sourceType: .microphone
        )

        recording.audioFileName = AppConstants.Storage.recordingsDirectory + "/" + result.fileURL.lastPathComponent
        recording.audioFileSize = result.fileSize
        recording.status = .pending

        modelContext.insert(recording)

        do {
            try modelContext.save()
            onComplete(recording)
            dismiss()
        } catch {
            viewModel.errorMessage = "Failed to save recording: \(error.localizedDescription)"
        }
    }

    private func generateTitle() -> String {
        let formatter = DateFormatter()
        formatter.dateStyle = .medium
        formatter.timeStyle = .short
        return "Recording - \(formatter.string(from: Date()))"
    }
}

// MARK: - Preview

#Preview {
    RecorderSheet { _ in }
}
