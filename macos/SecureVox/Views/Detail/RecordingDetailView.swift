import SwiftUI
import SwiftData
import AppKit

/// Detail view for a recording showing transcript and playback controls
struct RecordingDetailView: View {

    // MARK: - Properties

    @Bindable var recording: Recording

    // MARK: - Environment

    @Environment(\.modelContext) private var modelContext
    @EnvironmentObject private var appState: AppState

    // MARK: - State

    @State private var selectedSegment: TranscriptSegment?
    @State private var transcriptionError: String?
    @State private var showingRenameSheet = false
    @State private var showingDeleteConfirmation = false
    @State private var showingCancelConfirmation = false
    @State private var newTitle = ""
    @StateObject private var transcriptionService = TranscriptionService.shared
    @StateObject private var playerService = AudioPlayerService.shared

    // MARK: - Body

    var body: some View {
        VStack(spacing: 0) {
            // Header with recording info
            recordingHeader

            Divider()

            // Transcript content
            if recording.transcriptionStatus == .completed {
                transcriptView
            } else if recording.transcriptionStatus == .inProgress {
                transcriptionProgressView
            } else {
                emptyTranscriptView
            }

            Divider()

            // Playback bar at bottom
            if recording.audioFileName != nil {
                playbackBar
            }
        }
        .frame(minWidth: 400)
        .onAppear {
            loadAudio()
        }
        .onChange(of: recording.id) { _, _ in
            loadAudio()
        }
        .onDisappear {
            playerService.stop()
        }
        .sheet(isPresented: $showingRenameSheet) {
            renameSheet
        }
        .alert("Delete Recording", isPresented: $showingDeleteConfirmation) {
            Button("Cancel", role: .cancel) {}
            Button("Delete", role: .destructive) {
                deleteRecording()
            }
        } message: {
            Text("Are you sure you want to delete this recording? It will be moved to the Recycle Bin.")
        }
        .alert("Cancel Transcription", isPresented: $showingCancelConfirmation) {
            Button("Continue", role: .cancel) {}
            Button("Cancel Transcription", role: .destructive) {
                transcriptionService.cancelTranscription()
                recording.transcriptionStatus = .pending
                try? modelContext.save()
            }
        } message: {
            Text("Are you sure you want to cancel the transcription in progress?")
        }
    }

    // MARK: - Toolbar Menu

