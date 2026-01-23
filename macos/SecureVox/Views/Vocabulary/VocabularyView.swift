import SwiftUI

/// Custom vocabulary view for managing dictionary words
struct VocabularyView: View {

    // MARK: - State

    @StateObject private var dictionaryService = CustomDictionaryService.shared
    @State private var newWord = ""
    @State private var isEditing = false
    @FocusState private var isTextFieldFocused: Bool

    // MARK: - Body

    var body: some View {
        VStack(spacing: 0) {
            // Header
            sectionHeader("CUSTOM VOCABULARY")

            // Vocabulary text area
            vocabularyEditor

            // Tips section
            tipsSection

            // Warning
            warningBanner

            Spacer()
        }
        .padding()
        .navigationTitle("Vocabulary")
    }

    // MARK: - Views

    private func sectionHeader(_ title: String) -> some View {
        HStack {
            Text(title)
                .font(.caption)
                .foregroundStyle(.secondary)
                .textCase(.uppercase)
            Spacer()
        }
        .padding(.bottom, 8)
    }

    private var vocabularyEditor: some View {
        VStack(alignment: .leading, spacing: 0) {
            // Words display area
            ScrollView {
                VStack(alignment: .leading, spacing: 4) {
                    if dictionaryService.words.isEmpty && !isEditing {
                        // Placeholder examples
                        Text("Steve Wozniak")
                            .foregroundStyle(.secondary.opacity(0.5))
                        Text("LLama-3")
                            .foregroundStyle(.secondary.opacity(0.5))
                        Text("CRISPR-Cas9")
                            .foregroundStyle(.secondary.opacity(0.5))
                    } else {
                        ForEach(dictionaryService.words, id: \.self) { word in
                            Text(word)
                                .foregroundStyle(.primary)
                        }
                    }

                    // Input field when editing
                    if isEditing {
                        TextField("Add word...", text: $newWord)
                            .textFieldStyle(.plain)
                            .focused($isTextFieldFocused)
                            .onSubmit {
                                addCurrentWord()
                            }
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding()
            }
            .frame(height: 150)
            .background(Color(NSColor.textBackgroundColor).opacity(0.5))
            .cornerRadius(8)

            // Character count and edit button
            HStack {
                Spacer()
                Text("\(dictionaryService.words.count)/150")
                    .font(.caption)
                    .foregroundStyle(.secondary)

                Button {
                    isEditing.toggle()
                    if isEditing {
                        isTextFieldFocused = true
                    }
                } label: {
                    HStack(spacing: 4) {
                        Image(systemName: "pencil")
                        Text("Edit")
                    }
                    .font(.caption)
                }
                .buttonStyle(.plain)
            }
            .padding(.top, 4)

            // Description
            Text("Add important names, technical terms, or specialized vocabulary to improve transcription accuracy. Enter one term per line or separate with spaces.")
                .font(.caption)
                .foregroundStyle(.secondary)
                .padding(.top, 12)
        }
    }

    private var tipsSection: some View {
        VStack(alignment: .leading, spacing: 0) {
            sectionHeader("TIPS")
                .padding(.top, 24)

            VStack(alignment: .leading, spacing: 16) {
                TipRow(
                    icon: "person",
                    iconColor: .orange,
                    title: "Names",
                    description: "Add names of people, places, or organizations that may be mentioned."
                )

                TipRow(
                    icon: "wrench.and.screwdriver",
                    iconColor: .orange,
                    title: "Technical Terms",
                    description: "Include industry jargon, acronyms, or specialized terminology."
                )

                TipRow(
                    icon: "building.2",
                    iconColor: .orange,
                    title: "Brands & Products",
                    description: "Add brand names, product names, or company names."
                )
            }
            .padding()
            .background(Color(NSColor.textBackgroundColor).opacity(0.3))
            .cornerRadius(8)
        }
    }

    private var warningBanner: some View {
        HStack(alignment: .top, spacing: 8) {
            Image(systemName: "exclamationmark.triangle.fill")
                .foregroundStyle(.yellow)

            Text("Warning: Too many terms may cause the model to focus excessively on dictionary words. If transcription quality suffers, try reducing the vocabulary size.")
                .font(.caption)
                .foregroundStyle(.yellow)
        }
        .padding()
        .background(Color.yellow.opacity(0.15))
        .cornerRadius(8)
        .padding(.top, 16)
    }

    // MARK: - Actions

    private func addCurrentWord() {
        let trimmed = newWord.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }

        dictionaryService.addWord(trimmed)
        newWord = ""
    }
}

// MARK: - Tip Row

struct TipRow: View {
    let icon: String
    let iconColor: Color
    let title: String
    let description: String

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: icon)
                .font(.title3)
                .foregroundStyle(iconColor)
                .frame(width: 24)

            VStack(alignment: .leading, spacing: 4) {
                Text(title)
                    .font(.headline)

                Text(description)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
    }
}

// MARK: - Preview

#Preview {
    VocabularyView()
}
