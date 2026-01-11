import CoreML
import Foundation

/// Manages loading and unloading of Whisper CoreML models
final class WhisperModelLoader {

    // MARK: - Types

    enum ModelVariant: String, CaseIterable {
        case tiny = "whisper-tiny"
        case base = "whisper-base"
        case small = "whisper-small"

        var fileName: String {
            rawValue
        }

        var isBundled: Bool {
            self == .tiny
        }

        var expectedSize: Int64 {
            switch self {
            case .tiny: return 75_000_000    // ~75 MB
            case .base: return 150_000_000   // ~150 MB
            case .small: return 500_000_000  // ~500 MB
            }
        }
    }

    enum LoadError: Error, LocalizedError {
        case modelNotFound(ModelVariant)
        case loadFailed(Error)
        case compilationFailed(Error)

        var errorDescription: String? {
            switch self {
            case .modelNotFound(let variant):
                return "Model not found: \(variant.rawValue)"
            case .loadFailed(let error):
                return "Failed to load model: \(error.localizedDescription)"
            case .compilationFailed(let error):
                return "Failed to compile model: \(error.localizedDescription)"
            }
        }
    }

    // MARK: - Properties

    private(set) var loadedModel: MLModel?
    private(set) var loadedVariant: ModelVariant?

    // MARK: - Directories

    private var modelsDirectory: URL {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("Models")
    }

    // MARK: - Public Methods

    /// Check if a model is available
    func isModelAvailable(_ variant: ModelVariant) -> Bool {
        if variant.isBundled {
            return bundledModelURL(for: variant) != nil
        }
        return FileManager.default.fileExists(atPath: downloadedModelURL(for: variant).path)
    }

    /// Load a model into memory
    func loadModel(_ variant: ModelVariant) async throws -> MLModel {
        // Unload existing model first
        unloadModel()

        // Find model URL
        guard let modelURL = modelURL(for: variant) else {
            throw LoadError.modelNotFound(variant)
        }

        // Configure model
        let config = MLModelConfiguration()
        config.computeUnits = .cpuAndNeuralEngine // Prefer ANE for efficiency

        // Load model
        do {
            let model = try await Task.detached(priority: .userInitiated) {
                try MLModel(contentsOf: modelURL, configuration: config)
            }.value

            self.loadedModel = model
            self.loadedVariant = variant

            return model

        } catch {
            throw LoadError.loadFailed(error)
        }
    }

    /// Unload current model to free memory
    func unloadModel() {
        loadedModel = nil
        loadedVariant = nil
    }

    /// Get model file size
    func modelSize(_ variant: ModelVariant) -> Int64? {
        guard let url = modelURL(for: variant) else { return nil }

        let attributes = try? FileManager.default.attributesOfItem(atPath: url.path)
        return attributes?[.size] as? Int64
    }

    // MARK: - Private Methods

    private func modelURL(for variant: ModelVariant) -> URL? {
        // Check bundled first
        if let bundled = bundledModelURL(for: variant) {
            return bundled
        }

        // Check downloaded
        let downloaded = downloadedModelURL(for: variant)
        if FileManager.default.fileExists(atPath: downloaded.path) {
            return downloaded
        }

        return nil
    }

    private func bundledModelURL(for variant: ModelVariant) -> URL? {
        Bundle.main.url(forResource: variant.fileName, withExtension: "mlmodelc")
    }

    private func downloadedModelURL(for variant: ModelVariant) -> URL {
        modelsDirectory.appendingPathComponent("\(variant.fileName).mlmodelc")
    }
}
