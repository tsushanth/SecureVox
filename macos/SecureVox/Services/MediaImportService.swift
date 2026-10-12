import Foundation
import AVFoundation
import AppKit
import UniformTypeIdentifiers
import os.log

private let importLogger = Logger(subsystem: "com.voicenotes.ondevice.macos", category: "MediaImportService")

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

    func importFile(url: URL, onProgress: ((Float) -> Void)? = nil) async throws -> ImportResult {
        // Check file size
        let fileSize = try getFileSize(url: url)
        guard fileSize <= AppConstants.Whisper.maxImportFileSize else {
            throw ImportError.fileTooLarge
        }

        // Determine file type
        let isVideo = isVideoFile(url: url)

        // Get duration
        let asset = AVURLAsset(url: url)
        let duration = try await asset.load(.duration).seconds

        guard duration <= AppConstants.Audio.maxRecordingDuration else {
            throw ImportError.durationTooLong
        }

        guard duration >= AppConstants.Audio.minTranscriptionDuration else {
            throw ImportError.durationTooShort
        }

        onProgress?(0.1)

        // Convert to 16kHz mono WAV for Whisper compatibility
        let audioURL = try await convertToWhisperFormat(asset: asset, onProgress: { p in
            // Map conversion progress (0-1) to overall range 0.1-0.9
            onProgress?(0.1 + p * 0.8)
        })

        onProgress?(1.0)

        // Get final file size
        let finalSize = try getFileSize(url: audioURL)

        return ImportResult(
            audioURL: audioURL,
            duration: duration,
            fileSize: finalSize,
            originalFileName: url.lastPathComponent,
            sourceType: .imported
        )
    }

    // MARK: - Audio Conversion

    /// Convert any audio/video file to 16kHz mono 16-bit PCM WAV using AVAssetReader/Writer
    private func convertToWhisperFormat(asset: AVURLAsset, onProgress: ((Float) -> Void)? = nil) async throws -> URL {
        // Get audio track
        let audioTracks = try await asset.loadTracks(withMediaType: .audio)
        guard let audioTrack = audioTracks.first else {
            throw ImportError.noAudioTrack
        }

        // Log source format
        let sourceDescription = try await audioTrack.load(.formatDescriptions)
        if let firstDesc = sourceDescription.first {
            let sourceFormat = CMAudioFormatDescriptionGetStreamBasicDescription(firstDesc)?.pointee
            importLogger.info("Source audio: \(sourceFormat?.mSampleRate ?? 0) Hz, \(sourceFormat?.mChannelsPerFrame ?? 0) ch")
        }

        let whisperSampleRate = AppConstants.Audio.whisperSampleRate
        importLogger.info("Converting to \(whisperSampleRate) Hz mono WAV for Whisper compatibility")

        // Create output URL in recordings directory
        let documentsURL = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        let recordingsURL = documentsURL.appendingPathComponent(AppConstants.Storage.recordingsDirectory)
        try FileManager.default.createDirectory(at: recordingsURL, withIntermediateDirectories: true)

        let fileName = "\(UUID().uuidString).wav"
        let outputURL = recordingsURL.appendingPathComponent(fileName)

        // Configure reader
        let reader = try AVAssetReader(asset: asset)

        let readerOutputSettings: [String: Any] = [
            AVFormatIDKey: kAudioFormatLinearPCM,
            AVSampleRateKey: whisperSampleRate,
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
            throw ImportError.exportFailed
        }
        reader.add(readerOutput)

        // Configure writer as WAV (Linear PCM)
        let writer = try AVAssetWriter(outputURL: outputURL, fileType: .wav)

        let writerInputSettings: [String: Any] = [
            AVFormatIDKey: kAudioFormatLinearPCM,
            AVSampleRateKey: whisperSampleRate,
            AVNumberOfChannelsKey: 1,
            AVLinearPCMBitDepthKey: 16,
            AVLinearPCMIsFloatKey: false,
            AVLinearPCMIsBigEndianKey: false,
            AVLinearPCMIsNonInterleaved: false
        ]

        let writerInput = AVAssetWriterInput(
            mediaType: .audio,
            outputSettings: writerInputSettings
        )
        writerInput.expectsMediaDataInRealTime = false

        guard writer.canAdd(writerInput) else {
            throw ImportError.exportFailed
        }
        writer.add(writerInput)

        // Start reading and writing
        guard reader.startReading() else {
            throw ImportError.exportFailed
        }

        guard writer.startWriting() else {
            throw ImportError.exportFailed
        }

        writer.startSession(atSourceTime: .zero)

        // Estimate total samples for progress reporting
        let totalDurationSeconds = try await asset.load(.duration).seconds
        let totalSamplesEstimate = max(1.0, totalDurationSeconds * AppConstants.Audio.whisperSampleRate)
        var samplesProcessed: Int64 = 0

        // Process samples
        return try await withCheckedThrowingContinuation { continuation in
            let queue = DispatchQueue(label: "com.securevox.macos.audioconversion")

            writerInput.requestMediaDataWhenReady(on: queue) {
                while writerInput.isReadyForMoreMediaData {
                    if let sampleBuffer = readerOutput.copyNextSampleBuffer() {
                        samplesProcessed += CMSampleBufferGetNumSamples(sampleBuffer)
                        writerInput.append(sampleBuffer)
                        let progress = Float(min(Double(samplesProcessed) / totalSamplesEstimate, 0.99))
                        DispatchQueue.main.async { onProgress?(progress) }
                    } else {
                        // No more samples
                        writerInput.markAsFinished()

                        switch reader.status {
                        case .completed:
                            writer.finishWriting {
                                if writer.status == .completed {
                                    importLogger.info("Conversion complete: \(outputURL.lastPathComponent)")
                                    continuation.resume(returning: outputURL)
                                } else {
                                    importLogger.error("Writer failed: \(writer.error?.localizedDescription ?? "unknown")")
                                    continuation.resume(throwing: ImportError.exportFailed)
                                }
                            }
                        case .failed:
                            writer.cancelWriting()
                            importLogger.error("Reader failed: \(reader.error?.localizedDescription ?? "unknown")")
                            continuation.resume(throwing: ImportError.exportFailed)
                        case .cancelled:
                            writer.cancelWriting()
                            continuation.resume(throwing: ImportError.cancelled)
                        default:
                            writer.cancelWriting()
                            continuation.resume(throwing: ImportError.exportFailed)
                        }
                        return
                    }
                }
            }
        }
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

extension UTType {
    static let mp3 = UTType(filenameExtension: "mp3")!
    static let wav = UTType(filenameExtension: "wav")!
    static let aiff = UTType(filenameExtension: "aiff")!
}
