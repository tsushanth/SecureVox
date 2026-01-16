import Foundation
import PhotosUI
import AVFoundation
import UniformTypeIdentifiers

/// Service for importing audio and video files with audio extraction
actor MediaImportService {

    // MARK: - Types

    struct ImportResult {
        let audioFileURL: URL
        let originalFileName: String
        let duration: TimeInterval
        let fileSize: Int64
        let sourceType: SourceType
    }

    struct ImportProgress {
        let stage: ImportStage
        let fractionCompleted: Double
        let message: String

        init(stage: ImportStage, fractionCompleted: Double) {
            self.stage = stage
            self.fractionCompleted = fractionCompleted
            self.message = stage.rawValue
        }
    }

    enum ImportStage: String {
        case loading = "Loading file..."
        case extractingAudio = "Extracting audio..."
        case converting = "Converting format..."
        case finalizing = "Finalizing..."
    }

    enum ImportError: Error, LocalizedError {
        case photoLibraryPermissionDenied
        case fileAccessDenied
        case unsupportedFormat(String)
        case noAudioTrack
        case extractionFailed(Error)
        case cancelled
        case fileTooLarge(Int64)
        case durationTooLong(TimeInterval)
        case readerInitFailed
        case writerInitFailed
        case readingFailed(String)
        case writingFailed(String)

        var errorDescription: String? {
            switch self {
            case .photoLibraryPermissionDenied:
                return "Photo library access is required to import videos"
            case .fileAccessDenied:
                return "Cannot access the selected file"
            case .unsupportedFormat(let format):
                return "Unsupported file format: \(format)"
            case .noAudioTrack:
                return "This video has no audio track"
            case .extractionFailed(let error):
                return "Audio extraction failed: \(error.localizedDescription)"
            case .cancelled:
                return "Import was cancelled"
            case .fileTooLarge(let size):
                let formatted = ByteCountFormatter.string(fromByteCount: size, countStyle: .file)
                return "File too large: \(formatted). Maximum supported size is 2 GB."
            case .durationTooLong(let duration):
                let hours = Int(duration) / 3600
                let minutes = (Int(duration) % 3600) / 60
                let maxHours = Int(AppConstants.Audio.maxRecordingDuration) / 3600
                return "File too long: \(hours)h \(minutes)m. Maximum supported duration is \(maxHours) hours."
            case .readerInitFailed:
                return "Failed to initialize audio reader"
            case .writerInitFailed:
                return "Failed to initialize audio writer"
            case .readingFailed(let message):
                return "Failed to read audio: \(message)"
            case .writingFailed(let message):
                return "Failed to write audio: \(message)"
            }
        }
    }

    // MARK: - Properties

    private var isCancelled = false

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

    // MARK: - Public Methods

    /// Import video from PHPicker result
    func importFromPhotoPicker(
        _ result: PHPickerResult,
        progress: @escaping (ImportProgress) -> Void
    ) async throws -> ImportResult {
        isCancelled = false
        progress(ImportProgress(stage: .loading, fractionCompleted: 0))

        // Load video URL from picker result
        let videoURL = try await loadVideoFromPicker(result)

        progress(ImportProgress(stage: .loading, fractionCompleted: 0.1))

        return try await processMediaFile(
            url: videoURL,
            sourceType: .videoImport,
            progress: progress
        )
    }

    /// Import from Photos library item
    func importVideo(
        from item: PHPickerResult,
        progress: @escaping (ImportProgress) -> Void
    ) async throws -> ImportResult {
        return try await importFromPhotoPicker(item, progress: progress)
    }

    /// Import audio/video file from URL (Files app)
    func importFile(
        from url: URL,
        progress: @escaping (ImportProgress) -> Void
    ) async throws -> ImportResult {
        isCancelled = false
        progress(ImportProgress(stage: .loading, fractionCompleted: 0))

        // Start accessing security-scoped resource if needed
        let didStartAccessing = url.startAccessingSecurityScopedResource()
        defer {
            if didStartAccessing {
                url.stopAccessingSecurityScopedResource()
            }
        }

        // Copy file to temp directory for processing
        let tempURL = try copyToTempDirectory(url: url)

        // Check file size
        let attributes = try FileManager.default.attributesOfItem(atPath: tempURL.path)
        let fileSize = attributes[.size] as? Int64 ?? 0

        if fileSize > AppConstants.Whisper.maxImportFileSize {
            try? FileManager.default.removeItem(at: tempURL)
            throw ImportError.fileTooLarge(fileSize)
        }

        progress(ImportProgress(stage: .loading, fractionCompleted: 0.1))

        // Determine source type
        let sourceType: SourceType = isVideoFile(tempURL) ? .videoImport : .audioImport

        return try await processMediaFile(
            url: tempURL,
            sourceType: sourceType,
            progress: progress
        )
    }

    /// Import from URL with completion handler (for callback-based APIs)
    func importFile(
        from url: URL,
        progress: @escaping (ImportProgress) -> Void,
        completion: @escaping (Result<ImportResult, Error>) -> Void
    ) {
        Task {
            do {
                let result = try await importFile(from: url, progress: progress)
                await MainActor.run {
                    completion(.success(result))
                }
            } catch {
                await MainActor.run {
                    completion(.failure(error))
                }
            }
        }
    }

    /// Cancel any in-progress import
    func cancelImport() {
        isCancelled = true
    }

    /// Clean up temporary files older than specified age
    /// - Parameter maxAge: Maximum age in seconds for temp files (default: 1 hour)
    /// - Returns: Number of files cleaned up and total bytes freed
    @discardableResult
    func cleanupTemporaryFiles(maxAge: TimeInterval = 3600) -> (filesRemoved: Int, bytesFreed: Int64) {
        let tempDir = FileManager.default.temporaryDirectory
        let fileManager = FileManager.default

        guard let tempFiles = try? fileManager.contentsOfDirectory(
            at: tempDir,
            includingPropertiesForKeys: [.fileSizeKey, .creationDateKey],
            options: .skipsHiddenFiles
        ) else {
            return (0, 0)
        }

        let audioExtensions = Set(["m4a", "wav", "mp3", "aac", "mp4", "mov", "aiff", "caf"])
        let now = Date()
        var filesRemoved = 0
        var bytesFreed: Int64 = 0

        for url in tempFiles {
            let ext = url.pathExtension.lowercased()
            guard audioExtensions.contains(ext) else { continue }

            do {
                let resourceValues = try url.resourceValues(forKeys: [.fileSizeKey, .creationDateKey])
                let creationDate = resourceValues.creationDate ?? now
                let fileAge = now.timeIntervalSince(creationDate)

                // Only remove files older than maxAge
                if fileAge > maxAge {
                    let fileSize = Int64(resourceValues.fileSize ?? 0)
                    try fileManager.removeItem(at: url)
                    filesRemoved += 1
                    bytesFreed += fileSize
                    print("[MediaImportService] Cleaned up temp file: \(url.lastPathComponent) (\(fileAge)s old, \(fileSize) bytes)")
                }
            } catch {
                // Log but continue with other files
                print("[MediaImportService] Failed to clean up \(url.lastPathComponent): \(error.localizedDescription)")
            }
        }

        if filesRemoved > 0 {
            let bytesFormatted = ByteCountFormatter.string(fromByteCount: bytesFreed, countStyle: .file)
            print("[MediaImportService] Cleanup complete: \(filesRemoved) files removed, \(bytesFormatted) freed")
        }

        return (filesRemoved, bytesFreed)
    }

    /// Clean up all temporary audio/video files immediately (regardless of age)
    @discardableResult
    func cleanupAllTemporaryFiles() -> (filesRemoved: Int, bytesFreed: Int64) {
        return cleanupTemporaryFiles(maxAge: 0)
    }

    /// Check available storage space
    func availableStorageSpace() throws -> Int64 {
        let documentsURL = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        let attributes = try FileManager.default.attributesOfFileSystem(forPath: documentsURL.path)
        return attributes[.systemFreeSize] as? Int64 ?? 0
    }

    // MARK: - Private Methods - Loading

    private func loadVideoFromPicker(_ item: PHPickerResult) async throws -> URL {
        try await withCheckedThrowingContinuation { continuation in
            // Try video types first
            let videoTypes = [UTType.movie.identifier, UTType.video.identifier, UTType.mpeg4Movie.identifier, UTType.quickTimeMovie.identifier]

            func tryLoadType(at index: Int) {
                guard index < videoTypes.count else {
                    continuation.resume(throwing: ImportError.fileAccessDenied)
                    return
                }

                let typeIdentifier = videoTypes[index]

                if item.itemProvider.hasItemConformingToTypeIdentifier(typeIdentifier) {
                    item.itemProvider.loadFileRepresentation(forTypeIdentifier: typeIdentifier) { url, error in
                        if let error = error {
                            // Try next type
                            tryLoadType(at: index + 1)
                            return
                        }

                        guard let url = url else {
                            tryLoadType(at: index + 1)
                            return
                        }

                        // Copy to temp directory (picker URL is temporary)
                        let tempURL = FileManager.default.temporaryDirectory
                            .appendingPathComponent(UUID().uuidString)
                            .appendingPathExtension(url.pathExtension)

                        do {
                            try FileManager.default.copyItem(at: url, to: tempURL)
                            continuation.resume(returning: tempURL)
                        } catch {
                            continuation.resume(throwing: error)
                        }
                    }
                } else {
                    tryLoadType(at: index + 1)
                }
            }

            tryLoadType(at: 0)
        }
    }

    private func copyToTempDirectory(url: URL) throws -> URL {
        let tempURL = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString)
            .appendingPathExtension(url.pathExtension)

        try FileManager.default.copyItem(at: url, to: tempURL)
        return tempURL
    }

    // MARK: - Private Methods - Processing

    private func processMediaFile(
        url: URL,
        sourceType: SourceType,
        progress: @escaping (ImportProgress) -> Void
    ) async throws -> ImportResult {
        let originalFileName = url.lastPathComponent

        // Load asset
        let asset = AVURLAsset(url: url)

        // Check for audio track
        let audioTracks = try await asset.loadTracks(withMediaType: .audio)
        guard !audioTracks.isEmpty else {
            throw ImportError.noAudioTrack
        }

        progress(ImportProgress(stage: .extractingAudio, fractionCompleted: 0.2))

        // Check cancellation
        if isCancelled {
            throw ImportError.cancelled
        }

        // Get duration
        let duration = try await asset.load(.duration).seconds

        // Validate duration against maximum
        if duration > AppConstants.Audio.maxRecordingDuration {
            // Clean up temp file before throwing
            try? FileManager.default.removeItem(at: url)
            throw ImportError.durationTooLong(duration)
        }

        // Extract audio using AVAssetReader
        let outputURL: URL
        if sourceType == .videoImport {
            outputURL = try await extractAudioWithAssetReader(
                from: asset,
                progress: progress
            )
        } else {
            // For audio files, convert to standard format
            outputURL = try await convertAudioFormat(
                from: url,
                asset: asset,
                progress: progress
            )
        }

        progress(ImportProgress(stage: .finalizing, fractionCompleted: 0.9))

        // Get file size
        let attributes = try FileManager.default.attributesOfItem(atPath: outputURL.path)
        let fileSize = attributes[.size] as? Int64 ?? 0

        // Move to permanent location
        let finalURL = try moveToRecordingsDirectory(outputURL)

        // Clean up source temp file
        try? FileManager.default.removeItem(at: url)

        progress(ImportProgress(stage: .finalizing, fractionCompleted: 1.0))

        return ImportResult(
            audioFileURL: finalURL,
            originalFileName: originalFileName,
            duration: duration,
            fileSize: fileSize,
            sourceType: sourceType
        )
    }

    // MARK: - Audio Extraction with AVAssetReader

    private func extractAudioWithAssetReader(
        from asset: AVURLAsset,
        progress: @escaping (ImportProgress) -> Void
    ) async throws -> URL {
        let outputURL = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString)
            .appendingPathExtension("m4a")

        // Get audio track
        let audioTracks = try await asset.loadTracks(withMediaType: .audio)
        guard let audioTrack = audioTracks.first else {
            throw ImportError.noAudioTrack
        }

        // Create asset reader
        let reader: AVAssetReader
        do {
            reader = try AVAssetReader(asset: asset)
        } catch {
            throw ImportError.readerInitFailed
        }

        // Configure reader output settings (PCM for reading)
        let readerOutputSettings: [String: Any] = [
            AVFormatIDKey: kAudioFormatLinearPCM,
            AVSampleRateKey: 44100,
            AVNumberOfChannelsKey: 1,
            AVLinearPCMBitDepthKey: 16,
            AVLinearPCMIsFloatKey: false,
            AVLinearPCMIsBigEndianKey: false,
            AVLinearPCMIsNonInterleaved: false
        ]

        let readerOutput = AVAssetReaderTrackOutput(
            track: audioTrack,
            outputSettings: readerOutputSettings
        )
        readerOutput.alwaysCopiesSampleData = false

        guard reader.canAdd(readerOutput) else {
            throw ImportError.readerInitFailed
        }
        reader.add(readerOutput)

        // Create asset writer
        let writer: AVAssetWriter
        do {
            writer = try AVAssetWriter(outputURL: outputURL, fileType: .m4a)
        } catch {
            throw ImportError.writerInitFailed
        }

        // Configure writer input settings (AAC for output)
        let writerInputSettings: [String: Any] = [
            AVFormatIDKey: kAudioFormatMPEG4AAC,
            AVSampleRateKey: 44100,
            AVNumberOfChannelsKey: 1,
            AVEncoderBitRateKey: 128000
        ]

        let writerInput = AVAssetWriterInput(
            mediaType: .audio,
            outputSettings: writerInputSettings
        )
        writerInput.expectsMediaDataInRealTime = false

        guard writer.canAdd(writerInput) else {
            throw ImportError.writerInitFailed
        }
        writer.add(writerInput)

        // Start reading and writing
        guard reader.startReading() else {
            throw ImportError.readingFailed(reader.error?.localizedDescription ?? "Unknown error")
        }

        guard writer.startWriting() else {
            throw ImportError.writingFailed(writer.error?.localizedDescription ?? "Unknown error")
        }

        writer.startSession(atSourceTime: .zero)

        // Get duration for progress tracking
        let duration = try await asset.load(.duration)
        let durationSeconds = CMTimeGetSeconds(duration)

        // Process samples
        return try await withCheckedThrowingContinuation { continuation in
            let queue = DispatchQueue(label: "com.voicenotes.audioextraction")

            writerInput.requestMediaDataWhenReady(on: queue) { [weak self] in
                guard let self = self else {
                    continuation.resume(throwing: ImportError.cancelled)
                    return
                }

                while writerInput.isReadyForMoreMediaData {
                    // Check cancellation
                    if self.isCancelled {
                        reader.cancelReading()
                        writer.cancelWriting()
                        continuation.resume(throwing: ImportError.cancelled)
                        return
                    }

                    if let sampleBuffer = readerOutput.copyNextSampleBuffer() {
                        // Update progress
                        let currentTime = CMSampleBufferGetPresentationTimeStamp(sampleBuffer)
                        let currentSeconds = CMTimeGetSeconds(currentTime)
                        let progressValue = 0.2 + (currentSeconds / durationSeconds) * 0.6

                        Task { @MainActor in
                            progress(ImportProgress(stage: .extractingAudio, fractionCompleted: progressValue))
                        }

                        writerInput.append(sampleBuffer)
                    } else {
                        // No more samples
                        writerInput.markAsFinished()

                        switch reader.status {
                        case .completed:
                            writer.finishWriting {
                                if writer.status == .completed {
                                    continuation.resume(returning: outputURL)
                                } else {
                                    continuation.resume(throwing: ImportError.writingFailed(
                                        writer.error?.localizedDescription ?? "Unknown error"
                                    ))
                                }
                            }
                        case .failed:
                            writer.cancelWriting()
                            continuation.resume(throwing: ImportError.readingFailed(
                                reader.error?.localizedDescription ?? "Unknown error"
                            ))
                        case .cancelled:
                            writer.cancelWriting()
                            continuation.resume(throwing: ImportError.cancelled)
                        default:
                            writer.cancelWriting()
                            continuation.resume(throwing: ImportError.readingFailed("Unexpected reader status"))
                        }
                        return
                    }
                }
            }
        }
    }

    // MARK: - Audio Format Conversion

    private func convertAudioFormat(
        from url: URL,
        asset: AVURLAsset,
        progress: @escaping (ImportProgress) -> Void
    ) async throws -> URL {
        let outputURL = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString)
            .appendingPathExtension("m4a")

        progress(ImportProgress(stage: .converting, fractionCompleted: 0.3))

        // Check if already in compatible format
        let fileExtension = url.pathExtension.lowercased()
        if fileExtension == "m4a" {
            // Just copy
            try FileManager.default.copyItem(at: url, to: outputURL)
            progress(ImportProgress(stage: .converting, fractionCompleted: 0.8))
            return outputURL
        }

        // Use AVAssetExportSession for format conversion
        guard let exportSession = AVAssetExportSession(
            asset: asset,
            presetName: AVAssetExportPresetAppleM4A
        ) else {
            throw ImportError.extractionFailed(
                NSError(domain: "MediaImport", code: -1, userInfo: [
                    NSLocalizedDescriptionKey: "Cannot create export session"
                ])
            )
        }

        exportSession.outputURL = outputURL
        exportSession.outputFileType = .m4a

        // Track progress using explicit AnyCancellable type
        let progressTimer = Timer.publish(every: 0.1, on: .main, in: .common).autoconnect()
        var progressCancellable: AnyCancellable?

        progressCancellable = progressTimer.sink { _ in
            let exportProgress = 0.3 + Double(exportSession.progress) * 0.5
            progress(ImportProgress(stage: .converting, fractionCompleted: exportProgress))
        }

        // Export
        await exportSession.export()

        // Cancel progress timer - properly typed, no unsafe cast needed
        progressCancellable?.cancel()
        progressCancellable = nil

        // Check for errors
        if let error = exportSession.error {
            throw ImportError.extractionFailed(error)
        }

        guard exportSession.status == .completed else {
            throw ImportError.extractionFailed(
                NSError(domain: "MediaImport", code: -2, userInfo: [
                    NSLocalizedDescriptionKey: "Export failed with status: \(exportSession.status.rawValue)"
                ])
            )
        }

        return outputURL
    }

    // MARK: - File Management

    private func moveToRecordingsDirectory(_ sourceURL: URL) throws -> URL {
        let recordingsDir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent(AppConstants.Storage.recordingsDirectory)

        try FileManager.default.createDirectory(at: recordingsDir, withIntermediateDirectories: true)

        let fileName = "\(UUID().uuidString).m4a"
        let finalURL = recordingsDir.appendingPathComponent(fileName)

        try FileManager.default.moveItem(at: sourceURL, to: finalURL)

        return finalURL
    }

    private func isVideoFile(_ url: URL) -> Bool {
        let videoExtensions = ["mp4", "mov", "m4v", "avi", "mkv", "webm", "3gp"]
        return videoExtensions.contains(url.pathExtension.lowercased())
    }
}

// MARK: - Combine Support

import Combine

extension MediaImportService {
    /// AnyCancellable wrapper for timer
    typealias AnyCancellable = Combine.AnyCancellable
}
