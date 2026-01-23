import SwiftUI
import SwiftData
import AppKit

/// Home content view showing recordings list
struct HomeContentView: View {

    // MARK: - Environment

    @EnvironmentObject private var appState: AppState
    @Environment(\.modelContext) private var modelContext
    @Query(sort: \Recording.createdAt, order: .reverse) private var allRecordings: [Recording]

    // MARK: - State

    @State private var searchText = ""
    @State private var recordingToRename: Recording?
    @State private var newTitle = ""
    @State private var showingRenameSheet = false
    @State private var showingDeleteConfirmation = false
    @State private var recordingToDelete: Recording?

    // MARK: - Computed

    private var filteredRecordings: [Recording] {
        var recordings = allRecordings.filter { !$0.isDeleted }

        // Filter by category
        switch appState.selectedHomeCategory {
        case .quick:
            recordings = recordings.filter { $0.sourceType == .quick }
        case .recorded:
            recordings = recordings.filter { $0.sourceType == .recorded }
        case .imported:
            recordings = recordings.filter { $0.sourceType == .imported }
        case .meeting:
            recordings = recordings.filter { $0.sourceType == .meeting }
        }

        // Filter by search
        if !searchText.isEmpty {
            recordings = recordings.filter {
                $0.title.localizedCaseInsensitiveContains(searchText) ||
                $0.fullTranscript.localizedCaseInsensitiveContains(searchText)
            }
        }

        return recordings
    }

    // MARK: - Body

    var body: some View {
        VStack(spacing: 0) {
            // Recordings list
            if filteredRecordings.isEmpty {
                emptyStateView
            } else {
                recordingsList
            }
        }
        .searchable(text: $searchText, prompt: "Search recordings...")
        .navigationTitle(appState.selectedHomeCategory.rawValue)
        .sheet(isPresented: $showingRenameSheet) {
            renameSheet
        }
        .alert("Delete Recording", isPresented: $showingDeleteConfirmation) {
            Button("Cancel", role: .cancel) {
                recordingToDelete = nil
            }
            Button("Delete", role: .destructive) {
                if let recording = recordingToDelete {
                    deleteRecording(recording)
                }
                recordingToDelete = nil
            }
        } message: {
            Text("Are you sure you want to delete this recording? It will be moved to the Recycle Bin.")
        }
    }

    // MARK: - Views

    private var emptyStateView: some View {
        VStack(spacing: 16) {
            Image(systemName: appState.selectedHomeCategory.icon)
                .font(.system(size: 48))
                .foregroundStyle(.secondary)

            Text("No \(appState.selectedHomeCategory.rawValue.lowercased()) recordings")
                .font(.title3)
                .foregroundStyle(.secondary)

            if appState.selectedHomeCategory == .quick {
                Text("Hold Fn key to start a quick recording")
                    .font(.caption)
                    .foregroundStyle(.tertiary)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private var recordingsList: some View {
        List(selection: Binding(
            get: { appState.selectedRecording },
            set: { appState.selectedRecording = $0 }
        )) {
            ForEach(filteredRecordings) { recording in
                RecordingRowView(recording: recording)
                    .tag(recording)
                    .contextMenu {
                        recordingContextMenu(for: recording)
                    }
            }
            .onDelete(perform: deleteRecordings)
        }
        .listStyle(.inset)
    }

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
                HStack {
                    Label("Quick", systemImage: "bolt")
                    if recording.sourceType == .quick {
                        Image(systemName: "checkmark")
                    }
                }
            }

            Button {
                setSourceType(recording, to: .recorded)
            } label: {
                HStack {
                    Label("Recorded", systemImage: "mic")
                    if recording.sourceType == .recorded {
                        Image(systemName: "checkmark")
                    }
                }
            }

            Button {
                setSourceType(recording, to: .imported)
            } label: {
                HStack {
                    Label("Imported", systemImage: "square.and.arrow.down")
                    if recording.sourceType == .imported {
                        Image(systemName: "checkmark")
                    }
                }
            }

            Button {
                setSourceType(recording, to: .meeting)
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

        // Copy Transcript (if available)
        if recording.transcriptionStatus == .completed && !recording.fullTranscript.isEmpty {
            Button {
                copyTranscript(recording)
            } label: {
                Label("Copy Transcript", systemImage: "doc.on.doc")
            }
        }

        // Start Transcription (if pending)
        if recording.transcriptionStatus == .pending {
            Button {
                // Select the recording to open detail view where transcription can be started
                appState.selectedRecording = recording
            } label: {
                Label("Start Transcription", systemImage: "waveform")
            }
        }

        Divider()

        // Delete
        Button(role: .destructive) {
            recordingToDelete = recording
            showingDeleteConfirmation = true
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

    // MARK: - Actions

    private func deleteRecordings(at offsets: IndexSet) {
        for index in offsets {
            let recording = filteredRecordings[index]
            deleteRecording(recording)
        }
    }

    private func deleteRecording(_ recording: Recording) {
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
}

// MARK: - Recording Row View

struct RecordingRowView: View {
    let recording: Recording

    var body: some View {
        HStack(spacing: 12) {
            // Status icon
            Image(systemName: recording.transcriptionStatus.icon)
                .font(.title3)
                .foregroundStyle(statusColor)
                .frame(width: 24)

            // Recording info
            VStack(alignment: .leading, spacing: 4) {
                HStack {
                    Text(recording.title)
                        .font(.headline)
                        .lineLimit(1)

                    if recording.isFavorite {
                        Image(systemName: "star.fill")
                            .font(.caption)
                            .foregroundStyle(.yellow)
                    }
                }

                HStack(spacing: 8) {
                    Text(recording.formattedDate)
                    Text("·")
                    Text(recording.formattedDuration)

                    if !recording.fullTranscript.isEmpty {
                        Text("·")
                        Text(recording.fullTranscript.prefix(50) + "...")
                            .lineLimit(1)
                    }
                }
                .font(.caption)
                .foregroundStyle(.secondary)
            }

            Spacer()

            // Duration badge
            Text(recording.formattedDuration)
                .font(.caption)
                .padding(.horizontal, 8)
                .padding(.vertical, 4)
                .background(Color.secondary.opacity(0.1))
                .cornerRadius(4)
        }
        .padding(.vertical, 4)
    }

    private var statusColor: Color {
        switch recording.transcriptionStatus {
        case .pending: return .secondary
        case .inProgress: return .orange
        case .completed: return .green
        case .failed: return .red
        }
    }
}

// MARK: - Preview

#Preview {
    HomeContentView()
        .environmentObject(AppState())
}
