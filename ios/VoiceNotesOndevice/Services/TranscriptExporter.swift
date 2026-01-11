import Foundation

/// Exports transcript segments to various file formats
final class TranscriptExporter {

    // MARK: - Types

    enum ExportError: LocalizedError {
        case noSegments
        case writeFailed(Error)
        case invalidFileName

        var errorDescription: String? {
            switch self {
            case .noSegments:
                return "No transcript segments to export"
            case .writeFailed(let error):
                return "Failed to write file: \(error.localizedDescription)"
            case .invalidFileName:
                return "Invalid file name"
            }
        }
    }

    struct ExportResult {
        let url: URL
        let format: ExportFormat
        let content: String
    }

    // MARK: - Singleton

    static let shared = TranscriptExporter()

    private init() {}

    // MARK: - Content Generation

    /// Generate plain text content from segments
    /// - Parameter segments: Array of transcript segments
    /// - Returns: Plain text string with paragraphs
    func makeTXT(segments: [TranscriptSegment]) -> String {
        guard !segments.isEmpty else { return "" }

        return segments
            .sorted { $0.startTime < $1.startTime }
            .map { $0.text.trimmingCharacters(in: .whitespaces) }
            .filter { !$0.isEmpty }
            .joined(separator: "\n\n")
    }

    /// Generate SRT (SubRip Subtitle) content from segments
    /// - Parameter segments: Array of transcript segments
    /// - Returns: SRT formatted string
    ///
    /// SRT Format:
    /// ```
    /// 1
    /// 00:00:00,000 --> 00:00:05,000
    /// First subtitle text
    ///
    /// 2
    /// 00:00:05,000 --> 00:00:10,000
    /// Second subtitle text
    /// ```
    func makeSRT(segments: [TranscriptSegment]) -> String {
        guard !segments.isEmpty else { return "" }

        return segments
            .sorted { $0.startTime < $1.startTime }
            .enumerated()
            .map { index, segment in
                let startTimestamp = formatSRTTimestamp(segment.startTime)
                let endTimestamp = formatSRTTimestamp(segment.endTime)
                let text = segment.text.trimmingCharacters(in: .whitespaces)

                return """
                \(index + 1)
                \(startTimestamp) --> \(endTimestamp)
                \(text)
                """
            }
            .joined(separator: "\n\n")
    }

    /// Generate WebVTT content from segments
    /// - Parameter segments: Array of transcript segments
    /// - Returns: VTT formatted string
    ///
    /// VTT Format:
    /// ```
    /// WEBVTT
    ///
    /// 00:00:00.000 --> 00:00:05.000
    /// First subtitle text
    ///
    /// 00:00:05.000 --> 00:00:10.000
    /// Second subtitle text
    /// ```
    func makeVTT(segments: [TranscriptSegment]) -> String {
        guard !segments.isEmpty else { return "WEBVTT\n" }

        let header = "WEBVTT\n\n"

        let cues = segments
            .sorted { $0.startTime < $1.startTime }
            .map { segment in
                let startTimestamp = formatVTTTimestamp(segment.startTime)
                let endTimestamp = formatVTTTimestamp(segment.endTime)
                let text = segment.text.trimmingCharacters(in: .whitespaces)

                return """
                \(startTimestamp) --> \(endTimestamp)
                \(text)
                """
            }
            .joined(separator: "\n\n")

        return header + cues
    }

    // MARK: - File Export

    /// Export segments to a file in the specified format
    /// - Parameters:
    ///   - segments: Array of transcript segments
    ///   - format: Export format (TXT, SRT, VTT)
    ///   - fileName: Base file name (without extension)
    /// - Returns: ExportResult with file URL and content
    func export(
        segments: [TranscriptSegment],
        format: ExportFormat,
        fileName: String
    ) throws -> ExportResult {
        guard !segments.isEmpty else {
            throw ExportError.noSegments
        }

        let content: String
        switch format {
        case .txt:
            content = makeTXT(segments: segments)
        case .srt:
            content = makeSRT(segments: segments)
        case .vtt:
            content = makeVTT(segments: segments)
        }

        let url = try writeToTemporaryFile(
            content: content,
            fileName: fileName,
            extension: format.fileExtension
        )

        return ExportResult(url: url, format: format, content: content)
    }

