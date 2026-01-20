import Foundation
import UIKit

/// Service for exporting transcripts in various formats
struct ExportService {

    // MARK: - Public Methods

    /// Generate TXT content
    static func generateTXT(from segments: [TranscriptSegment]) -> String {
        segments
            .sorted { $0.startTime < $1.startTime }
            .map(\.text)
            .joined(separator: "\n\n")
    }

    /// Generate SRT content
    static func generateSRT(from segments: [TranscriptSegment]) -> String {
        segments
            .sorted { $0.startTime < $1.startTime }
            .enumerated()
            .map { index, segment in
                """
                \(index + 1)
                \(segment.srtStartTimestamp) --> \(segment.srtEndTimestamp)
                \(segment.text)
                """
            }
            .joined(separator: "\n\n")
    }

    /// Generate VTT content
    static func generateVTT(from segments: [TranscriptSegment]) -> String {
        let header = "WEBVTT\n\n"

        let cues = segments
            .sorted { $0.startTime < $1.startTime }
            .map { segment in
                """
                \(segment.vttStartTimestamp) --> \(segment.vttEndTimestamp)
                \(segment.text)
                """
            }
            .joined(separator: "\n\n")

        return header + cues
    }

    /// Generate content for any format
    static func generate(from recording: Recording, format: ExportFormat) -> String {
        let segments = recording.segments

        switch format {
        case .txt:
            return generateTXT(from: segments)
        case .srt:
            return generateSRT(from: segments)
        case .vtt:
            return generateVTT(from: segments)
        }
    }

    /// Export to file and return URL
    static func exportToFile(
        recording: Recording,
        format: ExportFormat
    ) throws -> URL {
        let content = generate(from: recording, format: format)

        // Sanitize filename - remove all invalid filesystem characters
        let sanitizedTitle = sanitizeFilename(recording.title)

        let fileName = "\(sanitizedTitle).\(format.fileExtension)"
        let tempURL = FileManager.default.temporaryDirectory.appendingPathComponent(fileName)

        try content.write(to: tempURL, atomically: true, encoding: .utf8)

        return tempURL
    }

    /// Copy transcript content to clipboard
    @MainActor
    static func copyToClipboard(recording: Recording, format: ExportFormat = .txt) {
        let content = generate(from: recording, format: format)
        UIPasteboard.general.string = content
    }

    /// Copy plain text transcript to clipboard
    @MainActor
    static func copyToClipboard(segments: [TranscriptSegment]) {
        let content = generateTXT(from: segments)
        UIPasteboard.general.string = content
    }

    /// Copy a single segment to clipboard
    @MainActor
    static func copyToClipboard(segment: TranscriptSegment) {
        UIPasteboard.general.string = segment.text
    }

    // MARK: - Private Helpers

    /// Sanitize a string to be safe for use as a filename
    /// Removes or replaces characters that are invalid on various filesystems
    private static func sanitizeFilename(_ filename: String) -> String {
        // Characters invalid on various filesystems:
        // Windows: \ / : * ? " < > |
        // macOS/iOS: / :
        // Common problematic: control characters, leading/trailing spaces/dots
        let invalidCharacters = CharacterSet(charactersIn: "/\\:*?\"<>|")
            .union(.controlCharacters)
            .union(.newlines)

        var sanitized = filename
            .components(separatedBy: invalidCharacters)
            .joined(separator: "-")

        // Remove leading/trailing whitespace and dots (problematic on some systems)
        sanitized = sanitized.trimmingCharacters(in: .whitespaces)
        sanitized = sanitized.trimmingCharacters(in: CharacterSet(charactersIn: "."))

        // Collapse multiple dashes into one
        while sanitized.contains("--") {
            sanitized = sanitized.replacingOccurrences(of: "--", with: "-")
        }

        // Ensure filename isn't empty after sanitization
        if sanitized.isEmpty {
            sanitized = "transcript"
        }

        // Limit filename length (some filesystems have limits, 255 is common)
        // Leave room for extension
        let maxLength = 200
        if sanitized.count > maxLength {
            sanitized = String(sanitized.prefix(maxLength))
        }

        return sanitized
    }
}
