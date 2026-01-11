import SwiftUI

/// View for managing custom dictionary words
struct CustomDictionaryView: View {

    // MARK: - Environment

    @Environment(\.dismiss) private var dismiss

    // MARK: - State

    @StateObject private var dictionaryService = CustomDictionaryService.shared
    @State private var newWord = ""
    @State private var showingImportSheet = false
    @FocusState private var isTextFieldFocused: Bool

    // MARK: - Body

    var body: some View {
        NavigationStack {
            List {
                // Words section
                Section {
                    // Text field for adding words
                    VStack(alignment: .leading, spacing: 8) {
                        // Show existing words
                        if dictionaryService.words.isEmpty {
                            // Placeholder examples when empty
                            Text("Steve Wozniak")
                                .foregroundStyle(.secondary.opacity(0.5))
                            Text("LLama-3")
                                .foregroundStyle(.secondary.opacity(0.5))
                        } else {
                            // Show actual words
                            ForEach(dictionaryService.words, id: \.self) { word in
                                Text(word)
                                    .foregroundStyle(.primary)
                            }
                        }

                        // Input field
                        HStack {
                            TextField("Add word...", text: $newWord)
                                .textInputAutocapitalization(.words)
                                .autocorrectionDisabled()
                                .focused($isTextFieldFocused)
                                .onSubmit {
                                    addCurrentWord()
                                }

                            if !newWord.isEmpty {
                                Button {
                                    addCurrentWord()
                                } label: {
                                    Image(systemName: "plus.circle.fill")
                                        .foregroundStyle(.blue)
                                }
                            }
                        }
                        .padding(.top, 8)
                    }
                    .frame(minHeight: 100, alignment: .topLeading)
                    .overlay(alignment: .bottomTrailing) {
                        if dictionaryService.words.isEmpty && newWord.isEmpty {
                            Button {
                                isTextFieldFocused = true
                            } label: {
                                Image(systemName: "pencil")
                                    .font(.body)
                                    .foregroundStyle(.secondary)
                            }
                        }
                    }
                } header: {
                    Text("Custom Dictionary")
                } footer: {
                    VStack(alignment: .leading, spacing: 12) {
                        Text("Add names, technical terms, or specialized vocabulary to improve accuracy—like 'Steve Wozniak', 'CRISPR-Cas9', or 'Llama-2'.")
                            .foregroundStyle(.secondary)

                        HStack(alignment: .top, spacing: 6) {
                            Image(systemName: "exclamationmark.triangle.fill")
                                .foregroundStyle(.yellow)
                            Text("Too many terms can reduce transcription quality. If accuracy suffers, try using fewer dictionary words.")
                                .foregroundStyle(.yellow)
                        }
                    }
                }

                // Manage words section (only show if there are words)
                if !dictionaryService.words.isEmpty {
                    Section {
                        ForEach(dictionaryService.words, id: \.self) { word in
                            Text(word)
                        }
                        .onDelete { offsets in
                            dictionaryService.removeWords(at: offsets)
                        }
                    } header: {
                        Text("Words (\(dictionaryService.words.count))")
                    }
                }
            }
            .navigationTitle("Custom Dictionary")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") {
                        dismiss()
                    }
                }

                ToolbarItem(placement: .topBarLeading) {
                    Menu {
                        Button {
                            showingImportSheet = true
                        } label: {
                            Label("Import Words", systemImage: "square.and.arrow.down")
                        }

                        if !dictionaryService.words.isEmpty {
                            Button {
                                exportWords()
                            } label: {
                                Label("Export Words", systemImage: "square.and.arrow.up")
                            }

                            Divider()

                            Button(role: .destructive) {
                                dictionaryService.clearAll()
                            } label: {
                                Label("Clear All", systemImage: "trash")
                            }
                        }
                    } label: {
                        Image(systemName: "ellipsis.circle")
                    }
                }
            }
            .sheet(isPresented: $showingImportSheet) {
                ImportWordsSheet { text in
                    dictionaryService.importWords(from: text)
                }
            }
        }
    }

    // MARK: - Methods

    private func addCurrentWord() {
        let trimmed = newWord.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }

        dictionaryService.addWord(trimmed)
        newWord = ""
    }

    private func exportWords() {
        let text = dictionaryService.exportWords()
        #if os(iOS)
        UIPasteboard.general.string = text
        #elseif os(macOS)
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(text, forType: .string)
        #endif
    }
}

// MARK: - Import Words Sheet

private struct ImportWordsSheet: View {
    @Environment(\.dismiss) private var dismiss

    @State private var importText = ""
    @State private var importResult: Int?

    let onImport: (String) -> Int

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextEditor(text: $importText)
                        .frame(minHeight: 150)
                        .font(.system(.body, design: .monospaced))
                } header: {
                    Text("Words to Import")
                } footer: {
                    Text("Enter one word or phrase per line.")
                }

                if let result = importResult {
                    Section {
                        Label("Imported \(result) word(s)", systemImage: "checkmark.circle.fill")
                            .foregroundStyle(.green)
                    }
                }
            }
            .navigationTitle("Import Words")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") {
                        dismiss()
                    }
                }

                ToolbarItem(placement: .confirmationAction) {
                    Button("Import") {
                        let count = onImport(importText)
                        importResult = count
                        if count > 0 {
                            DispatchQueue.main.asyncAfter(deadline: .now() + 1) {
                                dismiss()
                            }
                        }
                    }
                    .disabled(importText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            }
        }
        .presentationDetents([.medium, .large])
    }
}

// MARK: - Preview

#Preview {
    CustomDictionaryView()
}
