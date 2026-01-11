import Foundation

/// Utilities for managing audio files
enum AudioFileManager {

    // MARK: - Directories

    /// Documents directory URL
    static var documentsDirectory: URL {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
    }

    /// Recordings directory URL
    static var recordingsDirectory: URL {
        documentsDirectory.appendingPathComponent(AppConstants.Storage.recordingsDirectory)
    }

    /// Temporary directory URL
    static var temporaryDirectory: URL {
        FileManager.default.temporaryDirectory
    }

    // MARK: - Directory Management

    /// Create recordings directory if it doesn't exist
    static func createRecordingsDirectoryIfNeeded() throws {
        if !FileManager.default.fileExists(atPath: recordingsDirectory.path) {
            try FileManager.default.createDirectory(
                at: recordingsDirectory,
                withIntermediateDirectories: true
            )
        }
    }

    // MARK: - File Operations

    /// Generate unique filename for recording
    static func generateRecordingFilename() -> String {
        "\(UUID().uuidString).m4a"
    }

    /// Get full URL for recording file
    static func recordingURL(filename: String) -> URL {
        recordingsDirectory.appendingPathComponent(filename)
    }

    /// Get relative path from documents directory
    static func relativePath(for url: URL) -> String? {
        let documentsPath = documentsDirectory.path
        guard url.path.hasPrefix(documentsPath) else { return nil }
        return String(url.path.dropFirst(documentsPath.count + 1))
    }

    /// Get file size in bytes
    static func fileSize(at url: URL) -> Int64 {
        let attributes = try? FileManager.default.attributesOfItem(atPath: url.path)
        return attributes?[.size] as? Int64 ?? 0
    }

    /// Check if file exists
    static func fileExists(at url: URL) -> Bool {
        FileManager.default.fileExists(atPath: url.path)
    }

    /// Delete file if exists
    static func deleteFile(at url: URL) throws {
        if fileExists(at: url) {
            try FileManager.default.removeItem(at: url)
        }
    }

    /// Move file to recordings directory
    static func moveToRecordings(from sourceURL: URL) throws -> URL {
        try createRecordingsDirectoryIfNeeded()

        let filename = generateRecordingFilename()
        let destinationURL = recordingURL(filename: filename)

        try FileManager.default.moveItem(at: sourceURL, to: destinationURL)

        return destinationURL
    }

    /// Copy file to recordings directory
    static func copyToRecordings(from sourceURL: URL) throws -> URL {
        try createRecordingsDirectoryIfNeeded()

        let filename = generateRecordingFilename()
        let destinationURL = recordingURL(filename: filename)

        try FileManager.default.copyItem(at: sourceURL, to: destinationURL)

        return destinationURL
    }

    // MARK: - Cleanup

    /// Delete all files in recordings directory
    static func deleteAllRecordings() throws {
        if fileExists(at: recordingsDirectory) {
            let contents = try FileManager.default.contentsOfDirectory(
                at: recordingsDirectory,
                includingPropertiesForKeys: nil
            )

            for fileURL in contents {
                try FileManager.default.removeItem(at: fileURL)
            }
        }
    }

    /// Clean up temporary files
    static func cleanupTemporaryFiles() {
        let tempDir = temporaryDirectory

        guard let contents = try? FileManager.default.contentsOfDirectory(
            at: tempDir,
            includingPropertiesForKeys: [.creationDateKey]
        ) else { return }

        let audioExtensions = ["m4a", "wav", "mp3", "aac"]
        let maxAge: TimeInterval = 3600 // 1 hour

        for fileURL in contents {
            let ext = fileURL.pathExtension.lowercased()
            guard audioExtensions.contains(ext) else { continue }

            let attributes = try? fileURL.resourceValues(forKeys: [.creationDateKey])
            if let creationDate = attributes?.creationDate,
               Date().timeIntervalSince(creationDate) > maxAge {
                try? FileManager.default.removeItem(at: fileURL)
            }
        }
    }

    /// Get orphaned audio files (files without corresponding database record)
    static func findOrphanedFiles(existingFilenames: Set<String>) throws -> [URL] {
        guard fileExists(at: recordingsDirectory) else { return [] }

        let contents = try FileManager.default.contentsOfDirectory(
            at: recordingsDirectory,
            includingPropertiesForKeys: nil
        )

        return contents.filter { url in
            !existingFilenames.contains(url.lastPathComponent)
        }
    }

    // MARK: - Storage Info

    /// Calculate total size of recordings directory
    static func totalRecordingsSize() throws -> Int64 {
        guard fileExists(at: recordingsDirectory) else { return 0 }

        let contents = try FileManager.default.contentsOfDirectory(
            at: recordingsDirectory,
            includingPropertiesForKeys: [.fileSizeKey]
        )

        var total: Int64 = 0
        for fileURL in contents {
            let attributes = try fileURL.resourceValues(forKeys: [.fileSizeKey])
            total += Int64(attributes.fileSize ?? 0)
        }

        return total
    }

    /// Get available disk space
    static func availableDiskSpace() throws -> Int64 {
        let attributes = try FileManager.default.attributesOfFileSystem(
            forPath: documentsDirectory.path
        )
        return attributes[.systemFreeSize] as? Int64 ?? 0
    }

    /// Check if storage is low
    static func isStorageLow() -> Bool {
        let available = (try? availableDiskSpace()) ?? 0
        return available < AppConstants.Storage.lowSpaceWarningThreshold
    }
}
