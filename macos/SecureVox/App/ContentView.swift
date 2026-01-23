import SwiftUI
import SwiftData
import AVFoundation
import UniformTypeIdentifiers

/// Main content view with sidebar navigation - clean two-column design
struct ContentView: View {

    // MARK: - Environment

    @EnvironmentObject private var appState: AppState
    @Environment(\.modelContext) private var modelContext

    // MARK: - State

    @AppStorage("theme") private var theme = "System"
    @State private var searchText = ""

    // Computed color scheme based on theme setting
    private var preferredColorScheme: ColorScheme? {
        switch theme {
        case "Light": return .light
        case "Dark": return .dark
        default: return nil // System
        }
    }

    // MARK: - Body

    var body: some View {
        NavigationSplitView {
            // Sidebar
            SidebarView()
        } detail: {
            // Main content area
            switch appState.selectedTab {
            case .home:
                HomeMainView()
            case .vocabulary:
                VocabularyView()
            case .settings:
                SettingsView()
            case .faq:
                FAQView()
            case .recycleBin:
                RecycleBinView()
            }
        }
        .frame(minWidth: 800, minHeight: 500)
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button {
                    // Search action
                } label: {
                    Image(systemName: "magnifyingglass")
                }
            }
        }
        .preferredColorScheme(preferredColorScheme)
    }
}

// MARK: - Sidebar View

