import SwiftUI
import PhotosUI

#if os(iOS)
/// SwiftUI wrapper for PHPickerViewController to select videos from the photo library
struct PhotosPickerView: UIViewControllerRepresentable {

    // MARK: - Properties

    let onPick: (PHPickerResult) -> Void
    let onCancel: () -> Void

    // MARK: - Configuration

    var selectionLimit: Int = 1
    var filter: PHPickerFilter = .videos

    // MARK: - UIViewControllerRepresentable

    func makeUIViewController(context: Context) -> PHPickerViewController {
        var configuration = PHPickerConfiguration(photoLibrary: .shared())
        configuration.selectionLimit = selectionLimit
        configuration.filter = filter
        configuration.preferredAssetRepresentationMode = .current

        let picker = PHPickerViewController(configuration: configuration)
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_ uiViewController: PHPickerViewController, context: Context) {
        // No updates needed
    }

    func makeCoordinator() -> Coordinator {
        Coordinator(onPick: onPick, onCancel: onCancel)
    }

    // MARK: - Coordinator

    class Coordinator: NSObject, PHPickerViewControllerDelegate {
        let onPick: (PHPickerResult) -> Void
        let onCancel: () -> Void

        init(onPick: @escaping (PHPickerResult) -> Void, onCancel: @escaping () -> Void) {
            self.onPick = onPick
            self.onCancel = onCancel
        }

        func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
            picker.dismiss(animated: true)

            guard let result = results.first else {
                onCancel()
                return
            }

            onPick(result)
        }
    }
}
#elseif os(macOS)
import AppKit
import UniformTypeIdentifiers

/// macOS version using NSOpenPanel for video selection
struct PhotosPickerView: View {
    let onPick: (PHPickerResult) -> Void
    let onCancel: () -> Void
    var selectionLimit: Int = 1
    var filter: PHPickerFilter = .videos

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
        panel.allowedContentTypes = [.movie, .video, .quickTimeMovie, .mpeg4Movie]
        panel.message = "Select a video file to import"
        panel.prompt = "Import"

        panel.begin { response in
            if response == .OK, let url = panel.url {
                // Create a PHPickerResult-like wrapper for macOS
                let result = PHPickerResult(itemProvider: NSItemProvider(contentsOf: url)!)
                onPick(result)
            } else {
                onCancel()
            }
        }
    }
}
#endif

// MARK: - Video Picker Convenience

/// Convenience wrapper specifically for video selection
struct VideoPickerView: View {
    @Binding var isPresented: Bool
    let onVideoPicked: (PHPickerResult) -> Void

    var body: some View {
        EmptyView()
            .sheet(isPresented: $isPresented) {
                PhotosPickerView(
                    onPick: { result in
                        isPresented = false
                        onVideoPicked(result)
                    },
                    onCancel: {
                        isPresented = false
                    },
                    filter: .videos
                )
                .ignoresSafeArea()
            }
    }
}

// MARK: - Preview

#Preview {
    Text("Photos Picker")
        .sheet(isPresented: .constant(true)) {
            PhotosPickerView(
                onPick: { result in
                    print("Selected: \(result.itemProvider)")
                },
                onCancel: {
                    print("Cancelled")
                }
            )
        }
}
