import Foundation
import AVFoundation
import AppKit

/// Service for importing audio and video files
class MediaImportService {

    // MARK: - Singleton

    static let shared = MediaImportService()

    private init() {}

    // MARK: - Supported Types

    static let supportedAudioTypes: [UTType] = [
        .mp3, .mpeg4Audio, .wav, .aiff, .audio
    ]

    static let supportedVideoTypes: [UTType] = [
        .mpeg4Movie, .quickTimeMovie, .movie, .video
    ]

    static var allSupportedTypes: [UTType] {
        supportedAudioTypes + supportedVideoTypes
    }

    // MARK: - Import

    func importFile(url: URL) async throws -> ImportResult {
        // Check file size
        let fileSize = try getFileSize(url: url)
        guard fileSize <= AppConstants.Whisper.maxImportFileSize else {
            throw ImportError.fileTooLarge
        }

        // Determine file type
        let isVideo = isVideoFile(url: url)

        // Get duration
        let asset = AVAsset(url: url)
        let duration = try await asset.load(.duration).seconds

        guard duration <= AppConstants.Audio.maxRecordingDuration else {
            throw ImportError.durationTooLong
        }

        guard duration >= AppConstants.Audio.minTranscriptionDuration else {
            throw ImportError.durationTooShort
        }

        // If video, extract audio
        let audioURL: URL
        if isVideo {
            audioURL = try await extractAudioFromVideo(url: url)
        } else {
            audioURL = try copyToRecordingsDirectory(url: url)
        }

        // Get final file size
        let finalSize = try getFileSize(url: audioURL)

        return ImportResult(
            audioURL: audioURL,
            duration: duration,
            fileSize: finalSize,
            originalFileName: url.lastPathComponent,
            sourceType: isVideo ? .imported : .imported // Could differentiate video vs audio import
        )
    }

    // MARK: - File Operations

    private func getFileSize(url: URL) throws -> Int64 {
        let attributes = try FileManager.default.attributesOfItem(atPath: url.path)
        return attributes[.size] as? Int64 ?? 0
    }

    private func isVideoFile(url: URL) -> Bool {
        let videoExtensions = ["mp4", "mov", "m4v", "avi", "mkv", "webm"]
        return videoExtensions.contains(url.pathExtension.lowercased())
    }

    private func copyToRecordingsDirectory(url: URL) throws -> URL {
        let documentsURL = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        let recordingsURL = documentsURL.appendingPathComponent(AppConstants.Storage.recordingsDirectory)

        // Create directory if needed
        try FileManager.default.createDirectory(at: recordingsURL, withIntermediateDirectories: true)

        // Generate unique filename
        let fileName = "import_\(Int(Date().timeIntervalSince1970))_\(url.lastPathComponent)"
        let destinationURL = recordingsURL.appendingPathComponent(fileName)

        // Copy file
        try FileManager.default.copyItem(at: url, to: destinationURL)

        return destinationURL
    }

    private func extractAudioFromVideo(url: URL) async throws -> URL {
        let asset = AVAsset(url: url)

        // Check for audio tracks
        let audioTracks = try await asset.loadTracks(withMediaType: .audio)
        guard !audioTracks.isEmpty else {
            throw ImportError.noAudioTrack
        }

        // Create output URL
        let documentsURL = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        let recordingsURL = documentsURL.appendingPathComponent(AppConstants.Storage.recordingsDirectory)
        try FileManager.default.createDirectory(at: recordingsURL, withIntermediateDirectories: true)

        let fileName = "import_\(Int(Date().timeIntervalSince1970)).m4a"
        let outputURL = recordingsURL.appendingPathComponent(fileName)

        // Export audio
        guard let exportSession = AVAssetExportSession(asset: asset, presetName: AVAssetExportPresetAppleM4A) else {
            throw ImportError.exportFailed
        }

        exportSession.outputURL = outputURL
        exportSession.outputFileType = .m4a

        await exportSession.export()

        switch exportSession.status {
        case .completed:
            return outputURL
        case .failed:
            throw ImportError.exportFailed
        case .cancelled:
            throw ImportError.cancelled
        default:
            throw ImportError.exportFailed
        }
    }

    // MARK: - Open Panel

    func showOpenPanel() -> URL? {
        let panel = NSOpenPanel()
        panel.allowsMultipleSelection = false
        panel.canChooseDirectories = false
        panel.canChooseFiles = true
        panel.allowedContentTypes = Self.allSupportedTypes
        panel.message = "Select an audio or video file to import"
        panel.prompt = "Import"

        let response = panel.runModal()

        if response == .OK {
            return panel.url
        }
        return nil
    }
}

// MARK: - Import Result

struct ImportResult {
    let audioURL: URL
    let duration: TimeInterval
    let fileSize: Int64
    let originalFileName: String
    let sourceType: SourceType
}

// MARK: - Import Errors

enum ImportError: LocalizedError {
    case fileTooLarge
    case durationTooLong
    case durationTooShort
    case noAudioTrack
    case exportFailed
    case cancelled
    case unsupportedFormat

    var errorDescription: String? {
        switch self {
        case .fileTooLarge:
            return "File is too large (maximum 2 GB)"
        case .durationTooLong:
            return "Duration is too long (maximum 4 hours)"
        case .durationTooShort:
            return "Duration is too short (minimum 0.5 seconds)"
        case .noAudioTrack:
            return "Video file has no audio track"
        case .exportFailed:
            return "Failed to extract audio"
        case .cancelled:
            return "Import was cancelled"
        case .unsupportedFormat:
            return "Unsupported file format"
        }
    }
}

// MARK: - UTType Extension

import UniformTypeIdentifiers

extension UTType {
    static let mp3 = UTType(filenameExtension: "mp3")!
    static let wav = UTType(filenameExtension: "wav")!
    static let aiff = UTType(filenameExtension: "aiff")!
}
