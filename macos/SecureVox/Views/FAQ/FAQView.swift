import SwiftUI

/// FAQ View with expandable questions and answers
struct FAQView: View {

    // MARK: - State

    @State private var expandedQuestions: Set<String> = []
    @State private var searchText = ""

    // MARK: - Body

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 24) {
                // Header
                VStack(alignment: .leading, spacing: 8) {
                    Text("Frequently Asked Questions")
                        .font(.title2)
                        .fontWeight(.semibold)

                    Text("Find answers to common questions about SecureVox")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }

                // Search
                HStack {
                    Image(systemName: "magnifyingglass")
                        .foregroundStyle(.secondary)
                    TextField("Search FAQs...", text: $searchText)
                        .textFieldStyle(.plain)
                }
                .padding(10)
                .background(Color(NSColor.textBackgroundColor).opacity(0.3))
                .cornerRadius(8)

                // FAQ Categories
                ForEach(FAQCategory.allCases) { category in
                    faqSection(category)
                }

                // Contact Support
                supportSection
            }
            .padding()
        }
        .navigationTitle("FAQ")
        .frame(minWidth: 500)
    }

    // MARK: - Sections

    private func faqSection(_ category: FAQCategory) -> some View {
        let filteredQuestions = category.questions.filter { question in
            if searchText.isEmpty { return true }
            return question.question.localizedCaseInsensitiveContains(searchText) ||
                   question.answer.localizedCaseInsensitiveContains(searchText)
        }

        return Group {
            if !filteredQuestions.isEmpty {
                VStack(alignment: .leading, spacing: 12) {
                    // Category header
                    HStack(spacing: 8) {
                        Image(systemName: category.icon)
                            .foregroundStyle(.orange)
                        Text(category.rawValue)
                            .font(.headline)
                    }

                    // Questions
                    VStack(spacing: 0) {
                        ForEach(filteredQuestions) { faq in
                            FAQRow(
                                faq: faq,
                                isExpanded: expandedQuestions.contains(faq.id),
                                onToggle: {
                                    withAnimation(.easeInOut(duration: 0.2)) {
                                        if expandedQuestions.contains(faq.id) {
                                            expandedQuestions.remove(faq.id)
                                        } else {
                                            expandedQuestions.insert(faq.id)
                                        }
                                    }
                                }
                            )

                            if faq.id != filteredQuestions.last?.id {
                                Divider()
                            }
                        }
                    }
                    .padding()
                    .background(Color(NSColor.textBackgroundColor).opacity(0.3))
                    .cornerRadius(8)
                }
            }
        }
    }

    private var supportSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Still have questions?")
                .font(.headline)

            HStack(spacing: 16) {
                Button {
                    if let url = URL(string: "mailto:support@securevox.app") {
                        NSWorkspace.shared.open(url)
                    }
                } label: {
                    Label("Email Support", systemImage: "envelope")
                }
                .buttonStyle(.borderedProminent)

                Button {
                    // Open documentation
                } label: {
                    Label("Documentation", systemImage: "book")
                }
                .buttonStyle(.bordered)
            }
        }
        .padding()
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.orange.opacity(0.1))
        .cornerRadius(8)
    }
}

// MARK: - FAQ Row

struct FAQRow: View {
    let faq: FAQItem
    let isExpanded: Bool
    let onToggle: () -> Void

