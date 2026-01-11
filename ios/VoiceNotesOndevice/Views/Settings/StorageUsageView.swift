import SwiftUI

/// Detailed storage usage view
struct StorageUsageView: View {

    // MARK: - Properties

    let storageInfo: SettingsViewModel.StorageInfo

    // MARK: - Body

    var body: some View {
        List {
            Section {
                StorageBar(
                    used: storageInfo.totalAudioSize,
                    available: storageInfo.availableSpace
                )
                .listRowInsets(EdgeInsets())
                .padding()
            }

            Section {
                LabeledContent("Audio Recordings", value: "\(storageInfo.totalRecordings)")
                LabeledContent("Total Audio Size", value: storageInfo.formattedAudioSize)
                LabeledContent("Total Duration", value: storageInfo.formattedDuration)
            } header: {
                Text("Usage")
            }

            Section {
                LabeledContent("Available Space", value: storageInfo.formattedAvailableSpace)
            } header: {
                Text("Device Storage")
            }

            Section {
                Text("Tips to free up space:")
                    .font(.subheadline)
                    .fontWeight(.medium)

                TipRow(icon: "speaker.slash", text: "Delete audio files after transcription")
                TipRow(icon: "trash", text: "Remove old recordings you no longer need")
                TipRow(icon: "gear", text: "Enable auto-delete in Settings")
            } header: {
                Text("Tips")
            }
        }
        .navigationTitle("Storage Usage")
        .navigationBarTitleDisplayMode(.inline)
    }
}

// MARK: - Storage Bar

private struct StorageBar: View {
    let used: Int64
    let available: Int64

    private var total: Int64 { used + available }
    private var usedFraction: Double {
        guard total > 0 else { return 0 }
        return Double(used) / Double(total)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            GeometryReader { geometry in
                ZStack(alignment: .leading) {
                    RoundedRectangle(cornerRadius: 4)
                        .fill(Color.secondary.opacity(0.2))

                    RoundedRectangle(cornerRadius: 4)
                        .fill(Color.blue)
                        .frame(width: geometry.size.width * usedFraction)
                }
            }
            .frame(height: 8)

            HStack {
                Label {
                    Text("SecureVox")
                        .font(.caption)
                } icon: {
                    Circle()
                        .fill(Color.blue)
                        .frame(width: 8, height: 8)
                }

                Spacer()

                Text(ByteCountFormatter.string(fromByteCount: used, countStyle: .file))
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
    }
}

// MARK: - Tip Row

private struct TipRow: View {
    let icon: String
    let text: String

    var body: some View {
        Label {
            Text(text)
                .font(.subheadline)
        } icon: {
            Image(systemName: icon)
                .foregroundStyle(.secondary)
        }
    }
}

// MARK: - Preview

#Preview {
    NavigationStack {
        StorageUsageView(
            storageInfo: SettingsViewModel.StorageInfo(
                totalRecordings: 15,
                totalAudioSize: 150_000_000,
                totalDuration: 3600,
                availableSpace: 10_000_000_000
            )
        )
    }
}
