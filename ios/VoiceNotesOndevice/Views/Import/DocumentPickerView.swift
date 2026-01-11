import SwiftUI
import UniformTypeIdentifiers

// MARK: - Supported Types (shared)

private let documentPickerSupportedTypes: [UTType] = [
    // Audio
    .mp3,
    .mpeg4Audio,
    .wav,
    .aiff,
    // Video
    .mpeg4Movie,
    .quickTimeMovie,
    .movie,
    // Generic audio/video
    .audio,
    .audiovisualContent
]

#if os(iOS)
/// SwiftUI wrapper for UIDocumentPickerViewController
struct DocumentPickerView: UIViewControllerRepresentable {

    // MARK: - Properties

    let onPick: (URL) -> Void

    // MARK: - Supported Types

    static let supportedTypes: [UTType] = documentPickerSupportedTypes

    // MARK: - UIViewControllerRepresentable

    func makeUIViewController(context: Context) -> UIDocumentPickerViewController {
        let picker = UIDocumentPickerViewController(forOpeningContentTypes: Self.supportedTypes)
        picker.delegate = context.coordinator
        picker.allowsMultipleSelection = false
        return picker
    }

    func updateUIViewController(_ uiViewController: UIDocumentPickerViewController, context: Context) {
        // No updates needed
    }

    func makeCoordinator() -> Coordinator {
        Coordinator(onPick: onPick)
    }

    // MARK: - Coordinator

    class Coordinator: NSObject, UIDocumentPickerDelegate {
        let onPick: (URL) -> Void

        init(onPick: @escaping (URL) -> Void) {
            self.onPick = onPick
        }

        func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
            guard let url = urls.first else { return }

            // Start accessing security-scoped resource
            guard url.startAccessingSecurityScopedResource() else {
                return
            }

            // Copy file to app's temp directory before processing
            defer {
                url.stopAccessingSecurityScopedResource()
            }

            onPick(url)
        }

        func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) {
            // User cancelled, no action needed
        }
    }
}
#elseif os(macOS)
import AppKit

/// macOS version using NSOpenPanel for document selection
struct DocumentPickerView: View {

    // MARK: - Properties

    let onPick: (URL) -> Void

    // MARK: - Supported Types

    static let supportedTypes: [UTType] = documentPickerSupportedTypes

    var body: some View {
        Color.clear
            .onAppear {
                showOpenPanel()
            }
    }

    private func showOpenPanel() {
        let panel = NSOpenPanel()
        panel.allowsMultipleSelection = false
        panel.canChooseDirectories = false
        panel.canChooseFiles = true
        panel.allowedContentTypes = Self.supportedTypes
        panel.message = "Select an audio or video file to import"
        panel.prompt = "Import"

        panel.begin { response in
            if response == .OK, let url = panel.url {
                // Start accessing security-scoped resource if needed
                let accessing = url.startAccessingSecurityScopedResource()
                defer {
                    if accessing {
                        url.stopAccessingSecurityScopedResource()
                    }
                }
                onPick(url)
            }
        }
    }
}
#endif

// MARK: - Preview

#Preview {
    Text("Document Picker")
        .sheet(isPresented: .constant(true)) {
            DocumentPickerView { url in
                print("Selected: \(url)")
            }
        }
}