    private var toolbarMenu: some View {
        Menu {
            // Rename
            Button {
                newTitle = recording.title
                showingRenameSheet = true
            } label: {
                Label("Rename", systemImage: "pencil")
            }

            // Favorite/Unfavorite
            Button {
                recording.isFavorite.toggle()
                try? modelContext.save()
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
                    recording.sourceType = .quick
                    try? modelContext.save()
                } label: {
                    HStack {
                        Label("Quick", systemImage: "bolt")
                        if recording.sourceType == .quick {
                            Image(systemName: "checkmark")
                        }
                    }
                }

                Button {
                    recording.sourceType = .recorded
                    try? modelContext.save()
                } label: {
                    HStack {
                        Label("Recorded", systemImage: "mic")
                        if recording.sourceType == .recorded {
                            Image(systemName: "checkmark")
                        }
                    }
                }

                Button {
                    recording.sourceType = .imported
                    try? modelContext.save()
                } label: {
                    HStack {
                        Label("Imported", systemImage: "square.and.arrow.down")
                        if recording.sourceType == .imported {
                            Image(systemName: "checkmark")
                        }
                    }
                }

                Button {
                    recording.sourceType = .meeting
                    try? modelContext.save()
                } label: {
                    HStack {
                        Label("Meeting", systemImage: "video")
                        if recording.sourceType == .meeting {
                            Image(systemName: "checkmark")
                        }
                    }
                }
            } label: {
                Label("Move to Category", systemImage: "folder")
            }

            Divider()

            // Start Transcription (if pending or failed)
            if recording.transcriptionStatus == .pending || recording.transcriptionStatus == .failed {
                Button {
                    startTranscription()
                } label: {
                    Label("Start Transcription", systemImage: "waveform")
                }
            }

            // Cancel Transcription (if in progress)
            if recording.transcriptionStatus == .inProgress {
                Button(role: .destructive) {
                    showingCancelConfirmation = true
                } label: {
                    Label("Cancel Transcription", systemImage: "xmark.circle")
                }
            }

            // Export options (if transcript available)
            if recording.transcriptionStatus == .completed && !recording.fullTranscript.isEmpty {
                Divider()

                Menu {
                    Button {
                        copyToClipboard()
                    } label: {
                        Label("Copy to Clipboard", systemImage: "doc.on.doc")
                    }

                    Divider()

                    Button {
                        exportTranscript(format: .txt)
                    } label: {
                        Label("Export as TXT", systemImage: "doc.text")
                    }

                    Button {
                        exportTranscript(format: .srt)
                    } label: {
                        Label("Export as SRT", systemImage: "captions.bubble")
                    }

                    Button {
                        exportTranscript(format: .vtt)
                    } label: {
                        Label("Export as VTT", systemImage: "play.rectangle")
                    }

                    Button {
                        exportTranscript(format: .json)
                    } label: {
                        Label("Export as JSON", systemImage: "curlybraces")
                    }
                } label: {
                    Label("Export Transcript", systemImage: "square.and.arrow.up")
                }
            }

            Divider()

            // Delete
            Button(role: .destructive) {
                showingDeleteConfirmation = true
            } label: {
                Label("Delete Recording", systemImage: "trash")
            }
        } label: {
            Image(systemName: "ellipsis.circle")
        }
    }

    // MARK: - Rename Sheet

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
                }
                .keyboardShortcut(.cancelAction)

                Button("Save") {
                    recording.title = newTitle
                    try? modelContext.save()
                    showingRenameSheet = false
                }
                .keyboardShortcut(.defaultAction)
                .disabled(newTitle.trimmingCharacters(in: .whitespaces).isEmpty)
            }
        }
        .padding(24)
        .frame(width: 400)
    }

    // MARK: - Audio Loading

    private func loadAudio() {
        guard let audioURL = recording.audioURL else { return }
        _ = playerService.load(url: audioURL)
    }

    // MARK: - Views

    private var recordingHeader: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text(recording.title)
                    .font(.title2)
                    .fontWeight(.semibold)

                if recording.isFavorite {
                    Image(systemName: "star.fill")
                        .foregroundStyle(.yellow)
                }

                Spacer()

                // Status badge
                HStack(spacing: 4) {
                    Image(systemName: recording.transcriptionStatus.icon)
                    Text(recording.transcriptionStatus.displayName)
                }
                .font(.caption)
                .padding(.horizontal, 8)
                .padding(.vertical, 4)
                .background(statusColor.opacity(0.2))
                .foregroundStyle(statusColor)
                .cornerRadius(4)

                // Menu button
                toolbarMenu
            }

            HStack(spacing: 16) {
                Label(recording.formattedDate, systemImage: "calendar")
                Label(recording.formattedDuration, systemImage: "clock")
                Label(recording.sourceType.displayName, systemImage: recording.sourceType.icon)
            }
            .font(.caption)
            .foregroundStyle(.secondary)
        }
        .padding()
    }

    private var statusColor: Color {
        switch recording.transcriptionStatus {
        case .pending: return .secondary
        case .inProgress: return .orange
        case .completed: return .green
        case .failed: return .red
        }
    }

    private var transcriptView: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 8) {
                if let segments = recording.segments?.sorted(by: { $0.startTime < $1.startTime }) {
                    ForEach(segments) { segment in
                        SegmentRow(
                            segment: segment,
                            isSelected: selectedSegment?.id == segment.id,
                            onTap: {
                                selectedSegment = segment
                                playerService.seek(to: segment.startTime)
                            }
                        )
                    }
                }
            }
            .padding()
        }
    }

    private var transcriptionProgressView: some View {
        VStack(spacing: 16) {
            ProgressView(value: transcriptionService.progress)
                .progressViewStyle(.linear)
                .frame(width: 200)

            Text(transcriptionService.statusMessage.isEmpty ? "Transcribing..." : transcriptionService.statusMessage)
                .font(.headline)

            Text("This may take a few moments depending on the recording length.")
                .font(.caption)
                .foregroundStyle(.secondary)

            if transcriptionService.isTranscribing {
                Button("Cancel") {
                    showingCancelConfirmation = true
                }
                .buttonStyle(.bordered)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private var emptyTranscriptView: some View {
        VStack(spacing: 16) {
            Image(systemName: "text.bubble")
                .font(.system(size: 48))
                .foregroundStyle(.secondary)

            Text("No transcript yet")
                .font(.headline)

            if let error = transcriptionError {
                Text(error)
                    .font(.caption)
                    .foregroundStyle(.red)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal)
            }

            if recording.transcriptionStatus == .pending {
                Button("Start Transcription") {
                    startTranscription()
                }
                .buttonStyle(.borderedProminent)
                .tint(.orange)
            } else if recording.transcriptionStatus == .failed {
                Button("Retry Transcription") {
                    startTranscription()
                }
                .buttonStyle(.borderedProminent)
                .tint(.orange)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    // MARK: - Transcription

    private func startTranscription() {
        guard let audioURL = recording.audioURL else {
            transcriptionError = "No audio file found"
            return
        }

        transcriptionError = nil
        recording.transcriptionStatus = .inProgress
        try? modelContext.save()

        Task {
            do {
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
                transcriptionError = error.localizedDescription
                try? modelContext.save()
            }
        }
    }

    // MARK: - Actions

    private func deleteRecording() {
        recording.isDeleted = true
        recording.deletedAt = Date()
        try? modelContext.save()
        appState.selectedRecording = nil
    }

    private var playbackBar: some View {
        HStack(spacing: 16) {
            // Skip backward button
            Button {
                playerService.skipBackward()
            } label: {
                Image(systemName: "gobackward.15")
                    .font(.title3)
            }
            .buttonStyle(.plain)
            .help("Skip back 15 seconds")

            // Play/Pause button
            Button {
                playerService.togglePlayPause()
            } label: {
                Image(systemName: playerService.isPlaying ? "pause.fill" : "play.fill")
                    .font(.title2)
            }
            .buttonStyle(.plain)

            // Skip forward button
            Button {
                playerService.skipForward()
            } label: {
                Image(systemName: "goforward.15")
                    .font(.title3)
            }
            .buttonStyle(.plain)
            .help("Skip forward 15 seconds")

            // Progress slider
            Slider(
                value: Binding(
                    get: { playerService.currentTime },
                    set: { playerService.seek(to: $0) }
                ),
                in: 0...max(playerService.duration, 0.1)
            )
            .frame(maxWidth: .infinity)

            // Time labels
            HStack(spacing: 4) {
                Text(formatTime(playerService.currentTime))
                Text("/")
                    .foregroundStyle(.secondary)
                Text(formatTime(playerService.duration))
            }
            .font(.caption.monospacedDigit())
            .foregroundStyle(.secondary)

            // Speed control
            Menu {
                ForEach(AppConstants.PlaybackSpeed.allCases) { speed in
                    Button {
                        playerService.setSpeed(speed)
                    } label: {
                        HStack {
                            Text(speed.displayName)
                            if playerService.playbackSpeed == speed {
                                Image(systemName: "checkmark")
                            }
                        }
                    }
                }
            } label: {
                Text(playerService.playbackSpeed.displayName)
                    .font(.caption)
                    .frame(width: 45)
            }
            .menuStyle(.borderlessButton)

            // Export button (quick access)
            Menu {
                Button("Copy to Clipboard") {
                    copyToClipboard()
                }
                Divider()
                Button("Export as TXT") {
                    exportTranscript(format: .txt)
                }
                Button("Export as SRT") {
                    exportTranscript(format: .srt)
                }
                Button("Export as VTT") {
                    exportTranscript(format: .vtt)
                }
                Button("Export as JSON") {
                    exportTranscript(format: .json)
                }
            } label: {
                Image(systemName: "square.and.arrow.up")
            }
            .menuStyle(.borderlessButton)
            .disabled(recording.transcriptionStatus != .completed)
        }
        .padding()
        .background(Color(NSColor.windowBackgroundColor))
    }

    // MARK: - Export

    private func copyToClipboard() {
        let text = recording.fullTranscript
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(text, forType: .string)
    }

    private func exportTranscript(format: AppConstants.ExportFormat) {
        guard let segments = recording.segments else { return }

        let content: String
        switch format {
        case .txt:
            content = recording.fullTranscript
        case .srt:
            content = ExportService.shared.exportToSRT(segments: segments.sorted { $0.startTime < $1.startTime })
        case .vtt:
            content = ExportService.shared.exportToVTT(segments: segments.sorted { $0.startTime < $1.startTime })
        case .json:
            content = ExportService.shared.exportToJSON(recording: recording, segments: segments.sorted { $0.startTime < $1.startTime })
        }

        // Show save panel
        let savePanel = NSSavePanel()
        savePanel.allowedContentTypes = [.plainText]
        savePanel.nameFieldStringValue = "\(recording.title).\(format.fileExtension)"

        if savePanel.runModal() == .OK, let url = savePanel.url {
            try? content.write(to: url, atomically: true, encoding: .utf8)
        }
    }

    // MARK: - Helpers

    private func formatTime(_ time: TimeInterval) -> String {
        let minutes = Int(time) / 60
        let seconds = Int(time) % 60
        return String(format: "%d:%02d", minutes, seconds)
    }
}

// MARK: - Segment Row

struct SegmentRow: View {
    let segment: TranscriptSegment
    let isSelected: Bool
    let onTap: () -> Void

    var body: some View {
        Button(action: onTap) {
            HStack(alignment: .top, spacing: 12) {
                // Timestamp
                Text(segment.formattedStartTime)
                    .font(.caption.monospacedDigit())
                    .foregroundStyle(.orange)
                    .frame(width: 40, alignment: .trailing)

                // Text content
                Text(segment.text)
                    .font(.body)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .multilineTextAlignment(.leading)
            }
            .padding(.vertical, 8)
            .padding(.horizontal, 12)
            .background(isSelected ? Color.orange.opacity(0.1) : Color.clear)
            .cornerRadius(8)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

// MARK: - Preview

#Preview {
    RecordingDetailView(recording: Recording(
        title: "Sample Recording",
        duration: 125,
        transcriptionStatus: .completed
    ))
    .environmentObject(AppState())
}
