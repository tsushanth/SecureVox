import SwiftUI
import SwiftData
import os.log
#if os(iOS)
import UIKit
#elseif os(macOS)
import AppKit
#endif

private let listViewLogger = os.Logger(subsystem: "com.voicenotes.ondevice", category: "RecordingsListView")

/// Main library screen showing all recordings with a prominent record button
struct RecordingsListView: View {

    // MARK: - Environment

    @Environment(\.modelContext) private var modelContext
    @EnvironmentObject private var deepLinkHandler: DeepLinkHandler

    // MARK: - SwiftData Query

    @Query(
        filter: #Predicate<Recording> { $0.deletedAt == nil },
        sort: \Recording.createdAt,
        order: .reverse
    )
    private var recordings: [Recording]

    // MARK: - State

    @State private var searchText = ""
    @State private var selectedRecording: Recording?
    @State private var showingRecordingSheet = false
    @State private var showingImportSheet = false
    @State private var showingDeleteConfirmation = false
    @State private var recordingToDelete: Recording?
    @State private var errorMessage: String?

    // MARK: - Computed Properties

    private var filteredRecordings: [Recording] {
        if searchText.isEmpty {
            return recordings
        }
        return recordings.filter {
            $0.title.localizedCaseInsensitiveContains(searchText) ||
            $0.fullTranscript.localizedCaseInsensitiveContains(searchText)
        }
    }

    // MARK: - Body

    var body: some View {
        NavigationStack {
            ZStack(alignment: .bottom) {
                // Main content
                Group {
                    if recordings.isEmpty && searchText.isEmpty {
                        emptyStateView
                    } else {
                        recordingsList
                    }
                }

                // Floating record button
                recordButton
                    .padding(.bottom, 24)
            }
            .navigationTitle("Recordings")
            .searchable(
                text: $searchText,
                prompt: "Search recordings"
            )
            .toolbar {
                ToolbarItemGroup(placement: .topBarTrailing) {
                    Menu {
                        Button {
                            showingImportSheet = true
                        } label: {
                            Label("Import Media", systemImage: "square.and.arrow.down")
                        }
                    } label: {
                        Image(systemName: "plus")
                    }
                }
            }
            .sheet(isPresented: $showingRecordingSheet) {
                RecorderSheet { _ in
                    // Recording saved via SwiftData, list updates automatically
                }
            }
            .sheet(isPresented: $showingImportSheet) {
                ImportMenuView { _ in
                    // Import saved via SwiftData, list updates automatically
                }
            }
            .navigationDestination(for: Recording.self) { recording in
                RecordingDetailView(recording: recording, modelContext: modelContext)
            }
            .alert("Error", isPresented: .constant(errorMessage != nil)) {
                Button("OK") { errorMessage = nil }
            } message: {
                Text(errorMessage ?? "")
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
                Text("Are you sure you want to delete this recording? This cannot be undone.")
            }
            .onChange(of: recordings.count) { oldCount, newCount in
                listViewLogger.debug("Count changed from \(oldCount) to \(newCount)")
            }
            .onAppear {
                listViewLogger.debug("View appeared, recordings count: \(recordings.count)")
                // Check if we should start recording from widget deep link
                if deepLinkHandler.shouldStartRecording {
                    deepLinkHandler.shouldStartRecording = false
                    showingRecordingSheet = true
                }
            }
            .onChange(of: deepLinkHandler.shouldStartRecording) { _, shouldStart in
                if shouldStart {
                    deepLinkHandler.shouldStartRecording = false
                    showingRecordingSheet = true
                }
            }
        }
    }

    // MARK: - Subviews

    private var emptyStateView: some View {
        ContentUnavailableView {
            Label("No Recordings", systemImage: "waveform")
        } description: {
            Text("Tap the record button to create a new recording, or import audio/video files.")
        } actions: {
            Button {
                showingImportSheet = true
            } label: {
                Label("Import Media", systemImage: "square.and.arrow.down")
            }
            .buttonStyle(.bordered)
        }
    }

    private var recordingsList: some View {
        List {
            ForEach(filteredRecordings) { recording in
                NavigationLink(value: recording) {
                    RecordingRowView(recording: recording)
                }
                .swipeActions(edge: .trailing, allowsFullSwipe: true) {
                    Button(role: .destructive) {
                        recordingToDelete = recording
                        showingDeleteConfirmation = true
                    } label: {
                        Label("Delete", systemImage: "trash")
                    }
                }
                .swipeActions(edge: .leading) {
                    Button {
                        toggleFavorite(recording)
                    } label: {
                        Label(
                            recording.isFavorite ? "Unfavorite" : "Favorite",
                            systemImage: recording.isFavorite ? "star.slash" : "star.fill"
                        )
                    }
                    .tint(.yellow)
                }
                .contextMenu {
                    Button {
                        toggleFavorite(recording)
                    } label: {
                        Label(
                            recording.isFavorite ? "Remove from Favorites" : "Add to Favorites",
                            systemImage: recording.isFavorite ? "star.slash" : "star"
                        )
                    }

                    if recording.status == .completed {
                        Button {
                            copyTranscript(recording)
                        } label: {
                            Label("Copy Transcript", systemImage: "doc.on.doc")
                        }
                    }

                    Divider()

                    Button(role: .destructive) {
                        recordingToDelete = recording
                        showingDeleteConfirmation = true
                    } label: {
                        Label("Delete", systemImage: "trash")
                    }
                }
            }

            // Spacer for record button
            Color.clear
                .frame(height: 100)
                .listRowBackground(Color.clear)
                .listRowSeparator(.hidden)
        }
        .listStyle(.plain)
    }

