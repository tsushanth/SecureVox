import Foundation

/// Service for managing custom dictionary words for transcription improvement
final class CustomDictionaryService: ObservableObject {

    // MARK: - Singleton

    static let shared = CustomDictionaryService()

    // MARK: - Published Properties

    /// List of custom words/terms that should be recognized correctly
    @Published private(set) var words: [String] = []

    @Published var isEnabled: Bool {
        didSet {
            UserDefaults.standard.set(isEnabled, forKey: AppConstants.UserDefaultsKeys.customDictionaryEnabled)
        }
    }

    // MARK: - Initialization

    private init() {
        // Load enabled state
        if UserDefaults.standard.object(forKey: AppConstants.UserDefaultsKeys.customDictionaryEnabled) != nil {
            self.isEnabled = UserDefaults.standard.bool(forKey: AppConstants.UserDefaultsKeys.customDictionaryEnabled)
        } else {
            self.isEnabled = true
        }

        // Load saved words
        loadWords()
    }

    // MARK: - Public Methods

    /// Add a new custom word/term
    func addWord(_ word: String) {
        let trimmed = word.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }

        // Check for duplicates (case-insensitive)
        guard !words.contains(where: { $0.lowercased() == trimmed.lowercased() }) else {
            return
        }

        words.append(trimmed)
        words.sort { $0.localizedCaseInsensitiveCompare($1) == .orderedAscending }
        saveWords()
    }

    /// Add multiple words at once
    func addWords(_ newWords: [String]) {
        for word in newWords {
            let trimmed = word.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !trimmed.isEmpty else { continue }

            // Check for duplicates (case-insensitive)
            if !words.contains(where: { $0.lowercased() == trimmed.lowercased() }) {
                words.append(trimmed)
            }
        }
        words.sort { $0.localizedCaseInsensitiveCompare($1) == .orderedAscending }
        saveWords()
    }

    /// Remove a word
    func removeWord(_ word: String) {
        words.removeAll { $0 == word }
        saveWords()
    }

    /// Remove words at indices
    func removeWords(at offsets: IndexSet) {
        words.remove(atOffsets: offsets)
        saveWords()
    }

    /// Get the prompt string for Whisper transcription
    /// This is passed to the Whisper model to help it recognize these terms
    var promptString: String? {
        guard isEnabled && !words.isEmpty else { return nil }
        return words.joined(separator: ", ")
    }

    /// Import words from text (one word/phrase per line)
    func importWords(from text: String) -> Int {
        let lines = text.components(separatedBy: .newlines)
        var importCount = 0

        for line in lines {
            let trimmed = line.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !trimmed.isEmpty else { continue }

            // Check for duplicates (case-insensitive)
            if !words.contains(where: { $0.lowercased() == trimmed.lowercased() }) {
                words.append(trimmed)
                importCount += 1
            }
        }

        if importCount > 0 {
            words.sort { $0.localizedCaseInsensitiveCompare($1) == .orderedAscending }
            saveWords()
        }

        return importCount
    }

    /// Export words to text format (one per line)
    func exportWords() -> String {
        words.joined(separator: "\n")
    }

    /// Clear all words
    func clearAll() {
        words.removeAll()
        saveWords()
    }

    // MARK: - Private Methods

    private func loadWords() {
        // Try loading new format first
        if let savedWords = UserDefaults.standard.stringArray(forKey: AppConstants.UserDefaultsKeys.customDictionaryWords) {
            words = savedWords
            return
        }

        // Migration: Try loading old format and convert
        if let data = UserDefaults.standard.data(forKey: AppConstants.UserDefaultsKeys.customDictionary) {
            // Old format was an array of CustomWord objects with originalWord and replacement
            if let oldWords = try? JSONDecoder().decode([OldCustomWord].self, from: data) {
                // Extract the replacement values (the correctly spelled words)
                words = oldWords.map { $0.replacement }
                words.sort { $0.localizedCaseInsensitiveCompare($1) == .orderedAscending }
                // Save in new format
                saveWords()
                // Remove old data
                UserDefaults.standard.removeObject(forKey: AppConstants.UserDefaultsKeys.customDictionary)
            }
        }
    }

    private func saveWords() {
        UserDefaults.standard.set(words, forKey: AppConstants.UserDefaultsKeys.customDictionaryWords)
    }

    // MARK: - Migration Support

    /// Old word format for migration
    private struct OldCustomWord: Codable {
        let originalWord: String
        let replacement: String
    }
}
