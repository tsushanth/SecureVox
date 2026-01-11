import SwiftUI
import SwiftData

/// View showing deleted recordings that can be restored or permanently deleted
struct RecycleBinView: View {

    // MARK: - Environment

    @Environment(\.modelContext) private var modelContext

    // MARK: - SwiftData Query

    @Query(
        filter: #Predicate<Recording> { $0.deletedAt != nil },
        sort: \Recording.deletedAt,
        order: .reverse
    )
    private var deletedRecordings: [Recording]

    // MARK: - State

    @State private var showingEmptyConfirmation = false
    @State private var recordingToDelete: Recording?
    @State private var showingDeleteConfirmation = false
    @State private var errorMessage: String?

    // MARK: - Body

    var body: some View {
        Group {
            if deletedRecordings.isEmpty {
                emptyStateView
            } else {
                recordingsList
            }
        }
        .navigationTitle("Recently Deleted")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if !deletedRecordings.isEmpty {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Empty", role: .destructive) {
                        showingEmptyConfirmation = true
                    }
                }
            }
        }
        .confirmationDialog(
            "Empty Recycle Bin?",
            isPresented: $showingEmptyConfirmation,
            titleVisibility: .visible
        ) {
            Button("Delete All Permanently", role: .destructive) {
                emptyRecycleBin()
            }
            Button("Cancel", role: .cancel) { }
        } message: {
            Text("This will permanently delete \(deletedRecordings.count) recording(s). This cannot be undone.")
        }
        .confirmationDialog(
            "Delete Permanently?",
            isPresented: $showingDeleteConfirmation,
            titleVisibility: .visible
        ) {
            Button("Delete Permanently", role: .destructive) {
                if let recording = recordingToDelete {
                    permanentlyDelete(recording)
                }
                recordingToDelete = nil
            }
            Button("Cancel", role: .cancel) {
                recordingToDelete = nil
            }
        } message: {
            Text("This recording will be permanently deleted. This cannot be undone.")
        }
        .alert("Error", isPresented: .constant(errorMessage != nil)) {
            Button("OK") { errorMessage = nil }
        } message: {
            Text(errorMessage ?? "")
        }
    }

    // MARK: - Subviews

    private var emptyStateView: some View {
        ContentUnavailableView {
            Label("No Deleted Recordings", systemImage: "trash.slash")
        } description: {
            Text("Recordings you delete will appear here and can be restored.")
        }
    }

    private var recordingsList: some View {
        List {
            ForEach(deletedRecordings) { recording in
                DeletedRecordingRow(recording: recording)
                    .swipeActions(edge: .leading) {
                        Button {
                            restoreRecording(recording)
                        } label: {
                            Label("Restore", systemImage: "arrow.uturn.backward")
                        }
                        .tint(.green)
                    }
                    .swipeActions(edge: .trailing, allowsFullSwipe: true) {
                        Button(role: .destructive) {
                            recordingToDelete = recording
                            showingDeleteConfirmation = true
                        } label: {
                            Label("Delete", systemImage: "trash")
                        }
                    }
                    .contextMenu {
                        Button {
                            restoreRecording(recording)
                        } label: {
                            Label("Restore", systemImage: "arrow.uturn.backward")
                        }

                        Divider()

                        Button(role: .destructive) {
                            recordingToDelete = recording
                            showingDeleteConfirmation = true
                        } label: {
                            Label("Delete Permanently", systemImage: "trash")
                        }
                    }
            }
        }
        .listStyle(.plain)
    }

    // MARK: - Actions

    private func restoreRecording(_ recording: Recording) {
        recording.deletedAt = nil
        recording.updatedAt = Date()

        do {
            try modelContext.save()
        } catch {
            errorMessage = "Failed to restore recording: \(error.localizedDescription)"
        }
    }

    private func permanentlyDelete(_ recording: Recording) {
        // Delete audio file if exists
        if let audioURL = recording.audioFileURL {
            try? FileManager.default.removeItem(at: audioURL)
        }

        // Delete from SwiftData
        modelContext.delete(recording)

        do {
            try modelContext.save()
        } catch {
            errorMessage = "Failed to delete recording: \(error.localizedDescription)"
        }
    }

    private func emptyRecycleBin() {
        for recording in deletedRecordings {
            // Delete audio file if exists
            if let audioURL = recording.audioFileURL {
                try? FileManager.default.removeItem(at: audioURL)
            }

            // Delete from SwiftData
            modelContext.delete(recording)
        }

        do {
            try modelContext.save()
        } catch {
            errorMessage = "Failed to empty recycle bin: \(error.localizedDescription)"
        }
    }
}

// MARK: - Deleted Recording Row

private struct DeletedRecordingRow: View {
    let recording: Recording

    var body: some View {
        HStack(spacing: 12) {
            // Icon
            Image(systemName: "waveform")
                .font(.title2)
                .foregroundStyle(.secondary)
                .frame(width: 32)

            // Content
            VStack(alignment: .leading, spacing: 4) {
                Text(recording.title)
                    .font(.headline)
                    .lineLimit(1)

                HStack(spacing: 8) {
                    // Deletion date
                    if let deletedAt = recording.deletedAt {
                        Text(formattedDeletedDate(deletedAt))
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }

                    // Days remaining
                    if let daysRemaining = recording.daysUntilPermanentDeletion {
                        Text("·")
                            .foregroundStyle(.secondary)

                        if daysRemaining == 0 {
                            Text("Expires today")
                                .font(.caption)
                                .foregroundStyle(.red)
                        } else if daysRemaining == 1 {
                            Text("1 day left")
                                .font(.caption)
                                .foregroundStyle(.orange)
                        } else {
                            Text("\(daysRemaining) days left")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }
                }

                // Duration
                Text(recording.formattedDuration)
                    .font(.subheadline)
                    .foregroundStyle(.tertiary)
            }

            Spacer()
        }
        .padding(.vertical, 4)
    }

    private func formattedDeletedDate(_ date: Date) -> String {
        let formatter = DateFormatter()
        let calendar = Calendar.current

        if calendar.isDateInToday(date) {
            formatter.dateFormat = "h:mm a"
            return "Deleted today at \(formatter.string(from: date))"
        } else if calendar.isDateInYesterday(date) {
            return "Deleted yesterday"
        } else {
            formatter.dateStyle = .medium
            return "Deleted \(formatter.string(from: date))"
        }
    }
}

// MARK: - Preview

#Preview {
    NavigationStack {
        RecycleBinView()
    }
    .modelContainer(for: Recording.self, inMemory: true)
}