    /// Export a recording's transcript to a file
    /// - Parameters:
    ///   - recording: Recording with transcript segments
    ///   - format: Export format
    /// - Returns: URL to the exported file
    func export(recording: Recording, format: ExportFormat) throws -> URL {
        let result = try export(
            segments: recording.segments,
            format: format,
            fileName: recording.title
        )
        return result.url
    }

    /// Export segments and return the file URL for sharing
    /// - Parameters:
    ///   - segments: Array of transcript segments
    ///   - format: Export format
    ///   - title: Title for the file name
    /// - Returns: URL to share
    func exportForSharing(
        segments: [TranscriptSegment],
        format: ExportFormat,
        title: String
    ) throws -> URL {
        let result = try export(segments: segments, format: format, fileName: title)
        return result.url
    }

    // MARK: - Private Methods

    /// Format time as SRT timestamp (HH:MM:SS,mmm)
    private func formatSRTTimestamp(_ time: TimeInterval) -> String {
        let hours = Int(time) / 3600
        let minutes = (Int(time) % 3600) / 60
        let seconds = Int(time) % 60
        let milliseconds = Int((time.truncatingRemainder(dividingBy: 1)) * 1000)

        return String(format: "%02d:%02d:%02d,%03d", hours, minutes, seconds, milliseconds)
    }

    /// Format time as VTT timestamp (HH:MM:SS.mmm)
    private func formatVTTTimestamp(_ time: TimeInterval) -> String {
        let hours = Int(time) / 3600
        let minutes = (Int(time) % 3600) / 60
        let seconds = Int(time) % 60
        let milliseconds = Int((time.truncatingRemainder(dividingBy: 1)) * 1000)

        return String(format: "%02d:%02d:%02d.%03d", hours, minutes, seconds, milliseconds)
    }

    /// Sanitize file name by removing invalid characters
    private func sanitizeFileName(_ name: String) -> String {
        let invalidCharacters = CharacterSet(charactersIn: "/\\?%*|\"<>:")
        return name
            .components(separatedBy: invalidCharacters)
            .joined(separator: "-")
            .trimmingCharacters(in: .whitespaces)
    }

    /// Write content to a temporary file
    private func writeToTemporaryFile(
        content: String,
        fileName: String,
        extension fileExtension: String
    ) throws -> URL {
        let sanitizedName = sanitizeFileName(fileName)

        guard !sanitizedName.isEmpty else {
            throw ExportError.invalidFileName
        }

        let fullFileName = "\(sanitizedName).\(fileExtension)"
        let tempDirectory = FileManager.default.temporaryDirectory
        let fileURL = tempDirectory.appendingPathComponent(fullFileName)

        // Remove existing file if present
        if FileManager.default.fileExists(atPath: fileURL.path) {
            try? FileManager.default.removeItem(at: fileURL)
        }

        do {
            try content.write(to: fileURL, atomically: true, encoding: .utf8)
        } catch {
            throw ExportError.writeFailed(error)
        }

        return fileURL
    }

    /// Clean up temporary export files
    func cleanupTemporaryFiles() {
        let tempDirectory = FileManager.default.temporaryDirectory

        do {
            let contents = try FileManager.default.contentsOfDirectory(
                at: tempDirectory,
                includingPropertiesForKeys: nil
            )

            let exportExtensions = ["txt", "srt", "vtt"]

            for url in contents {
                if exportExtensions.contains(url.pathExtension.lowercased()) {
                    try? FileManager.default.removeItem(at: url)
                }
            }
        } catch {
            // Ignore cleanup errors
        }
    }
}
