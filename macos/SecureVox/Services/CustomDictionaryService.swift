import Foundation
import Combine

/// Service for managing custom vocabulary/dictionary words
class CustomDictionaryService: ObservableObject {

    // MARK: - Singleton

    static let shared = CustomDictionaryService()

    // MARK: - Published Properties

    @Published private(set) var words: [String] = []

    // MARK: - Constants

    private let userDefaultsKey = "customDictionaryWords"
    private let maxWords = 150

    // MARK: - Initialization

    private init() {
        loadWords()
    }

    // MARK: - Public Methods

    /// Add a word to the dictionary
    func addWord(_ word: String) {
        let trimmed = word.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        guard !words.contains(trimmed) else { return }
        guard words.count < maxWords else { return }

        words.append(trimmed)
        saveWords()
    }

    /// Add multiple words
    func addWords(_ newWords: [String]) {
        for word in newWords {
            let trimmed = word.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !trimmed.isEmpty else { continue }
            guard !words.contains(trimmed) else { continue }
            guard words.count < maxWords else { break }

            words.append(trimmed)
        }
        saveWords()
    }

    /// Remove a word from the dictionary
    func removeWord(_ word: String) {
        words.removeAll { $0 == word }
        saveWords()
    }

    /// Remove words at offsets
    func removeWords(at offsets: IndexSet) {
        words.remove(atOffsets: offsets)
        saveWords()
    }

    /// Clear all words
    func clearAll() {
        words.removeAll()
        saveWords()
    }

    /// Import words from text (one per line or space-separated)
    @discardableResult
    func importWords(from text: String) -> Int {
        let lines = text.components(separatedBy: .newlines)
        var imported = 0

        for line in lines {
            // Split by spaces as well
            let lineWords = line.components(separatedBy: .whitespaces)
            for word in lineWords {
                let trimmed = word.trimmingCharacters(in: .whitespacesAndNewlines)
                guard !trimmed.isEmpty else { continue }
                guard !words.contains(trimmed) else { continue }
                guard words.count < maxWords else { return imported }

                words.append(trimmed)
                imported += 1
            }
        }

        saveWords()
        return imported
    }

    /// Export words as text (one per line)
    func exportWords() -> String {
        words.joined(separator: "\n")
    }

    /// Get words as a comma-separated string for Whisper prompt
    func getPromptString() -> String {
        words.joined(separator: ", ")
    }

    // MARK: - Private Methods

    private func loadWords() {
        if let saved = UserDefaults.standard.stringArray(forKey: userDefaultsKey) {
            words = saved
        }
    }

    private func saveWords() {
        UserDefaults.standard.set(words, forKey: userDefaultsKey)
    }
}