    private var recordButton: some View {
        Button {
            showingRecordingSheet = true
        } label: {
            ZStack {
                Circle()
                    .fill(Color.blue)
                    .frame(width: 72, height: 72)
                    .shadow(color: .black.opacity(0.2), radius: 8, y: 4)

                Circle()
                    .fill(Color.white)
                    .frame(width: 28, height: 28)
            }
        }
        .buttonStyle(.plain)
    }

    // MARK: - Actions

    private func deleteRecording(_ recording: Recording) {
        let retentionDays = UserDefaults.standard.integer(forKey: AppConstants.UserDefaultsKeys.recycleBinRetentionDays)

        if retentionDays > 0 {
            // Move to recycle bin
            recording.deletedAt = Date()
            recording.updatedAt = Date()
        } else {
            // Permanent deletion (recycle bin disabled)
            if let audioURL = recording.audioFileURL {
                try? FileManager.default.removeItem(at: audioURL)
            }
            modelContext.delete(recording)
        }

        do {
            try modelContext.save()
        } catch {
            errorMessage = "Failed to delete recording: \(error.localizedDescription)"
        }

        // Provide haptic feedback
        FeedbackService.shared.triggerLightHaptic()
    }

    private func toggleFavorite(_ recording: Recording) {
        recording.isFavorite.toggle()
        recording.updatedAt = Date()

        do {
            try modelContext.save()
        } catch {
            errorMessage = "Failed to update recording: \(error.localizedDescription)"
        }

        // Provide haptic feedback
        FeedbackService.shared.triggerSelectionHaptic()
    }

    private func copyTranscript(_ recording: Recording) {
        let transcript = recording.fullTranscript
        guard !transcript.isEmpty else { return }

        #if os(iOS)
        UIPasteboard.general.string = transcript
        #elseif os(macOS)
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(transcript, forType: .string)
        #endif

        // Provide haptic feedback
        FeedbackService.shared.triggerLightHaptic()
    }
}

// MARK: - Recording Row View

struct RecordingRowView: View {
    let recording: Recording

    var body: some View {
        HStack(spacing: 12) {
            // Source icon with favorite indicator
            ZStack(alignment: .topTrailing) {
                Image(systemName: recording.sourceType == .microphone ? "mic.fill" : "square.and.arrow.down")
                    .font(.title2)
                    .foregroundStyle(.secondary)
                    .frame(width: 32)

                if recording.isFavorite {
                    Image(systemName: "star.fill")
                        .font(.system(size: 10))
                        .foregroundStyle(.yellow)
                        .offset(x: 6, y: -4)
                }
            }

            // Content
            VStack(alignment: .leading, spacing: 4) {
                // Title
                Text(recording.title)
                    .font(.headline)
                    .lineLimit(1)

                // Metadata row
                HStack(spacing: 8) {
                    // Date
                    Text(formattedDate)
                        .font(.caption)
                        .foregroundStyle(.secondary)

                    Text("·")
                        .foregroundStyle(.secondary)

                    // Duration
                    Text(recording.formattedDuration)
                        .font(.caption)
                        .foregroundStyle(.secondary)

                    // Status indicator
                    statusView
                }

                // Preview text or progress
                contentPreview
            }

            Spacer()
        }
        .padding(.vertical, 4)
    }

    private var formattedDate: String {
        let formatter = DateFormatter()
        let calendar = Calendar.current

        if calendar.isDateInToday(recording.createdAt) {
            formatter.dateFormat = "h:mm a"
            return "Today, \(formatter.string(from: recording.createdAt))"
        } else if calendar.isDateInYesterday(recording.createdAt) {
            formatter.dateFormat = "h:mm a"
            return "Yesterday, \(formatter.string(from: recording.createdAt))"
        } else {
            formatter.dateStyle = .medium
            formatter.timeStyle = .short
            return formatter.string(from: recording.createdAt)
        }
    }

    @ViewBuilder
    private var statusView: some View {
        switch recording.status {
        case .pending:
            Label("Waiting", systemImage: "clock")
                .font(.caption)
                .foregroundStyle(.secondary)

        case .processing:
            Label("Transcribing", systemImage: "arrow.triangle.2.circlepath")
                .font(.caption)
                .foregroundStyle(.blue)

        case .completed:
            Label("Done", systemImage: "checkmark.circle.fill")
                .font(.caption)
                .foregroundStyle(.green)

        case .failed:
            Label("Failed", systemImage: "exclamationmark.triangle.fill")
                .font(.caption)
                .foregroundStyle(.red)
        }
    }

    @ViewBuilder
    private var contentPreview: some View {
        switch recording.status {
        case .pending:
            Text("Tap to transcribe")
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .italic()

        case .processing:
            ProgressView(value: recording.transcriptionProgress)
                .progressViewStyle(.linear)
                .frame(maxWidth: 200)

        case .completed:
            if !recording.fullTranscript.isEmpty {
                Text(recording.fullTranscript)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .lineLimit(2)
            }

        case .failed:
            Text(recording.errorMessage ?? "Transcription failed")
                .font(.subheadline)
                .foregroundStyle(.red)
                .lineLimit(1)
        }
    }
}

// MARK: - Preview

#Preview {
    RecordingsListView()
        .modelContainer(for: Recording.self, inMemory: true)
}