    var body: some View {
        Button(action: onToggle) {
            VStack(alignment: .leading, spacing: 8) {
                HStack {
                    Text(faq.question)
                        .font(.subheadline)
                        .fontWeight(.medium)
                        .multilineTextAlignment(.leading)

                    Spacer()

                    Image(systemName: isExpanded ? "chevron.up" : "chevron.down")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }

                if isExpanded {
                    Text(faq.answer)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.leading)
                        .padding(.top, 4)
                }
            }
            .padding(.vertical, 8)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

// MARK: - FAQ Data

struct FAQItem: Identifiable {
    let id: String
    let question: String
    let answer: String

    init(_ question: String, answer: String) {
        self.id = question
        self.question = question
        self.answer = answer
    }
}

enum FAQCategory: String, CaseIterable, Identifiable {
    case general = "General"
    case recording = "Recording"
    case transcription = "Transcription"
    case privacy = "Privacy & Security"
    case troubleshooting = "Troubleshooting"

    var id: String { rawValue }

    var icon: String {
        switch self {
        case .general: return "questionmark.circle"
        case .recording: return "mic"
        case .transcription: return "text.bubble"
        case .privacy: return "lock.shield"
        case .troubleshooting: return "wrench.and.screwdriver"
        }
    }

    var questions: [FAQItem] {
        switch self {
        case .general:
            return [
                FAQItem("What is SecureVox?",
                       answer: "SecureVox is a privacy-focused voice recording and transcription app that processes everything locally on your device. Your audio never leaves your Mac, ensuring complete privacy."),
                FAQItem("Is SecureVox free to use?",
                       answer: "SecureVox offers a free tier with basic features. Premium features like advanced models and unlimited transcription are available through a one-time purchase or subscription."),
                FAQItem("Does SecureVox work offline?",
                       answer: "Yes! SecureVox works completely offline. All transcription is done locally on your Mac using the Whisper AI model, so you don't need an internet connection."),
                FAQItem("What languages does SecureVox support?",
                       answer: "SecureVox supports over 90 languages for transcription, including English, Spanish, French, German, Japanese, Chinese, and many more. You can set automatic language detection or choose a specific language.")
            ]
        case .recording:
            return [
                FAQItem("How do I start a quick recording?",
                       answer: "Hold the Fn key to start a quick recording. Release to stop. The transcribed text will automatically be copied to your clipboard."),
                FAQItem("What's the maximum recording length?",
                       answer: "SecureVox supports recordings up to 4 hours in length. For longer recordings, we recommend splitting them into multiple sessions."),
                FAQItem("Can I import existing audio files?",
                       answer: "Yes! You can import audio files (MP3, WAV, M4A, etc.) and video files (MP4, MOV) for transcription. Use File > Import Audio or drag and drop files into the app."),
                FAQItem("How do I record meetings?",
                       answer: "Enable Meeting Detection in Settings to automatically detect Zoom, Teams, and Google Meet. SecureVox can capture system audio and your microphone simultaneously.")
            ]
        case .transcription:
            return [
                FAQItem("How accurate is the transcription?",
                       answer: "SecureVox uses OpenAI's Whisper model, which provides state-of-the-art accuracy. The Large V3 Turbo model offers the best accuracy, while the Small model is faster but slightly less accurate."),
                FAQItem("What's the difference between Small and Large models?",
                       answer: "The Small model (~600 MB) is fast and uses less memory. The Large V3 Turbo model (~3 GB) is more accurate but slower and uses more VRAM. Both work offline."),
                FAQItem("Can I add custom vocabulary?",
                       answer: "Yes! Use the Vocabulary section to add custom words, names, and technical terms. This helps improve transcription accuracy for specialized content."),
                FAQItem("Why is transcription slow on my Mac?",
                       answer: "Transcription speed depends on your Mac's hardware. Apple Silicon Macs (M1/M2/M3) perform best. The Small model is recommended for older Intel Macs.")
            ]
        case .privacy:
            return [
                FAQItem("Is my data sent to the cloud?",
                       answer: "No. SecureVox processes everything locally on your Mac. Your recordings and transcripts never leave your device. We don't have access to your data."),
                FAQItem("Where are my recordings stored?",
                       answer: "Recordings are stored in your Mac's Documents folder in the SecureVox directory. You can access them directly through Finder."),
                FAQItem("Can I export my data?",
                       answer: "Yes! You can export transcripts in multiple formats (TXT, SRT, VTT, JSON). You can also export audio files directly."),
                FAQItem("How do I delete all my data?",
                       answer: "Go to Settings > Data Management > Delete All Recordings. This permanently removes all recordings and transcripts from your Mac.")
            ]
        case .troubleshooting:
            return [
                FAQItem("Microphone not working?",
                       answer: "Check System Preferences > Privacy & Security > Microphone and ensure SecureVox has permission. Try selecting a different microphone in Settings."),
                FAQItem("Transcription keeps failing?",
                       answer: "Try using a smaller Whisper model (Small instead of Large). Ensure you have enough available RAM and disk space. Restart the app if issues persist."),
                FAQItem("Meeting recording not capturing audio?",
                       answer: "SecureVox requires Screen Recording permission to capture system audio. Go to System Preferences > Privacy & Security > Screen Recording and enable SecureVox."),
                FAQItem("App crashes on startup?",
                       answer: "Try resetting preferences by holding Option while launching the app. If issues persist, contact support with your macOS version and Mac model.")
            ]
        }
    }
}

// MARK: - Preview

#Preview {
    FAQView()
}
