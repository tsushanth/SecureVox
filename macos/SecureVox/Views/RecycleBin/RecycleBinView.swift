import SwiftUI
import SwiftData

/// View for managing deleted recordings in the recycle bin
struct RecycleBinView: View {

    // MARK: - Environment

    @Environment(\.modelContext) private var modelContext
    @Query(
        filter: #Predicate<Recording> { $0.isDeleted },
        sort: \Recording.deletedAt,
        order: .reverse
    ) private var deletedRecordings: [Recording]

    // MARK: - State

    @State private var selectedRecordings: Set<UUID> = []
    @State private var showEmptyConfirmation = false
    @State private var showRestoreConfirmation = false
    @State private var showDeleteConfirmation = false
    @State private var recordingToDelete: Recording?

    // MARK: - Services

    private let recycleBinService = RecycleBinService.shared

    // MARK: - Body

    var body: some View {
        VStack(spacing: 0) {
            // Header
            header

            Divider()

            // Content
            if deletedRecordings.isEmpty {
                emptyState
            } else {
                recordingsList
            }
        }
        .navigationTitle("Recycle Bin")
        .frame(minWidth: 500)
        .confirmationDialog(
            "Empty Recycle Bin?",
            isPresented: $showEmptyConfirmation,
            titleVisibility: .visible
        ) {
            Button("Empty Recycle Bin", role: .destructive) {
                emptyRecycleBin()
            }
        } message: {
            Text("This will permanently delete \(deletedRecordings.count) recording(s). This action cannot be undone.")
        }
        .confirmationDialog(
            "Restore Recordings?",
            isPresented: $showRestoreConfirmation,
            titleVisibility: .visible
        ) {
            Button("Restore \(selectedRecordings.count) Recording(s)") {
                restoreSelected()
            }
        } message: {
            Text("The selected recordings will be restored to your library.")
        }
        .confirmationDialog(
            "Delete Permanently?",
            isPresented: $showDeleteConfirmation,
            titleVisibility: .visible
        ) {
            Button("Delete Permanently", role: .destructive) {
                if let recording = recordingToDelete {
                    permanentlyDelete(recording)
                }
            }
        } message: {
            Text("This recording will be permanently deleted. This action cannot be undone.")
        }
    }

    // MARK: - Views

    private var header: some View {
        HStack {
            VStack(alignment: .leading, spacing: 4) {
                Text("Recycle Bin")
                    .font(.title2)
                    .fontWeight(.semibold)

                Text("\(deletedRecordings.count) deleted recording(s)")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            Spacer()

            if !deletedRecordings.isEmpty {
                HStack(spacing: 12) {
                    // Restore selected
                    if !selectedRecordings.isEmpty {
                        Button {
                            showRestoreConfirmation = true
                        } label: {
                            Label("Restore Selected", systemImage: "arrow.uturn.backward")
                        }
                        .buttonStyle(.bordered)
                    }

                    // Empty recycle bin
                    Button {
                        showEmptyConfirmation = true
                    } label: {
                        Label("Empty Recycle Bin", systemImage: "trash")
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(.red)
                }
            }
        }
        .padding()
    }

    private var emptyState: some View {
        VStack(spacing: 16) {
            Image(systemName: "trash")
                .font(.system(size: 48))
                .foregroundStyle(.secondary)

            Text("Recycle Bin is Empty")
                .font(.title3)
                .fontWeight(.medium)

            Text("Deleted recordings will appear here for \(recycleBinService.retentionDays) days before being permanently removed.")
                .font(.caption)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .frame(maxWidth: 300)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private var recordingsList: some View {
        List(selection: $selectedRecordings) {
            ForEach(deletedRecordings) { recording in
                RecycleBinRow(
                    recording: recording,
                    daysRemaining: recycleBinService.formattedDaysRemaining(deletedAt: recording.deletedAt),
                    onRestore: { restoreRecording(recording) },
                    onDelete: {
                        recordingToDelete = recording
                        showDeleteConfirmation = true
                    }
                )
                .tag(recording.id)
            }
        }
        .listStyle(.inset)
    }

    // MARK: - Actions

    private func restoreRecording(_ recording: Recording) {
        recording.isDeleted = false
        recording.deletedAt = nil
        try? modelContext.save()
    }

    private func restoreSelected() {
        for id in selectedRecordings {
            if let recording = deletedRecordings.first(where: { $0.id == id }) {
                recording.isDeleted = false
                recording.deletedAt = nil
            }
        }
        selectedRecordings.removeAll()
        try? modelContext.save()
    }

    private func permanentlyDelete(_ recording: Recording) {
        // Delete audio file
        if let audioURL = recording.audioURL {
            try? FileManager.default.removeItem(at: audioURL)
        }

        // Delete from database
        modelContext.delete(recording)
        try? modelContext.save()
        recordingToDelete = nil
    }

    private func emptyRecycleBin() {
        for recording in deletedRecordings {
            // Delete audio file
            if let audioURL = recording.audioURL {
                try? FileManager.default.removeItem(at: audioURL)
            }

            // Delete from database
            modelContext.delete(recording)
        }
        try? modelContext.save()
    }
}

// MARK: - Recycle Bin Row

struct RecycleBinRow: View {
    let recording: Recording
    let daysRemaining: String
    let onRestore: () -> Void
    let onDelete: () -> Void

    @State private var isHovered = false

    var body: some View {
        HStack(spacing: 12) {
            // Icon
            Image(systemName: recording.sourceType.icon)
                .font(.title2)
                .foregroundStyle(.secondary)
                .frame(width: 32)

            // Info
            VStack(alignment: .leading, spacing: 4) {
                Text(recording.title)
                    .font(.headline)
                    .lineLimit(1)

                HStack(spacing: 8) {
                    // Deleted date
                    if let deletedAt = recording.deletedAt {
                        Text("Deleted \(deletedAt.formatted(date: .abbreviated, time: .shortened))")
                    }

                    Text("·")

                    // Days remaining
                    Text(daysRemaining)
                        .foregroundStyle(daysRemaining.contains("today") ? .red : .secondary)
                }
                .font(.caption)
                .foregroundStyle(.secondary)
            }

            Spacer()

            // Duration
            Text(recording.formattedDuration)
                .font(.caption.monospacedDigit())
                .padding(.horizontal, 8)
                .padding(.vertical, 4)
                .background(Color.secondary.opacity(0.1))
                .cornerRadius(4)

            // Actions (shown on hover)
            if isHovered {
                HStack(spacing: 8) {
                    Button {
                        onRestore()
                    } label: {
                        Image(systemName: "arrow.uturn.backward")
                    }
                    .buttonStyle(.borderless)
                    .help("Restore")

                    Button {
                        onDelete()
                    } label: {
                        Image(systemName: "trash")
                            .foregroundStyle(.red)
                    }
                    .buttonStyle(.borderless)
                    .help("Delete Permanently")
                }
            }
        }
        .padding(.vertical, 4)
        .contentShape(Rectangle())
        .onHover { hovering in
            isHovered = hovering
        }
    }
}

// MARK: - Preview

#Preview {
    RecycleBinView()
}
