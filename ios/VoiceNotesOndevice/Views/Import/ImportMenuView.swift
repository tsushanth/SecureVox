import SwiftUI
import SwiftData
import PhotosUI

/// Menu for selecting import source and handling media import
struct ImportMenuView: View {

    // MARK: - Environment

    @Environment(\.dismiss) private var dismiss
    @Environment(\.modelContext) private var modelContext

    // MARK: - Properties

    let onComplete: (Recording) -> Void

    // MARK: - State

    @State private var showingPhotoPicker = false
    @State private var showingDocumentPicker = false
    @State private var isImporting = false
    @State private var importProgress: Double = 0
    @State private var importStage: String = ""
    @State private var errorMessage: String?
    @State private var showingError = false

    // MARK: - Body

    var body: some View {
        NavigationStack {
            List {
                // Import sources section
                Section {
                    photoLibraryButton
                    filesButton
                } header: {
                    Text("Choose Source")
                } footer: {
                    Text("Audio will be extracted from videos. Supported formats: MP4, MOV, MP3, M4A, WAV, and more.")
                }

                // Supported formats info
                Section {
                    supportedFormatsInfo
                }
            }
            .navigationTitle("Import Media")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("Cancel") {
                        if isImporting {
                            cancelImport()
                        }
                        dismiss()
                    }
                }
            }
            .sheet(isPresented: $showingPhotoPicker) {
                PhotosPickerView(
                    onPick: { result in
                        showingPhotoPicker = false
                        Task {
                            await importFromPhotoPicker(result)
                        }
                    },
                    onCancel: {
                        showingPhotoPicker = false
                    }
                )
                .ignoresSafeArea()
            }
            .sheet(isPresented: $showingDocumentPicker) {
                DocumentPickerView { url in
                    showingDocumentPicker = false
                    Task {
                        await importFromFile(url)
                    }
                }
            }
            .overlay {
                if isImporting {
                    importingOverlay
                }
            }
            .alert("Import Error", isPresented: $showingError) {
                Button("OK") {
                    errorMessage = nil
                }
            } message: {
                Text(errorMessage ?? "An unknown error occurred")
            }
        }
        .interactiveDismissDisabled(isImporting)
    }

    // MARK: - Source Buttons

    private var photoLibraryButton: some View {
        Button {
            showingPhotoPicker = true
        } label: {
            Label {
                VStack(alignment: .leading, spacing: 4) {
                    Text("Photo Library")
                        .foregroundStyle(.primary)
                    Text("Import videos from Camera Roll")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            } icon: {
                Image(systemName: "photo.on.rectangle")
                    .font(.title2)
                    .foregroundStyle(.blue)
                    .frame(width: 32)
            }
        }
        .disabled(isImporting)
    }

    private var filesButton: some View {
        Button {
            showingDocumentPicker = true
        } label: {
            Label {
                VStack(alignment: .leading, spacing: 4) {
                    Text("Files")
                        .foregroundStyle(.primary)
                    Text("Import audio or video files")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            } icon: {
                Image(systemName: "folder")
                    .font(.title2)
                    .foregroundStyle(.blue)
                    .frame(width: 32)
            }
        }
        .disabled(isImporting)
    }

    // MARK: - Supported Formats

    private var supportedFormatsInfo: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Supported Formats")
                .font(.headline)

            VStack(alignment: .leading, spacing: 8) {
                FormatRow(
                    icon: "video.fill",
                    title: "Video",
                    formats: "MP4, MOV, M4V, AVI, WebM"
                )
                FormatRow(
                    icon: "waveform",
                    title: "Audio",
                    formats: "MP3, M4A, WAV, AAC, AIFF, FLAC"
                )
            }

            Text("Maximum file size: 2 GB")
                .font(.caption)
                .foregroundStyle(.secondary)
                .padding(.top, 4)
        }
        .padding(.vertical, 8)
    }

    // MARK: - Importing Overlay

    private var importingOverlay: some View {
        ZStack {
            Color.black.opacity(0.5)
                .ignoresSafeArea()

            VStack(spacing: 20) {
                // Progress circle
                ZStack {
                    Circle()
                        .stroke(Color.gray.opacity(0.3), lineWidth: 8)
                        .frame(width: 80, height: 80)

                    Circle()
                        .trim(from: 0, to: importProgress)
                        .stroke(Color.blue, style: StrokeStyle(lineWidth: 8, lineCap: .round))
                        .frame(width: 80, height: 80)
                        .rotationEffect(.degrees(-90))
                        .animation(.linear(duration: 0.1), value: importProgress)

                    Text("\(Int(importProgress * 100))%")
                        .font(.headline)
                        .monospacedDigit()
                }

                VStack(spacing: 8) {
                    Text("Importing...")
                        .font(.headline)

                    Text(importStage)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }

                Button(role: .destructive) {
                    cancelImport()
                } label: {
                    Text("Cancel")
                        .font(.subheadline)
                }
                .buttonStyle(.bordered)
            }
            .padding(32)
            .background(.regularMaterial)
            .clipShape(RoundedRectangle(cornerRadius: 20))
            .shadow(radius: 20)
        }
    }

    // MARK: - Import Methods

    private func importFromPhotoPicker(_ result: PHPickerResult) async {
        await MainActor.run {
            isImporting = true
            importProgress = 0
            importStage = "Loading video..."
        }

        let importService = MediaImportService.shared

        do {
            let importResult = try await importService.importFromPhotoPicker(result) { progress in
                Task { @MainActor in
                    self.importProgress = progress.fractionCompleted
                    self.importStage = progress.message
                }
            }

            await handleImportSuccess(importResult)

        } catch {
            await handleImportError(error)
        }
    }

    private func importFromFile(_ url: URL) async {
        await MainActor.run {
            isImporting = true
            importProgress = 0
            importStage = "Loading file..."
        }

        let importService = MediaImportService.shared

        do {
            let importResult = try await importService.importFile(from: url) { progress in
                Task { @MainActor in
                    self.importProgress = progress.fractionCompleted
                    self.importStage = progress.message
                }
            }

            await handleImportSuccess(importResult)

        } catch {
            await handleImportError(error)
        }
    }

    @MainActor
    private func handleImportSuccess(_ result: MediaImportService.ImportResult) {
        // Create recording from import result
        let recording = Recording(
            title: generateTitle(from: result.originalFileName),
            duration: result.duration,
            sourceType: result.sourceType
        )
        recording.audioFileName = result.audioFileURL.lastPathComponent
        recording.audioFileSize = result.fileSize

        // Save to SwiftData
        modelContext.insert(recording)

        do {
            try modelContext.save()
        } catch {
            errorMessage = "Failed to save recording: \(error.localizedDescription)"
            showingError = true
            isImporting = false
            return
        }

        isImporting = false

        // Notify parent and dismiss
        onComplete(recording)
        dismiss()
    }

    @MainActor
    private func handleImportError(_ error: Error) {
        isImporting = false

        if let importError = error as? MediaImportService.ImportError {
            switch importError {
            case .cancelled:
                // User cancelled, no error message needed
                return
            default:
                errorMessage = importError.localizedDescription
            }
        } else {
            errorMessage = error.localizedDescription
        }

        showingError = true
    }

    private func cancelImport() {
        Task {
            await MediaImportService.shared.cancelImport()
        }
        isImporting = false
    }

    private func generateTitle(from fileName: String) -> String {
        // Remove extension
        let nameWithoutExtension = (fileName as NSString).deletingPathExtension

        // Clean up common patterns
        var title = nameWithoutExtension
            .replacingOccurrences(of: "_", with: " ")
            .replacingOccurrences(of: "-", with: " ")

        // Capitalize first letter
        if let first = title.first {
            title = first.uppercased() + title.dropFirst()
        }

        // Truncate if too long
        if title.count > 50 {
            title = String(title.prefix(50)) + "..."
        }

        return title.isEmpty ? "Imported Recording" : title
    }
}

// MARK: - Format Row

private struct FormatRow: View {
    let icon: String
    let title: String
    let formats: String

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: icon)
                .frame(width: 24)
                .foregroundStyle(.secondary)

            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.subheadline)
                    .fontWeight(.medium)
                Text(formats)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
    }
}

// MARK: - Preview

#Preview {
    ImportMenuView { recording in
        print("Imported: \(recording.title)")
    }
}
