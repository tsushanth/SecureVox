import Foundation
import AppKit
import os.log

private let logger = Logger(subsystem: "com.voicenotes.ondevice.macos", category: "Export")

/// Service for exporting transcripts in various formats
class ExportService {

    // MARK: - Singleton

    static let shared = ExportService()

    private init() {}

    // MARK: - Export Methods

    func export(segments: [TranscriptSegment], format: AppConstants.ExportFormat, title: String) -> String {
        switch format {
        case .txt:
            return exportToTXT(segments: segments)
        case .srt:
            return exportToSRT(segments: segments)
        case .vtt:
            return exportToVTT(segments: segments)
        case .json:
            return exportToJSON(segments: segments, title: title)
        }
    }

    func exportToFile(segments: [TranscriptSegment], format: AppConstants.ExportFormat, title: String) -> URL? {
        let content = export(segments: segments, format: format, title: title)

        let sanitizedTitle = sanitizeFileName(title)
        let fileName = "\(sanitizedTitle).\(format.fileExtension)"

        let tempDir = FileManager.default.temporaryDirectory
        let fileURL = tempDir.appendingPathComponent(fileName)

        do {
            try content.write(to: fileURL, atomically: true, encoding: .utf8)
            return fileURL
        } catch {
            logger.error("Error writing export file: \(error.localizedDescription)")
            return nil
        }
    }

    func copyToClipboard(segments: [TranscriptSegment]) {
        let text = segments
            .sorted { $0.startTime < $1.startTime }
            .map { $0.text }
            .joined(separator: " ")

        let pasteboard = NSPasteboard.general
        pasteboard.clearContents()
        pasteboard.setString(text, forType: .string)
    }

    // MARK: - Format Implementations

    func exportToTXT(segments: [TranscriptSegment]) -> String {
        segments
            .sorted { $0.startTime < $1.startTime }
            .map { $0.text.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
            .joined(separator: "\n\n")
    }

    func exportToSRT(segments: [TranscriptSegment]) -> String {
        let sortedSegments = segments.sorted { $0.startTime < $1.startTime }

        return sortedSegments.enumerated().map { index, segment in
            let startTimestamp = formatSRTTimestamp(segment.startTime)
            let endTimestamp = formatSRTTimestamp(segment.endTime)
            let text = segment.text.trimmingCharacters(in: .whitespacesAndNewlines)

            return "\(index + 1)\n\(startTimestamp) --> \(endTimestamp)\n\(text)"
        }.joined(separator: "\n\n")
    }

    func exportToVTT(segments: [TranscriptSegment]) -> String {
        var output = "WEBVTT\n\n"

        let sortedSegments = segments.sorted { $0.startTime < $1.startTime }

        output += sortedSegments.map { segment in
            let startTimestamp = formatVTTTimestamp(segment.startTime)
            let endTimestamp = formatVTTTimestamp(segment.endTime)
            let text = segment.text.trimmingCharacters(in: .whitespacesAndNewlines)

            return "\(startTimestamp) --> \(endTimestamp)\n\(text)"
        }.joined(separator: "\n\n")

        return output
    }

    func exportToJSON(segments: [TranscriptSegment], title: String) -> String {
        let sortedSegments = segments.sorted { $0.startTime < $1.startTime }

        let jsonSegments = sortedSegments.map { segment -> [String: Any] in
            return [
                "text": segment.text,
                "start_time": segment.startTime,
                "end_time": segment.endTime,
                "confidence": segment.confidence
            ]
        }

        let fullTranscript = sortedSegments.map { $0.text }.joined(separator: " ")

        let jsonObject: [String: Any] = [
            "title": title,
            "full_transcript": fullTranscript,
            "segments": jsonSegments,
            "exported_at": ISO8601DateFormatter().string(from: Date())
        ]

        do {
            let jsonData = try JSONSerialization.data(withJSONObject: jsonObject, options: [.prettyPrinted, .sortedKeys])
            return String(data: jsonData, encoding: .utf8) ?? "{}"
        } catch {
            return "{}"
        }
    }

    func exportToJSON(recording: Recording, segments: [TranscriptSegment]) -> String {
        let sortedSegments = segments.sorted { $0.startTime < $1.startTime }

        let jsonSegments = sortedSegments.map { segment -> [String: Any] in
            return [
                "text": segment.text,
                "start_time": segment.startTime,
                "end_time": segment.endTime,
                "confidence": segment.confidence
            ]
        }

        let fullTranscript = sortedSegments.map { $0.text }.joined(separator: " ")

        var jsonObject: [String: Any] = [
            "title": recording.title,
            "full_transcript": fullTranscript,
            "duration": recording.duration,
            "created_at": ISO8601DateFormatter().string(from: recording.createdAt),
            "source_type": recording.sourceType.rawValue,
            "segments": jsonSegments,
            "exported_at": ISO8601DateFormatter().string(from: Date())
        ]

        if let model = recording.transcriptionModel {
            jsonObject["transcription_model"] = model
        }
        if let language = recording.detectedLanguage {
            jsonObject["detected_language"] = language
        }

        do {
            let jsonData = try JSONSerialization.data(withJSONObject: jsonObject, options: [.prettyPrinted, .sortedKeys])
            return String(data: jsonData, encoding: .utf8) ?? "{}"
        } catch {
            return "{}"
        }
    }

    // MARK: - Helpers

    private func formatSRTTimestamp(_ time: TimeInterval) -> String {
        let hours = Int(time) / 3600
        let minutes = (Int(time) % 3600) / 60
        let seconds = Int(time) % 60
        let milliseconds = Int((time.truncatingRemainder(dividingBy: 1)) * 1000)

        return String(format: "%02d:%02d:%02d,%03d", hours, minutes, seconds, milliseconds)
    }

    private func formatVTTTimestamp(_ time: TimeInterval) -> String {
        let hours = Int(time) / 3600
        let minutes = (Int(time) % 3600) / 60
        let seconds = Int(time) % 60
        let milliseconds = Int((time.truncatingRemainder(dividingBy: 1)) * 1000)

        return String(format: "%02d:%02d:%02d.%03d", hours, minutes, seconds, milliseconds)
    }

    private func sanitizeFileName(_ name: String) -> String {
        let invalidCharacters = CharacterSet(charactersIn: ":/\\?%*|\"<>")
        var sanitized = name.components(separatedBy: invalidCharacters).joined(separator: "_")

        // Limit length
        if sanitized.count > 200 {
            sanitized = String(sanitized.prefix(200))
        }

        // Remove leading/trailing spaces and dots
        sanitized = sanitized.trimmingCharacters(in: .whitespacesAndNewlines)
        sanitized = sanitized.trimmingCharacters(in: CharacterSet(charactersIn: "."))

        return sanitized.isEmpty ? "transcript" : sanitized
    }
}
