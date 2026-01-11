import SwiftUI

/// Language selection picker
struct LanguagePickerView: View {

    // MARK: - Environment

    @Environment(\.dismiss) private var dismiss

    // MARK: - Properties

    @Binding var selectedLanguage: String

    // MARK: - State

    @State private var searchText = ""

    // MARK: - Body

    var body: some View {
        NavigationStack {
            List {
                // Common languages section
                Section {
                    ForEach(WhisperLanguage.commonLanguages) { language in
                        LanguageRow(
                            language: language,
                            isSelected: selectedLanguage == language.code,
                            onSelect: { selectLanguage(language) }
                        )
                    }
                } header: {
                    Text("Common")
                }

                // All languages section
                Section {
                    ForEach(filteredLanguages) { language in
                        LanguageRow(
                            language: language,
                            isSelected: selectedLanguage == language.code,
                            onSelect: { selectLanguage(language) }
                        )
                    }
                } header: {
                    Text("All Languages")
                }
            }
            .navigationTitle("Select Language")
            .navigationBarTitleDisplayMode(.inline)
            .searchable(text: $searchText, prompt: "Search languages")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Done") {
                        dismiss()
                    }
                }
            }
        }
    }

    // MARK: - Computed Properties

    private var filteredLanguages: [WhisperLanguage] {
        let allExceptCommon = WhisperLanguage.allLanguages.filter { language in
            !WhisperLanguage.commonLanguages.contains { $0.code == language.code }
        }

        if searchText.isEmpty {
            return allExceptCommon
        }

        return allExceptCommon.filter { language in
            language.name.localizedCaseInsensitiveContains(searchText) ||
            language.localizedName.localizedCaseInsensitiveContains(searchText) ||
            language.code.localizedCaseInsensitiveContains(searchText)
        }
    }

    // MARK: - Methods

    private func selectLanguage(_ language: WhisperLanguage) {
        selectedLanguage = language.code
        dismiss()
    }
}

// MARK: - Language Row

private struct LanguageRow: View {
    let language: WhisperLanguage
    let isSelected: Bool
    let onSelect: () -> Void

    var body: some View {
        Button {
            onSelect()
        } label: {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text(language.localizedName)
                        .foregroundStyle(.primary)

                    if language.code != "auto" {
                        Text(language.code.uppercased())
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }

                Spacer()

                if isSelected {
                    Image(systemName: "checkmark")
                        .foregroundStyle(.blue)
                }
            }
        }
    }
}

// MARK: - Preview

#Preview {
    LanguagePickerView(selectedLanguage: .constant("en"))
}
