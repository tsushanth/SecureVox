import SwiftUI

/// Sheet for exporting transcript in various formats
struct ExportSheet: View {

    // MARK: - Properties

    let recording: Recording

    // MARK: - Environment

    @Environment(\.dismiss) private var dismiss

    // MARK: - State

    @State private var selectedFormat: ExportFormat = .txt
    @State private var exportedFileURL: URL?
    @State private var showingShareSheet = false
    @State private var isExporting = false
    @State private var errorMessage: String?

    // MARK: - Body

    var body: some View {
        NavigationStack {
            List {
                Section {
                    ForEach(ExportFormat.allCases) { format in
                        ExportFormatRow(
                            format: format,
                            isSelected: selectedFormat == format,
                            onSelect: { selectedFormat = format }
                        )
                    }
                } header: {
                    Text("Export Format")
                } footer: {
                    Text(selectedFormat.formatDescription)
                }

                Section {
                    previewSection
                } header: {
                    Text("Preview")
                }
            }
            .navigationTitle("Export Transcript")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("Cancel") {
                        dismiss()
                    }
                }

                ToolbarItem(placement: .topBarTrailing) {
                    Button("Export") {
                        exportTranscript()
                    }
                    .fontWeight(.semibold)
                    .disabled(isExporting || recording.segments.isEmpty)
                }
            }
            .overlay {
                if isExporting {
                    ProgressView("Exporting...")
                        .padding()
                        .background(.regularMaterial)
                        .clipShape(RoundedRectangle(cornerRadius: 12))
                }
            }
            .sheet(isPresented: $showingShareSheet) {
                if let url = exportedFileURL {
                    ShareSheet(items: [url]) {
                        // Clean up temp file after sharing
                        try? FileManager.default.removeItem(at: url)
                        dismiss()
                    }
                }
            }
            .alert("Export Error", isPresented: .constant(errorMessage != nil)) {
                Button("OK") { errorMessage = nil }
            } message: {
                Text(errorMessage ?? "")
            }
        }
    }

    // MARK: - Subviews

    private var previewSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(generatePreview())
                .font(.system(.caption, design: .monospaced))
                .foregroundStyle(.secondary)
                .lineLimit(10)
        }
        .padding(.vertical, 8)
    }

    // MARK: - Methods

    private func generatePreview() -> String {
        let segments = recording.segments.sorted { $0.startTime < $1.startTime }
        let previewSegments = Array(segments.prefix(3))

        switch selectedFormat {
        case .txt:
            return previewSegments.map(\.text).joined(separator: "\n\n")

        case .srt:
            return previewSegments.enumerated().map { index, segment in
                """
                \(index + 1)
                \(segment.srtStartTimestamp) --> \(segment.srtEndTimestamp)
                \(segment.text)
                """
            }.joined(separator: "\n\n")

        case .vtt:
            let header = "WEBVTT\n\n"
            let cues = previewSegments.map { segment in
                """
                \(segment.vttStartTimestamp) --> \(segment.vttEndTimestamp)
                \(segment.text)
                """
            }.joined(separator: "\n\n")
            return header + cues
        }
    }

    private func exportTranscript() {
        isExporting = true

        Task {
            do {
                let content = generateFullContent()
                let fileName = "\(recording.title).\(selectedFormat.fileExtension)"
                let tempURL = FileManager.default.temporaryDirectory.appendingPathComponent(fileName)

                try content.write(to: tempURL, atomically: true, encoding: .utf8)

                await MainActor.run {
                    exportedFileURL = tempURL
                    isExporting = false
                    showingShareSheet = true
                }
            } catch {
                await MainActor.run {
                    errorMessage = "Failed to export: \(error.localizedDescription)"
                    isExporting = false
                }
            }
        }
    }

    private func generateFullContent() -> String {
        let segments = recording.segments.sorted { $0.startTime < $1.startTime }

        switch selectedFormat {
        case .txt:
            return segments.map(\.text).joined(separator: "\n\n")

        case .srt:
            return segments.enumerated().map { index, segment in
                """
                \(index + 1)
                \(segment.srtStartTimestamp) --> \(segment.srtEndTimestamp)
                \(segment.text)
                """
            }.joined(separator: "\n\n")

        case .vtt:
            let header = "WEBVTT\n\n"
            let cues = segments.map { segment in
                """
                \(segment.vttStartTimestamp) --> \(segment.vttEndTimestamp)
                \(segment.text)
                """
            }.joined(separator: "\n\n")
            return header + cues
        }
    }
}

// MARK: - Export Format Row

private struct ExportFormatRow: View {
    let format: ExportFormat
    let isSelected: Bool
    let onSelect: () -> Void

    var body: some View {
        Button {
            onSelect()
        } label: {
            HStack {
                VStack(alignment: .leading, spacing: 4) {
                    Text(format.displayName)
                        .foregroundStyle(.primary)
                    Text(format.shortName)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }

                Spacer()

                if isSelected {
                    Image(systemName: "checkmark")
                        .foregroundStyle(.blue)
                }
            }
        }
    }
}

// MARK: - Share Sheet

#if os(iOS)
struct ShareSheet: UIViewControllerRepresentable {
    let items: [Any]
    let onDismiss: () -> Void

    func makeUIViewController(context: Context) -> UIActivityViewController {
        let controller = UIActivityViewController(activityItems: items, applicationActivities: nil)
        controller.completionWithItemsHandler = { _, _, _, _ in
            onDismiss()
        }
        return controller
    }

    func updateUIViewController(_ uiViewController: UIActivityViewController, context: Context) {}
}
#elseif os(macOS)
import AppKit

struct ShareSheet: View {
    let items: [Any]
    let onDismiss: () -> Void

    var body: some View {
        VStack(spacing: 20) {
            Text("Share")
                .font(.headline)

            if let url = items.first as? URL {
                Text(url.lastPathComponent)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)

                HStack(spacing: 16) {
                    Button("Show in Finder") {
                        NSWorkspace.shared.activateFileViewerSelecting([url])
                        onDismiss()
                    }
                    .buttonStyle(.bordered)

                    Button("Copy Path") {
                        NSPasteboard.general.clearContents()
                        NSPasteboard.general.setString(url.path, forType: .string)
                        onDismiss()
                    }
                    .buttonStyle(.bordered)

                    Button("Share...") {
                        let picker = NSSharingServicePicker(items: items)
                        if let window = NSApp.keyWindow,
                           let contentView = window.contentView {
                            picker.show(relativeTo: .zero, of: contentView, preferredEdge: .minY)
                        }
                    }
                    .buttonStyle(.borderedProminent)
                }
            }

            Button("Done") {
                onDismiss()
            }
            .keyboardShortcut(.defaultAction)
        }
        .padding(30)
        .frame(minWidth: 400)
    }
}
#endif

// MARK: - Preview

#Preview {
    let recording = Recording(title: "Test Recording", duration: 120)
    recording.segments = [
        TranscriptSegment(startTime: 0, endTime: 5, text: "Hello, this is a test."),
        TranscriptSegment(startTime: 5, endTime: 10, text: "This is the second segment."),
        TranscriptSegment(startTime: 10, endTime: 15, text: "And this is the third one.")
    ]

    return ExportSheet(recording: recording)
}
