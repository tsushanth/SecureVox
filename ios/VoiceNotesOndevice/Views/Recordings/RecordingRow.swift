import SwiftUI

/// Row component for displaying a recording in a list
struct RecordingRow: View {

    // MARK: - Properties

    let recording: Recording
    let isSelected: Bool
    let isSelectionMode: Bool
    let onToggleSelection: () -> Void

    // MARK: - Body

    var body: some View {
        HStack(spacing: 12) {
            // Selection checkbox in selection mode
            if isSelectionMode {
                Button {
                    onToggleSelection()
                } label: {
                    Image(systemName: isSelected ? "checkmark.circle.fill" : "circle")
                        .font(.title2)
                        .foregroundStyle(isSelected ? .blue : .secondary)
                }
                .buttonStyle(.plain)
            }

            // Source type icon
            Image(systemName: recording.sourceType.iconName)
                .font(.title2)
                .foregroundStyle(.secondary)
                .frame(width: 32)

            // Content
            VStack(alignment: .leading, spacing: 4) {
                // Title
                Text(recording.title)
                    .font(.headline)
                    .lineLimit(1)

                // Metadata row
                HStack(spacing: 8) {
                    // Date
                    Text(recording.createdAt.formatted(date: .abbreviated, time: .shortened))
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

    // MARK: - Subviews

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
    List {
        RecordingRow(
            recording: Recording(title: "Meeting Notes", duration: 3600),
            isSelected: false,
            isSelectionMode: false,
            onToggleSelection: {}
        )

        RecordingRow(
            recording: {
                let r = Recording(title: "Interview Recording", duration: 1800)
                r.status = .processing
                r.transcriptionProgress = 0.65
                return r
            }(),
            isSelected: true,
            isSelectionMode: true,
            onToggleSelection: {}
        )
    }
}
