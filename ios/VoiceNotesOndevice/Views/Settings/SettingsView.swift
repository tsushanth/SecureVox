import SwiftUI
import StoreKit
import SwiftData

/// App settings screen
struct SettingsView: View {

    // MARK: - Environment

    @Environment(\.modelContext) private var modelContext

    // MARK: - State

    @StateObject private var viewModel = SettingsViewModel()
    @State private var showingDeleteAudioConfirmation = false
    @State private var showingDeleteAllConfirmation = false
    @State private var showingLanguagePicker = false
    @State private var isDeletingAudio = false
    @State private var isDeletingRecordings = false
    @State private var showingMailError = false
    @State private var showingShareSheet = false
    @State private var showingRatingPrompt = false

    // MARK: - Body

    var body: some View {
        NavigationStack {
            List {
                transcriptionSection
                languageSection
                punctuationSection
                recordingSection
                audioSettingsSection
                appearanceSection
                dataManagementSection
                storageSection
                privacySection
                aboutSection
            }
            .navigationTitle("Settings")
            .task {
                await viewModel.loadStorageInfo()
                await viewModel.refreshModelAvailability()
            }
            .confirmationDialog(
                "Delete All Audio Files?",
                isPresented: $showingDeleteAudioConfirmation,
                titleVisibility: .visible
            ) {
                Button("Delete All Audio", role: .destructive) {
                    Task {
                        await deleteAllAudioFiles()
                    }
                }
                Button("Cancel", role: .cancel) { }
            } message: {
                Text("Transcripts will be preserved. This cannot be undone.")
            }
            .confirmationDialog(
                "Delete All Recordings?",
                isPresented: $showingDeleteAllConfirmation,
                titleVisibility: .visible
            ) {
                Button("Delete Everything", role: .destructive) {
                    Task {
                        await deleteAllRecordings()
                    }
                }
                Button("Cancel", role: .cancel) { }
            } message: {
                Text("All recordings and transcripts will be permanently deleted. This cannot be undone.")
            }
            .sheet(isPresented: $showingLanguagePicker) {
                LanguagePickerView(selectedLanguage: $viewModel.defaultLanguage)
            }
            .alert("Error", isPresented: .constant(viewModel.errorMessage != nil)) {
                Button("OK") { viewModel.errorMessage = nil }
            } message: {
                Text(viewModel.errorMessage ?? "")
            }
            .alert("Unable to Open Mail", isPresented: $showingMailError) {
                Button("OK") { }
            } message: {
                Text("Please email us at \(AppConstants.Support.email)")
            }
            .sheet(isPresented: $showingShareSheet) {
                ShareSheet(items: [
                    "Check out \(AppConstants.appName) - a private, offline voice transcription app!",
                    AppConstants.AppStore.appStoreURL
                ]) {
                    showingShareSheet = false
                }
            }
            .sheet(isPresented: $showingRatingPrompt) {
                RatingPromptView(isPresented: $showingRatingPrompt)
                    .presentationDetents([.height(340)])
            }
        }
    }

    // MARK: - Sections

    private var transcriptionSection: some View {
        Section {
            ForEach(SettingsViewModel.WhisperModel.allCases) { model in
                ModelRow(
                    model: model,
                    isSelected: viewModel.selectedModel == model,
                    downloadProgress: viewModel.modelDownloadProgress[model.rawValue],
                    isAvailable: viewModel.isModelAvailable(model),
                    onSelect: { viewModel.selectedModel = model },
                    onDownload: {
                        Task {
                            await viewModel.downloadModel(model)
                        }
                    }
                )
            }
        } header: {
            Text("Transcription Quality")
        } footer: {
            if CoreMLTranscriber.hasNeuralEngineSupport {
                Text("Higher quality options produce better results but take longer. Your device supports GPU acceleration for optimal performance.")
            } else {
                Text("Higher quality options may be slow on this device. Fast is recommended. iPhone 12 or newer recommended for Balanced and Accurate.")
            }
        }
    }

    private var languageSection: some View {
        Section {
            Button {
                showingLanguagePicker = true
            } label: {
                HStack {
                    Text("Default Language")
                        .foregroundStyle(.primary)
                    Spacer()
                    Text(viewModel.languageDisplayName(for: viewModel.defaultLanguage))
                        .foregroundStyle(.secondary)
                    Image(systemName: "chevron.right")
                        .font(.caption)
                        .foregroundStyle(.tertiary)
                }
            }
        } header: {
            Text("Language")
        } footer: {
            Text("Whisper supports 99 languages. Auto-detect works well for most content, but selecting a specific language can improve accuracy.")
        }
    }

    private var punctuationSection: some View {
        Section {
            Toggle("Auto-Punctuation", isOn: $viewModel.autoPunctuationEnabled)

            Toggle("Smart Capitalization", isOn: $viewModel.smartCapitalizationEnabled)

            NavigationLink {
                CustomDictionaryView()
            } label: {
                HStack {
                    Label("Custom Dictionary", systemImage: "text.book.closed")
                    Spacer()
                    Text("\(CustomDictionaryService.shared.words.count) words")
                        .foregroundStyle(.secondary)
                }
            }
        } header: {
            Text("Transcription")
        } footer: {
            Text("Customize how transcription results are formatted. Add custom words to fix common misrecognitions.")
        }
    }

