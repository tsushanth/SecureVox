import SwiftUI

/// Onboarding view for selecting and downloading transcription model
struct ModelSelectionView: View {

    // MARK: - Environment

    @Environment(\.dismiss) private var dismiss

    // MARK: - State

    @StateObject private var transcriptionService = TranscriptionService.shared
    @State private var selectedModel: AppConstants.WhisperModel = .tiny
    @State private var isDownloading = false
    @State private var downloadProgress: Double = 0
    @State private var downloadError: String?
    @State private var showingError = false

    // Callback when setup is complete
    var onComplete: () -> Void

    // MARK: - Body

    var body: some View {
        VStack(spacing: 0) {
            // Header
            headerView
                .padding(.top, 40)
                .padding(.bottom, 24)

            // Model options
            modelOptionsView
                .padding(.horizontal, 40)

            Spacer()

            // Footer with continue button
            footerView
                .padding(24)
        }
        .frame(width: 600, height: 650)
        .background(Color(NSColor.windowBackgroundColor))
        .alert("Download Error", isPresented: $showingError) {
            Button("OK") {}
        } message: {
            Text(downloadError ?? "An error occurred while downloading the model.")
        }
    }

    // MARK: - Header

    private var headerView: some View {
        VStack(spacing: 16) {
            // App icon
            Image(systemName: "waveform.circle.fill")
                .font(.system(size: 64))
                .foregroundStyle(.orange)

            Text("Welcome to SecureVox")
                .font(.largeTitle)
                .fontWeight(.bold)

            Text("Choose a transcription model to get started")
                .font(.title3)
                .foregroundStyle(.secondary)

            Text("All transcription happens on-device for complete privacy.")
                .font(.caption)
                .foregroundStyle(.tertiary)
        }
    }

    // MARK: - Model Options

    private var modelOptionsView: some View {
        VStack(spacing: 12) {
            ForEach(AppConstants.WhisperModel.allCases) { model in
                ModelOptionCard(
                    model: model,
                    isSelected: selectedModel == model,
                    isDownloading: isDownloading && selectedModel == model,
                    downloadProgress: isDownloading && selectedModel == model ? downloadProgress : nil,
                    onSelect: {
                        if !isDownloading {
                            selectedModel = model
                        }
                    }
                )
            }
        }
    }

    // MARK: - Footer

    private var footerView: some View {
        VStack(spacing: 16) {
            if isDownloading {
                VStack(spacing: 8) {
                    ProgressView(value: downloadProgress)
                        .progressViewStyle(.linear)

                    Text("Downloading \(selectedModel.displayName) model...")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }

            HStack(spacing: 16) {
                // Skip button (use Apple Speech)
                Button("Use Apple Speech Instead") {
                    // Set to use Apple Speech and complete
                    UserDefaults.standard.set(true, forKey: "useAppleSpeechOnly")
                    UserDefaults.standard.set(true, forKey: "hasCompletedOnboarding")
                    onComplete()
                }
                .buttonStyle(.bordered)
                .disabled(isDownloading)

                // Continue button
                Button(isDownloading ? "Downloading..." : "Continue with \(selectedModel.displayName)") {
                    startDownloadAndContinue()
                }
                .buttonStyle(.borderedProminent)
                .tint(.orange)
                .disabled(isDownloading)
            }

            Text("You can change this later in Settings")
                .font(.caption2)
                .foregroundStyle(.tertiary)
        }
    }

    // MARK: - Actions

    private func startDownloadAndContinue() {
        // If tiny model is selected, it's bundled so no download needed
        if selectedModel == .tiny {
            completeSetup()
            return
        }

        // Start download for other models
        isDownloading = true
        downloadProgress = 0
        downloadError = nil

        Task {
            do {
                // Simulate download with progress updates
                // In production, this would use WhisperKit's download mechanism
                try await transcriptionService.downloadModel(selectedModel)

                await MainActor.run {
                    completeSetup()
                }
            } catch {
                await MainActor.run {
                    isDownloading = false
                    downloadError = error.localizedDescription
                    showingError = true
                }
            }
        }

        // Monitor progress
        Task {
            while isDownloading {
                try? await Task.sleep(nanoseconds: 100_000_000) // 100ms
                await MainActor.run {
                    if let progress = transcriptionService.modelDownloadProgress[selectedModel.rawValue] {
                        downloadProgress = progress
                    }
                }
            }
        }
    }

    private func completeSetup() {
        // Save selected model
        transcriptionService.selectedModel = selectedModel
        UserDefaults.standard.set(selectedModel.rawValue, forKey: "selectedModel")
        UserDefaults.standard.set(false, forKey: "useAppleSpeechOnly")
        UserDefaults.standard.set(true, forKey: "hasCompletedOnboarding")

        isDownloading = false
        onComplete()
    }
}

// MARK: - Model Option Card

struct ModelOptionCard: View {
    let model: AppConstants.WhisperModel
    let isSelected: Bool
    var isDownloading: Bool = false
    var downloadProgress: Double?
    let onSelect: () -> Void

    var body: some View {
        Button(action: onSelect) {
            HStack(spacing: 16) {
                // Selection indicator
                Image(systemName: isSelected ? "checkmark.circle.fill" : "circle")
                    .font(.title2)
                    .foregroundStyle(isSelected ? .orange : .secondary)

                // Model info
                VStack(alignment: .leading, spacing: 4) {
                    HStack {
                        Text(model.displayName)
                            .font(.headline)

                        Text("(\(model.subtitle))")
                            .font(.subheadline)
                            .foregroundStyle(.secondary)

                        if model.isBundled {
                            Text("Built-in")
                                .font(.caption)
                                .padding(.horizontal, 6)
                                .padding(.vertical, 2)
                                .background(Color.green.opacity(0.2))
                                .foregroundStyle(.green)
                                .cornerRadius(4)
                        }
                    }

                    HStack(spacing: 16) {
                        Label(model.expectedRAM, systemImage: "memorychip")
                        Label(model.downloadSize, systemImage: "arrow.down.circle")
                        Label(model.approximateSpeed, systemImage: "speedometer")
                    }
                    .font(.caption)
                    .foregroundStyle(.secondary)
                }

                Spacer()

                // Download progress or checkmark
                if isDownloading, let progress = downloadProgress {
                    ProgressView(value: progress)
                        .progressViewStyle(.circular)
                        .frame(width: 24, height: 24)
                } else if model.isBundled {
                    Image(systemName: "checkmark.seal.fill")
                        .foregroundStyle(.green)
                }
            }
            .padding(16)
            .background(isSelected ? Color.orange.opacity(0.1) : Color(NSColor.controlBackgroundColor))
            .cornerRadius(12)
            .overlay(
                RoundedRectangle(cornerRadius: 12)
                    .stroke(isSelected ? Color.orange : Color.clear, lineWidth: 2)
            )
        }
        .buttonStyle(.plain)
    }
}

// MARK: - Preview

#Preview {
    ModelSelectionView {
        // Setup complete callback
    }
}