struct SidebarView: View {
    @EnvironmentObject private var appState: AppState
    @Query(filter: #Predicate<Recording> { !$0.isDeleted }) private var allRecordings: [Recording]
    @Query(filter: #Predicate<Recording> { $0.isDeleted }) private var deletedRecordings: [Recording]

    // Count recordings by type
    private var quickCount: Int {
        allRecordings.filter { $0.sourceType == .quick }.count
    }
    private var recordedCount: Int {
        allRecordings.filter { $0.sourceType == .recorded }.count
    }
    private var importedCount: Int {
        allRecordings.filter { $0.sourceType == .imported }.count
    }
    private var meetingCount: Int {
        allRecordings.filter { $0.sourceType == .meeting }.count
    }

    var body: some View {
        List(selection: $appState.selectedTab) {
            // App header
            HStack(spacing: 10) {
                Image(systemName: "waveform.circle.fill")
                    .font(.title2)
                    .foregroundStyle(.orange)
                Text("SecureVox")
                    .font(.headline)
                    .fontWeight(.semibold)
            }
            .padding(.vertical, 12)
            .padding(.horizontal, 4)

            // Home section with categories
            Section {
                SidebarRow(
                    icon: "house.fill",
                    title: "Home",
                    isSelected: appState.selectedTab == .home && appState.selectedHomeCategory == .quick,
                    isExpanded: true
                ) {
                    appState.selectedTab = .home
                }

                // Sub-categories (always visible like competitor)
                Group {
                    SidebarSubRow(
                        icon: "bolt.fill",
                        title: "Quick",
                        count: quickCount,
                        isSelected: appState.selectedTab == .home && appState.selectedHomeCategory == .quick
                    ) {
                        appState.selectedHomeCategory = .quick
                        appState.selectedTab = .home
                    }

                    SidebarSubRow(
                        icon: "mic.fill",
                        title: "Recorded",
                        count: recordedCount,
                        isSelected: appState.selectedTab == .home && appState.selectedHomeCategory == .recorded
                    ) {
                        appState.selectedHomeCategory = .recorded
                        appState.selectedTab = .home
                    }

                    SidebarSubRow(
                        icon: "square.and.arrow.down.fill",
                        title: "Imported",
                        count: importedCount,
                        isSelected: appState.selectedTab == .home && appState.selectedHomeCategory == .imported
                    ) {
                        appState.selectedHomeCategory = .imported
                        appState.selectedTab = .home
                    }

                    SidebarSubRow(
                        icon: "video.fill",
                        title: "Meeting",
                        count: meetingCount,
                        isSelected: appState.selectedTab == .home && appState.selectedHomeCategory == .meeting
                    ) {
                        appState.selectedHomeCategory = .meeting
                        appState.selectedTab = .home
                    }
                }

                SidebarRow(
                    icon: "text.book.closed.fill",
                    title: "Vocabulary",
                    isSelected: appState.selectedTab == .vocabulary
                ) {
                    appState.selectedTab = .vocabulary
                }

                SidebarRow(
                    icon: "gearshape.fill",
                    title: "Settings",
                    isSelected: appState.selectedTab == .settings
                ) {
                    appState.selectedTab = .settings
                }
            }

            // More section
            Section("More") {
                SidebarRow(
                    icon: "questionmark.circle.fill",
                    title: "FAQ",
                    isSelected: appState.selectedTab == .faq
                ) {
                    appState.selectedTab = .faq
                }

                SidebarRow(
                    icon: "trash.fill",
                    title: "Recycle Bin",
                    count: deletedRecordings.count,
                    countColor: .red,
                    isSelected: appState.selectedTab == .recycleBin
                ) {
                    appState.selectedTab = .recycleBin
                }
            }
        }
        .listStyle(.sidebar)
        .frame(minWidth: 200, idealWidth: 220)
    }
}

// MARK: - Sidebar Row

struct SidebarRow: View {
    let icon: String
    let title: String
    var count: Int = 0
    var countColor: Color = .secondary
    var isSelected: Bool = false
    var isExpanded: Bool = false
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 10) {
                Image(systemName: icon)
                    .font(.system(size: 14))
                    .foregroundStyle(isSelected ? .orange : .secondary)
                    .frame(width: 20)

                Text(title)
                    .fontWeight(isSelected ? .medium : .regular)

                Spacer()

                if count > 0 {
                    Text("\(count)")
                        .font(.caption)
                        .fontWeight(.medium)
                        .padding(.horizontal, 8)
                        .padding(.vertical, 3)
                        .background(countColor.opacity(0.15))
                        .foregroundStyle(countColor)
                        .cornerRadius(6)
                }

                if isExpanded {
                    Image(systemName: "chevron.down")
                        .font(.caption)
                        .foregroundStyle(.tertiary)
                }
            }
            .padding(.vertical, 6)
            .padding(.horizontal, 8)
            .background(isSelected ? Color.orange.opacity(0.1) : Color.clear)
            .cornerRadius(8)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

// MARK: - Sidebar Sub Row

struct SidebarSubRow: View {
    let icon: String
    let title: String
    var count: Int = 0
    var isSelected: Bool = false
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 10) {
                Image(systemName: icon)
                    .font(.system(size: 12))
                    .foregroundStyle(isSelected ? .orange : Color.secondary.opacity(0.6))
                    .frame(width: 16)

                Text(title)
                    .font(.subheadline)
                    .foregroundStyle(isSelected ? .primary : .secondary)

                Spacer()

                if count > 0 {
                    Text("\(count)")
                        .font(.caption2)
                        .fontWeight(.medium)
                        .padding(.horizontal, 6)
                        .padding(.vertical, 2)
                        .background(Color.secondary.opacity(0.1))
                        .foregroundStyle(.secondary)
                        .cornerRadius(4)
                }
            }
            .padding(.vertical, 4)
            .padding(.leading, 32)
            .padding(.trailing, 8)
            .background(isSelected ? Color.orange.opacity(0.08) : Color.clear)
            .cornerRadius(6)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

// MARK: - Home Main View (Combined list + detail)

struct HomeMainView: View {
    @EnvironmentObject private var appState: AppState
    @Environment(\.modelContext) private var modelContext
    @Query(filter: #Predicate<Recording> { !$0.isDeleted }, sort: \Recording.createdAt, order: .reverse) private var allRecordings: [Recording]

    @State private var selectedRecording: Recording?
    @StateObject private var recorderService = AudioRecorderService()
    @State private var currentRecordingURL: URL?

    // Context menu state
    @State private var recordingToRename: Recording?
    @State private var newTitle = ""
    @State private var showingRenameSheet = false
    @State private var recordingToDelete: Recording?

    private var filteredRecordings: [Recording] {
        switch appState.selectedHomeCategory {
        case .quick:
            return allRecordings.filter { $0.sourceType == .quick }
        case .recorded:
            return allRecordings.filter { $0.sourceType == .recorded }
        case .imported:
            return allRecordings.filter { $0.sourceType == .imported }
        case .meeting:
            return allRecordings.filter { $0.sourceType == .meeting }
        }
    }

    var body: some View {
        ZStack(alignment: .bottomTrailing) {
            // Main content - single column with list and detail
            GeometryReader { geometry in
                HStack(spacing: 0) {
                    // Recordings list (left side)
                    VStack(spacing: 0) {
                        // Search bar
                        HStack {
                            Image(systemName: "magnifyingglass")
                                .foregroundStyle(.secondary)
                            TextField("Search recordings...", text: .constant(""))
                                .textFieldStyle(.plain)
                        }
                        .padding(10)
                        .background(Color(NSColor.textBackgroundColor).opacity(0.5))
                        .cornerRadius(8)
                        .padding()

                        // Recordings list
                        if filteredRecordings.isEmpty {
                            emptyStateView
                        } else {
                            ScrollView {
                                LazyVStack(spacing: 2) {
                                    ForEach(filteredRecordings) { recording in
                                        RecordingListRow(
                                            recording: recording,
                                            isSelected: selectedRecording?.id == recording.id
                                        ) {
                                            selectedRecording = recording
                                        }
                                        .contextMenu {
                                            recordingContextMenu(for: recording)
                                        }
                                    }
                                }
                                .padding(.horizontal)
                                .padding(.bottom, 80) // Space for floating buttons
                            }
                        }
                    }
                    .frame(width: min(350, geometry.size.width * 0.4))
                    .background(Color(NSColor.windowBackgroundColor))

                    Divider()

                    // Detail view (right side)
                    if let recording = selectedRecording {
                        RecordingDetailView(recording: recording)
                            .frame(maxWidth: .infinity)
                    } else {
                        selectRecordingView
                            .frame(maxWidth: .infinity)
                    }
                }
            }

            // Floating buttons - import and record
            floatingButtons
                .padding(.trailing, 24)
                .padding(.bottom, 24)
        }
        .sheet(isPresented: $showingRenameSheet) {
            renameSheet
        }
        .alert(
            "Delete Recording",
            isPresented: Binding(
                get: { recordingToDelete != nil },
                set: { if !$0 { recordingToDelete = nil } }
            ),
            presenting: recordingToDelete
        ) { recording in
            Button("Cancel", role: .cancel) {
                recordingToDelete = nil
            }
            Button("Delete", role: .destructive) {
                deleteRecording(recording)
                recordingToDelete = nil
            }
        } message: { _ in
            Text("Are you sure you want to delete this recording? It will be moved to the Recycle Bin.")
        }
    }

    // MARK: - Context Menu

    @ViewBuilder
    private func recordingContextMenu(for recording: Recording) -> some View {
        // Rename
        Button {
            recordingToRename = recording
            newTitle = recording.title
            showingRenameSheet = true
        } label: {
            Label("Rename", systemImage: "pencil")
        }

        // Favorite/Unfavorite
        Button {
            toggleFavorite(recording)
        } label: {
            Label(
                recording.isFavorite ? "Remove from Favorites" : "Add to Favorites",
                systemImage: recording.isFavorite ? "star.slash" : "star"
            )
        }

        Divider()

        // Category submenu
        Menu {
            Button {
                setSourceType(recording, to: .quick)
            } label: {
                Label("Quick", systemImage: "bolt")
            }

            Button {
                setSourceType(recording, to: .recorded)
            } label: {
                Label("Recorded", systemImage: "mic")
            }

            Button {
                setSourceType(recording, to: .imported)
            } label: {
                Label("Imported", systemImage: "square.and.arrow.down")
            }

            Button {
                setSourceType(recording, to: .meeting)
            } label: {
                Label("Meeting", systemImage: "video")
            }
        } label: {
            Label("Move to Category", systemImage: "folder")
        }

        Divider()

        // Copy Transcript (if available)
        if recording.transcriptionStatus == .completed && !recording.fullTranscript.isEmpty {
            Button {
                copyTranscript(recording)
            } label: {
                Label("Copy Transcript", systemImage: "doc.on.doc")
            }
        }

        Divider()

        // Delete
        Button(role: .destructive) {
            recordingToDelete = recording
        } label: {
            Label("Delete", systemImage: "trash")
        }
    }

    private var renameSheet: some View {
        VStack(spacing: 20) {
            Text("Rename Recording")
                .font(.headline)

            TextField("Title", text: $newTitle)
                .textFieldStyle(.roundedBorder)
                .frame(width: 300)

            HStack(spacing: 12) {
                Button("Cancel") {
                    showingRenameSheet = false
                    recordingToRename = nil
                }
                .keyboardShortcut(.cancelAction)

                Button("Save") {
                    if let recording = recordingToRename {
                        recording.title = newTitle
                        try? modelContext.save()
                    }
                    showingRenameSheet = false
                    recordingToRename = nil
                }
                .keyboardShortcut(.defaultAction)
                .disabled(newTitle.trimmingCharacters(in: .whitespaces).isEmpty)
            }
        }
        .padding(24)
        .frame(width: 400)
    }

    // MARK: - Context Menu Actions

    private func deleteRecording(_ recording: Recording) {
        // Clear selection if this is the selected recording
        if selectedRecording?.id == recording.id {
            selectedRecording = nil
        }
        recording.isDeleted = true
        recording.deletedAt = Date()
        try? modelContext.save()
    }

    private func toggleFavorite(_ recording: Recording) {
        recording.isFavorite.toggle()
        try? modelContext.save()
    }

    private func setSourceType(_ recording: Recording, to sourceType: SourceType) {
        recording.sourceType = sourceType
        try? modelContext.save()
    }

    private func copyTranscript(_ recording: Recording) {
        let transcript = recording.fullTranscript
        guard !transcript.isEmpty else { return }
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(transcript, forType: .string)
    }

    private var emptyStateView: some View {
        VStack(spacing: 16) {
            Image(systemName: "waveform")
                .font(.system(size: 48))
                .foregroundStyle(Color.secondary.opacity(0.5))

            Text("No \(appState.selectedHomeCategory.rawValue.lowercased()) recordings")
                .font(.title3)
                .foregroundStyle(.secondary)

            Text("Click the mic button to start recording")
                .font(.caption)
                .foregroundStyle(Color.secondary.opacity(0.6))
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private var selectRecordingView: some View {
        VStack(spacing: 16) {
            Image(systemName: "text.bubble")
                .font(.system(size: 48))
                .foregroundStyle(Color.secondary.opacity(0.5))

            Text("Select a recording")
                .font(.title3)
                .foregroundStyle(.secondary)

            Text("Choose a recording from the list to view its transcript")
                .font(.caption)
                .foregroundStyle(Color.secondary.opacity(0.6))
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color(NSColor.textBackgroundColor).opacity(0.3))
    }

    private var floatingButtons: some View {
        HStack(spacing: 12) {
            // Import button
            Button {
                importAudio()
            } label: {
                ZStack {
                    Circle()
                        .fill(Color(NSColor.controlBackgroundColor))
                        .frame(width: 48, height: 48)
                        .shadow(color: .black.opacity(0.15), radius: 8, y: 2)

                    Image(systemName: "folder.fill")
                        .font(.title3)
                        .foregroundStyle(.orange)
                }
            }
            .buttonStyle(.plain)
            .help("Import Audio File")

            // Record button
            Button {
                toggleRecording()
            } label: {
                ZStack {
                    Circle()
                        .fill(recorderService.isRecording ? Color.red : Color.orange)
                        .frame(width: 56, height: 56)
                        .shadow(color: (recorderService.isRecording ? Color.red : Color.orange).opacity(0.4), radius: 12, y: 4)

                    if recorderService.isRecording {
                        // Show recording indicator
                        VStack(spacing: 2) {
                            Image(systemName: "stop.fill")
                                .font(.title2)
                                .foregroundStyle(.white)
                            Text(formatDuration(recorderService.recordingDuration))
                                .font(.system(size: 9, weight: .medium, design: .monospaced))
                                .foregroundStyle(.white.opacity(0.9))
                        }
                    } else {
                        Image(systemName: "mic.fill")
                            .font(.title2)
                            .foregroundStyle(.white)
                    }
                }
            }
            .buttonStyle(.plain)
            .help(recorderService.isRecording ? "Stop Recording" : "Start Recording")
        }
    }

    private func toggleRecording() {
        if recorderService.isRecording {
            // Stop recording
            if let result = recorderService.stopRecording() {
                // Get file size
                let fileSize = (try? FileManager.default.attributesOfItem(atPath: result.url.path)[.size] as? Int64) ?? 0

                // Create new Recording entry
                let recording = Recording(
                    title: "Recording \(Date().formatted(date: .abbreviated, time: .shortened))",
                    duration: result.duration,
                    audioFileName: result.url.lastPathComponent,
                    audioFileSize: fileSize,
                    sourceType: .recorded
                )
                modelContext.insert(recording)
                try? modelContext.save()
                selectedRecording = recording

                // Auto-start transcription
                startTranscription(for: recording, audioURL: result.url)
            }
        } else {
            // Start recording
            Task {
                if let url = await recorderService.startRecording() {
                    currentRecordingURL = url
                }
            }
        }
    }

    private func startTranscription(for recording: Recording, audioURL: URL) {
        recording.transcriptionStatus = .inProgress
        try? modelContext.save()

        Task {
            do {
                let transcriptionService = TranscriptionService.shared
                let segments = try await transcriptionService.transcribe(audioURL: audioURL)

                // Save segments to the recording
                for segment in segments {
                    segment.recording = recording
                    modelContext.insert(segment)
                }

                recording.transcriptionStatus = .completed
                recording.transcriptionModel = transcriptionService.selectedModel.rawValue
                recording.detectedLanguage = transcriptionService.selectedLanguage.rawValue
                try? modelContext.save()

            } catch {
                recording.transcriptionStatus = .failed
                recording.transcriptionError = error.localizedDescription
                try? modelContext.save()
            }
        }
    }

    private func importAudio() {
        let panel = NSOpenPanel()
        panel.allowedContentTypes = [.audio, .mpeg4Audio, .mp3, .wav, .aiff]
        panel.allowsMultipleSelection = false
        panel.canChooseDirectories = false
        panel.message = "Select an audio file to import"

        if panel.runModal() == .OK, let url = panel.url {
            // Copy file to app's documents
            let documentsURL = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            let recordingsURL = documentsURL.appendingPathComponent(AppConstants.Storage.recordingsDirectory)
            try? FileManager.default.createDirectory(at: recordingsURL, withIntermediateDirectories: true)

            let destURL = recordingsURL.appendingPathComponent(url.lastPathComponent)
            try? FileManager.default.copyItem(at: url, to: destURL)

            // Get audio duration and file size
            let asset = AVURLAsset(url: destURL)
            let duration = CMTimeGetSeconds(asset.duration)
            let fileSize = (try? FileManager.default.attributesOfItem(atPath: destURL.path)[.size] as? Int64) ?? 0

            // Create recording entry
            let recording = Recording(
                title: url.deletingPathExtension().lastPathComponent,
                duration: duration.isNaN ? 0 : duration,
                audioFileName: destURL.lastPathComponent,
                audioFileSize: fileSize,
                sourceType: .imported
            )
            recording.originalFileName = url.lastPathComponent
            modelContext.insert(recording)
            try? modelContext.save()
            selectedRecording = recording

            // Switch to imported category
            appState.selectedHomeCategory = .imported

            // Auto-start transcription for imported files
            startTranscription(for: recording, audioURL: destURL)
        }
    }

    private func formatDuration(_ duration: TimeInterval) -> String {
        let minutes = Int(duration) / 60
        let seconds = Int(duration) % 60
        return String(format: "%d:%02d", minutes, seconds)
    }
}

// MARK: - Recording List Row

struct RecordingListRow: View {
    let recording: Recording
    let isSelected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 4) {
                    // Date/time
                    Text(recording.createdAt.formatted(date: .abbreviated, time: .shortened))
                        .font(.caption)
                        .foregroundStyle(.secondary)

                    // Title or transcript preview
                    Text(recording.title)
                        .font(.body)
                        .fontWeight(.medium)
                        .lineLimit(1)

                    // Transcript preview if available
                    if !recording.fullTranscript.isEmpty {
                        Text(recording.fullTranscript)
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                            .lineLimit(2)
                    }
                }

                Spacer()

                // Status and duration
                VStack(alignment: .trailing, spacing: 4) {
                    // Status indicator
                    if recording.transcriptionStatus == .completed {
                        Image(systemName: "checkmark.circle.fill")
                            .foregroundStyle(.green)
                            .font(.caption)
                    } else if recording.transcriptionStatus == .inProgress {
                        ProgressView()
                            .scaleEffect(0.6)
                    }

                    // Duration
                    Text(recording.formattedDuration)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .monospacedDigit()
                }
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 10)
            .background(isSelected ? Color.orange.opacity(0.1) : Color.clear)
            .cornerRadius(8)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

// MARK: - Preview

#Preview {
    ContentView()
        .environmentObject(AppState())
}