    private var recordingSection: some View {
        Section {
            Toggle("Sound Effects", isOn: $viewModel.soundEffectsEnabled)

            Toggle("Haptic Feedback", isOn: $viewModel.hapticFeedbackEnabled)
        } header: {
            Text("Recording")
        } footer: {
            Text("Play sounds and provide haptic feedback when starting and stopping recordings.")
        }
    }

    private var audioSettingsSection: some View {
        Section {
            Picker("Recording Quality", selection: $viewModel.recordingQuality) {
                ForEach(AppConstants.RecordingQuality.allCases) { quality in
                    VStack(alignment: .leading) {
                        Text(quality.displayName)
                        Text(quality.description)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    .tag(quality)
                }
            }
            .pickerStyle(.menu)

            VStack(alignment: .leading, spacing: 8) {
                HStack {
                    Text("Input Gain")
                    Spacer()
                    Text(gainLabel)
                        .foregroundStyle(.secondary)
                }

                Slider(value: $viewModel.inputGain, in: 0.5...2.0, step: 0.1)
            }
        } header: {
            Text("Audio")
        } footer: {
            Text("Higher quality produces larger files. Adjust input gain if recordings are too quiet or loud.")
        }
    }

    private var gainLabel: String {
        if viewModel.inputGain == 1.0 {
            return "Normal"
        } else if viewModel.inputGain < 1.0 {
            return String(format: "-%.0f%%", (1.0 - viewModel.inputGain) * 100)
        } else {
            return String(format: "+%.0f%%", (viewModel.inputGain - 1.0) * 100)
        }
    }

    private var appearanceSection: some View {
        Section {
            Picker("Theme", selection: $viewModel.appTheme) {
                ForEach(AppConstants.AppTheme.allCases) { theme in
                    Label(theme.displayName, systemImage: theme.icon)
                        .tag(theme)
                }
            }
            .pickerStyle(.menu)
        } header: {
            Text("Appearance")
        }
    }

    private var dataManagementSection: some View {
        Section {
            Picker("Recycle Bin", selection: $viewModel.recycleBinRetentionDays) {
                Text("Disabled").tag(0)
                Text("7 Days").tag(7)
                Text("14 Days").tag(14)
                Text("30 Days").tag(30)
            }
            .pickerStyle(.menu)

            NavigationLink {
                RecycleBinView()
            } label: {
                HStack {
                    Label("Recently Deleted", systemImage: "trash")
                    Spacer()
                    if let count = viewModel.deletedRecordingsCount, count > 0 {
                        Text("\(count)")
                            .foregroundStyle(.secondary)
                    }
                }
            }
        } header: {
            Text("Data Management")
        } footer: {
            if viewModel.recycleBinRetentionDays > 0 {
                Text("Deleted recordings are kept for \(viewModel.recycleBinRetentionDays) days before permanent deletion.")
            } else {
                Text("Recordings are permanently deleted immediately.")
            }
        }
    }

    private var storageSection: some View {
        Section {
            if viewModel.isLoadingStorage {
                HStack {
                    Text("Loading...")
                    Spacer()
                    ProgressView()
                }
            } else if let info = viewModel.storageInfo {
                LabeledContent("Recordings", value: "\(info.totalRecordings) files")
                LabeledContent("Audio Files", value: info.formattedAudioSize)
                LabeledContent("Total Duration", value: info.formattedDuration)
                LabeledContent("Available Space", value: info.formattedAvailableSpace)

                Toggle("Auto-Delete Audio", isOn: $viewModel.autoDeleteAudio)

                Toggle("Auto-Copy to Clipboard", isOn: $viewModel.autoCopyToClipboard)

                Button(role: .destructive) {
                    showingDeleteAudioConfirmation = true
                } label: {
                    Text("Delete All Audio Files")
                }
                .disabled(info.totalRecordings == 0)

                Button(role: .destructive) {
                    showingDeleteAllConfirmation = true
                } label: {
                    Text("Delete All Recordings")
                }
                .disabled(info.totalRecordings == 0)
            }
        } header: {
            Text("Storage")
        } footer: {
            if viewModel.autoDeleteAudio && viewModel.autoCopyToClipboard {
                Text("After transcription: audio files will be deleted and transcript will be copied to clipboard.")
            } else if viewModel.autoDeleteAudio {
                Text("Audio files will be automatically deleted after transcription completes. Transcripts are preserved.")
            } else if viewModel.autoCopyToClipboard {
                Text("Transcript will be automatically copied to clipboard after transcription completes.")
            }
        }
    }

    private var privacySection: some View {
        Section {
            Text("\(AppConstants.appName) works completely offline. Your recordings never leave your device, and we never request network access. This means no iCloud sync, but your privacy is absolutely protected.")
                .font(.subheadline)
                .foregroundStyle(.secondary)
        } header: {
            Text("Privacy")
        }
    }

    private var aboutSection: some View {
        Section {
            NavigationLink {
                FAQView()
            } label: {
                Label("FAQ & Help", systemImage: "questionmark.circle")
            }

            Button {
                contactSupport()
            } label: {
                Label("Contact Support", systemImage: "envelope")
            }

            Button {
                showingRatingPrompt = true
            } label: {
                Label("Rate on App Store", systemImage: "star")
            }

            Button {
                shareApp()
            } label: {
                Label("Share with Friends", systemImage: "heart")
            }

            Link(destination: URL(string: "https://kreativekoala.llc/privacy")!) {
                Label("Privacy Policy", systemImage: "hand.raised")
            }

            LabeledContent("Version", value: viewModel.appVersion)
        } header: {
            Text("About")
        }
    }

    // MARK: - Methods

    private func contactSupport() {
        let subject = AppConstants.Support.emailSubject.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? ""
        let urlString = "mailto:\(AppConstants.Support.email)?subject=\(subject)"

        if let url = URL(string: urlString) {
            #if os(iOS)
            UIApplication.shared.open(url) { success in
                if !success {
                    showingMailError = true
                }
            }
            #else
            // macOS fallback - just copy email to clipboard
            showingMailError = true
            #endif
        }
    }

    private func shareApp() {
        showingShareSheet = true
    }

    /// Delete all audio files but keep transcripts
    private func deleteAllAudioFiles() async {
        isDeletingAudio = true
        defer { isDeletingAudio = false }

        do {
            // Fetch all recordings
            let descriptor = FetchDescriptor<Recording>()
            let recordings = try modelContext.fetch(descriptor)

            let fileManager = FileManager.default
            let documentsURL = fileManager.urls(for: .documentDirectory, in: .userDomainMask)[0]

            for recording in recordings {
                // Delete audio file if exists
                if let audioFileName = recording.audioFileName {
                    let audioURL = documentsURL.appendingPathComponent(audioFileName)
                    if fileManager.fileExists(atPath: audioURL.path) {
                        try? fileManager.removeItem(at: audioURL)
                    }
                    // Clear the audio reference but keep the recording
                    recording.audioFileName = nil
                    recording.audioFileSize = 0
                }
            }

            try modelContext.save()
            await viewModel.loadStorageInfo()
        } catch {
            viewModel.errorMessage = "Failed to delete audio files: \(error.localizedDescription)"
        }
    }

    /// Delete all recordings (audio files and transcripts)
    private func deleteAllRecordings() async {
        isDeletingRecordings = true
        defer { isDeletingRecordings = false }

        do {
            // Fetch all recordings
            let descriptor = FetchDescriptor<Recording>()
            let recordings = try modelContext.fetch(descriptor)

            let fileManager = FileManager.default
            let documentsURL = fileManager.urls(for: .documentDirectory, in: .userDomainMask)[0]

            for recording in recordings {
                // Delete audio file if exists
                if let audioFileName = recording.audioFileName {
                    let audioURL = documentsURL.appendingPathComponent(audioFileName)
                    try? fileManager.removeItem(at: audioURL)
                }

                // Delete the recording from SwiftData (segments cascade delete)
                modelContext.delete(recording)
            }

            try modelContext.save()
            await viewModel.loadStorageInfo()
        } catch {
            viewModel.errorMessage = "Failed to delete recordings: \(error.localizedDescription)"
        }
    }
}

// MARK: - Model Row

private struct ModelRow: View {
    let model: SettingsViewModel.WhisperModel
    let isSelected: Bool
    let downloadProgress: Double?
    let isAvailable: Bool
    let onSelect: () -> Void
    let onDownload: () -> Void

    var body: some View {
        Button {
            if isAvailable {
                onSelect()
            } else {
                onDownload()
            }
        } label: {
            HStack {
                VStack(alignment: .leading, spacing: 4) {
                    HStack {
                        Text(model.displayName)
                            .foregroundStyle(.primary)

                        Text("(\(model.subtitle))")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }

                    HStack(spacing: 12) {
                        Text(model.expectedRAM)
                        Text("·")
                        Text(model.approximateSpeed)
                    }
                    .font(.caption)
                    .foregroundStyle(.tertiary)
                }

                Spacer()

                if let progress = downloadProgress {
                    ProgressView(value: progress)
                        .frame(width: 60)
                } else if !isAvailable {
                    Button {
                        onDownload()
                    } label: {
                        Label("Get", systemImage: "arrow.down.circle")
                            .font(.caption)
                    }
                    .buttonStyle(.bordered)
                    .controlSize(.small)
                } else if isSelected {
                    Image(systemName: "checkmark")
                        .foregroundStyle(.blue)
                }
            }
        }
        .disabled(downloadProgress != nil)
    }
}

// MARK: - Preview

#Preview {
    SettingsView()
}
