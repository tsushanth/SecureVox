import SwiftUI

/// Empty state view shown when there are no recordings
struct EmptyLibraryView: View {

    // MARK: - Properties

    let onRecord: () -> Void
    let onImport: () -> Void

    // MARK: - Body

    var body: some View {
        ContentUnavailableView {
            Label("No Recordings", systemImage: "waveform")
        } description: {
            Text("Record audio or import media to get started with offline transcription.")
        } actions: {
            VStack(spacing: 12) {
                Button {
                    onRecord()
                } label: {
                    Label("Record Audio", systemImage: "mic.fill")
                        .frame(minWidth: 160)
                }
                .buttonStyle(.borderedProminent)

                Button {
                    onImport()
                } label: {
                    Label("Import Media", systemImage: "square.and.arrow.down")
                        .frame(minWidth: 160)
                }
                .buttonStyle(.bordered)
            }
        }
    }
}

// MARK: - Preview

#Preview {
    EmptyLibraryView(
        onRecord: {},
        onImport: {}
    )
}
