import SwiftUI

/// Help and frequently asked questions screen
struct FAQView: View {

    // MARK: - State

    @State private var searchText = ""
    @State private var expandedItems: Set<String> = []

    // MARK: - Body

    var body: some View {
        List {
            ForEach(filteredSections) { section in
                Section(section.title) {
                    ForEach(section.items) { item in
                        FAQItemRow(
                            item: item,
                            isExpanded: expandedItems.contains(item.id),
                            onToggle: { toggleItem(item.id) }
                        )
                    }
                }
            }
        }
        .navigationTitle("FAQ & Help")
        .searchable(text: $searchText, prompt: "Search help topics")
    }

    // MARK: - Data

    private var faqSections: [FAQSection] {
        [
            FAQSection(title: "Getting Started", items: [
                FAQItem(
                    id: "how-to-record",
                    question: "How do I record audio?",
                    answer: "Tap the + button in the top right corner and select \"Record\". Grant microphone permission when prompted. Tap the red record button to start, and tap stop when finished. Your recording will appear in the library."
                ),
                FAQItem(
                    id: "import-video",
                    question: "How do I import a video from my Camera Roll?",
                    answer: "Tap the + button and select \"Import\". Choose \"Photo Library\" to select videos from your Camera Roll. The app will extract the audio track for transcription."
                ),
                FAQItem(
                    id: "import-audio",
                    question: "Can I import audio files?",
                    answer: "Yes! Tap the + button, select \"Import\", then \"Files\". You can import MP3, M4A, WAV, and most common audio formats from the Files app, iCloud Drive, or other sources."
                )
            ]),

            FAQSection(title: "Transcription", items: [
                FAQItem(
                    id: "how-long",
                    question: "How long does transcription take?",
                    answer: "With the Tiny model, transcription runs about 10x faster than realtime (a 10-minute recording takes ~1 minute). The Small model is more accurate but slower at about 2x realtime. Processing happens entirely on your device."
                ),
                FAQItem(
                    id: "language-support",
                    question: "What languages are supported?",
                    answer: "Whisper supports 99 languages including English, Spanish, French, German, Chinese, Japanese, Korean, Arabic, and many more. Auto-detect works for most content, or you can manually select a language in Settings for better accuracy."
                ),
                FAQItem(
                    id: "accuracy",
                    question: "How accurate is the transcription?",
                    answer: "Accuracy depends on audio quality, background noise, accents, and the model used. The Small model provides the best accuracy. Clear audio with minimal background noise produces the best results. You can edit transcripts after processing."
                ),
                FAQItem(
                    id: "no-internet",
                    question: "Does this work offline?",
                    answer: "Yes! All transcription happens on your device using Apple's Neural Engine. No internet connection is required, and your audio never leaves your device."
                )
            ]),

            FAQSection(title: "Export & Sharing", items: [
                FAQItem(
                    id: "export-formats",
                    question: "What export formats are available?",
                    answer: "• Plain Text (.txt) - Simple text without timestamps\n• SubRip (.srt) - Industry-standard subtitle format\n• WebVTT (.vtt) - Web-compatible subtitle format\n\nSRT and VTT include timestamps and can be used as subtitles in video editors or media players."
                ),
                FAQItem(
                    id: "share-transcript",
                    question: "How do I share a transcript?",
                    answer: "Open a recording, tap the menu button (...), and select \"Export Transcript\". Choose your format and use the share sheet to send via Messages, Mail, AirDrop, or save to Files."
                )
            ]),

            FAQSection(title: "Storage & Privacy", items: [
                FAQItem(
                    id: "storage-usage",
                    question: "How much storage do recordings use?",
                    answer: "Audio recordings use about 1 MB per minute. The Whisper models use 75-500 MB depending on which you select. You can delete audio files after transcription to save space while keeping your transcripts."
                ),
                FAQItem(
                    id: "privacy",
                    question: "Is my data private?",
                    answer: "Absolutely. All processing happens on-device. Your audio and transcripts never leave your iPhone. We don't collect any usage data or analytics. Your recordings are stored only in the app's private container."
                ),
                FAQItem(
                    id: "delete-audio",
                    question: "Can I delete the audio but keep the transcript?",
                    answer: "Yes! Open a recording, tap the menu (...), and select \"Delete Audio File\". The transcript remains. You can also enable \"Auto-Delete Audio\" in Settings to automatically remove audio after transcription."
                )
            ]),

            FAQSection(title: "Troubleshooting", items: [
                FAQItem(
                    id: "transcription-failed",
                    question: "Why did my transcription fail?",
                    answer: "Common causes:\n• Corrupted audio file - try re-recording or re-importing\n• Very short audio (< 1 second) - Whisper needs more content\n• Unsupported format - convert to MP3 or M4A first\n• Low memory - close other apps and try again\n\nTap \"Retry\" on the failed recording to try again."
                ),
                FAQItem(
                    id: "poor-quality",
                    question: "The transcription quality is poor. What can I do?",
                    answer: "Try these tips:\n• Use the Small model for better accuracy (Settings > Model)\n• Select the correct language instead of auto-detect\n• Record in a quieter environment\n• Speak clearly and at a moderate pace\n• Keep the microphone 6-12 inches from your mouth"
                ),
                FAQItem(
                    id: "app-crashes",
                    question: "The app crashes during transcription",
                    answer: "This usually happens when the device runs out of memory. Try:\n• Close other apps before transcribing\n• Use the Tiny model instead of Small\n• Restart your device\n• For very long recordings (2+ hours), consider splitting the audio into smaller files"
                )
            ])
        ]
    }

    private var filteredSections: [FAQSection] {
        guard !searchText.isEmpty else { return faqSections }

        return faqSections.compactMap { section in
            let filteredItems = section.items.filter { item in
                item.question.localizedCaseInsensitiveContains(searchText) ||
                item.answer.localizedCaseInsensitiveContains(searchText)
            }

            guard !filteredItems.isEmpty else { return nil }
            return FAQSection(title: section.title, items: filteredItems)
        }
    }

    private func toggleItem(_ id: String) {
        if expandedItems.contains(id) {
            expandedItems.remove(id)
        } else {
            expandedItems.insert(id)
        }
    }
}

// MARK: - Supporting Types

struct FAQSection: Identifiable {
    let id = UUID()
    let title: String
    let items: [FAQItem]
}

struct FAQItem: Identifiable {
    let id: String
    let question: String
    let answer: String
}

// MARK: - FAQ Item Row

private struct FAQItemRow: View {
    let item: FAQItem
    let isExpanded: Bool
    let onToggle: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Button {
                withAnimation(.easeInOut(duration: 0.2)) {
                    onToggle()
                }
            } label: {
                HStack {
                    Text(item.question)
                        .font(.body)
                        .foregroundStyle(.primary)
                        .multilineTextAlignment(.leading)

                    Spacer()

                    Image(systemName: "chevron.right")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .rotationEffect(.degrees(isExpanded ? 90 : 0))
                }
            }
            .buttonStyle(.plain)

            if isExpanded {
                Text(item.answer)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .padding(.top, 4)
            }
        }
        .padding(.vertical, 4)
    }
}

// MARK: - Preview

#Preview {
    NavigationStack {
        FAQView()
    }
}
