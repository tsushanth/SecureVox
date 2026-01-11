import Foundation

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

        // Sanitize filename
        let sanitizedTitle = recording.title
            .replacingOccurrences(of: "/", with: "-")
            .replacingOccurrences(of: ":", with: "-")

        let fileName = "\(sanitizedTitle).\(format.fileExtension)"
        let tempURL = FileManager.default.temporaryDirectory.appendingPathComponent(fileName)

        try content.write(to: tempURL, atomically: true, encoding: .utf8)

        return tempURL
    }
}
