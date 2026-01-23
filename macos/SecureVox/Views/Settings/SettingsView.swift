import SwiftUI
import ServiceManagement

/// App settings view
struct SettingsView: View {

    // MARK: - Environment

    @Environment(\.colorScheme) private var systemColorScheme

    // MARK: - Services

    @StateObject private var launchAtLoginService = LaunchAtLoginService.shared

    // MARK: - Settings State

    @AppStorage("hideDockIcon") private var hideDockIcon = false
    @AppStorage("theme") private var theme = "System"

    // Computed color scheme based on theme setting
    var preferredColorScheme: ColorScheme? {
        switch theme {
        case "Light": return .light
        case "Dark": return .dark
        default: return nil // System
        }
    }
    @AppStorage("microphone") private var microphone = "Automatic (System Default)"
    @AppStorage("language") private var language = "Auto"
    @AppStorage("transcriptionMode") private var transcriptionMode = "Accurate (1.2x slower)"
    @AppStorage("autoCopyToClipboard") private var autoCopyToClipboard = true
    @AppStorage("deleteOversizedAudioFiles") private var deleteOversizedAudioFiles = false

    // Meeting recording
    @AppStorage("meetingDetectionEnabled") private var meetingDetectionEnabled = false
    @AppStorage("autoRecordMeetings") private var autoRecordMeetings = false
    @AppStorage("recordMicrophone") private var recordMicrophone = true
    @AppStorage("detectZoom") private var detectZoom = true
    @AppStorage("detectTeams") private var detectTeams = true
    @AppStorage("detectGoogleMeet") private var detectGoogleMeet = true

    // Model selection
    @StateObject private var transcriptionService = TranscriptionService.shared
    @State private var isDownloadingModel = false
    @State private var downloadError: String?

    // MARK: - Body

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 24) {
                // General section
                generalSection

                // Appearance section
                appearanceSection

                // Transcription section
                transcriptionSection

                // Model selection section
                modelSection

                // Behavior section
                behaviorSection

                // Meeting Recording section
                meetingRecordingSection

                // Feedback & Support section
                feedbackSection
            }
            .padding()
        }
        .navigationTitle("Settings")
        .frame(minWidth: 500)
    }

    // MARK: - Sections

    private func sectionHeader(_ title: String) -> some View {
        Text(title)
            .font(.caption)
            .foregroundStyle(.secondary)
            .textCase(.uppercase)
            .padding(.bottom, 4)
    }

    private var generalSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            sectionHeader("GENERAL")

            VStack(spacing: 12) {
                // Launch at Login with SMAppService
                LaunchAtLoginRow(service: launchAtLoginService)

                SettingsToggleRow(
                    title: "Hide Dock icon",
                    description: "Hide the app from Dock and show only in menu bar. Access the app via the menu bar icon.",
                    isOn: $hideDockIcon
                )
            }
            .padding()
            .background(Color(NSColor.textBackgroundColor).opacity(0.3))
            .cornerRadius(8)
        }
    }

    private var appearanceSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            sectionHeader("APPEARANCE")

            VStack(spacing: 12) {
                SettingsPickerRow(
                    title: "Theme",
                    selection: $theme,
                    options: ["System", "Light", "Dark"]
                )
            }
            .padding()
            .background(Color(NSColor.textBackgroundColor).opacity(0.3))
            .cornerRadius(8)
        }
    }

    private var transcriptionSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            sectionHeader("TRANSCRIPTION")

            VStack(spacing: 12) {
                SettingsPickerRow(
                    title: "Microphone",
                    description: "Select preferred microphone for recording. 'Automatic' uses system default.",
                    selection: $microphone,
                    options: ["Automatic (System Default)", "MacBook Pro Microphone", "External Microphone"]
                )

                SettingsPickerRow(
                    title: "Language",
                    description: "Select the language for transcription. Choose 'Auto' to let Whisper detect automatically.",
                    selection: $language,
                    options: ["Auto", "English", "Spanish", "French", "German", "Japanese", "Chinese"]
                )

                SettingsPickerRow(
                    title: "Mode",
                    description: "Fast mode uses quick decoding for speed. Accurate mode uses full context for better quality.",
                    selection: $transcriptionMode,
                    options: ["Fast", "Accurate (1.2x slower)"]
                )
            }
            .padding()
            .background(Color(NSColor.textBackgroundColor).opacity(0.3))
            .cornerRadius(8)
        }
    }

    private var modelSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            sectionHeader("TRANSCRIPTION MODEL")

            VStack(spacing: 12) {
                ForEach(AppConstants.WhisperModel.allCases) { model in
                    ModelSelectionRow(
                        model: model,
                        isSelected: transcriptionService.selectedModel == model,
                        isAvailable: transcriptionService.isModelAvailable(model),
                        downloadProgress: transcriptionService.modelDownloadProgress[model.rawValue],
                        onSelect: {
                            if transcriptionService.isModelAvailable(model) {
                                transcriptionService.selectedModel = model
                            }
                        },
                        onDownload: {
                            Task {
                                do {
                                    try await transcriptionService.downloadModel(model)
                                    transcriptionService.selectedModel = model
                                } catch {
                                    downloadError = error.localizedDescription
                                }
                            }
                        }
                    )
                }

                if let error = downloadError {
                    HStack {
                        Image(systemName: "exclamationmark.triangle.fill")
                            .foregroundStyle(.red)
                        Text(error)
                            .font(.caption)
                            .foregroundStyle(.red)
                        Spacer()
                        Button("Dismiss") {
                            downloadError = nil
                        }
                        .font(.caption)
                    }
                    .padding(.top, 8)
                }
            }
            .padding()
            .background(Color(NSColor.textBackgroundColor).opacity(0.3))
            .cornerRadius(8)
        }
    }

    private var behaviorSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            sectionHeader("BEHAVIOR")

            VStack(spacing: 12) {
                SettingsToggleRow(
                    title: "Auto copy to clipboard",
                    description: "Automatically copy transcribed text when transcription completes.",
                    isOn: $autoCopyToClipboard
                )

                SettingsToggleRow(
                    title: "Delete oversized audio files",
                    description: "Audio files exceeding the size limit will be deleted, keeping only the text.",
                    isOn: $deleteOversizedAudioFiles
                )
            }
            .padding()
            .background(Color(NSColor.textBackgroundColor).opacity(0.3))
            .cornerRadius(8)
        }
    }

    private var meetingRecordingSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            sectionHeader("MEETING RECORDING")

            VStack(spacing: 12) {
                SettingsToggleRow(
                    title: "Enable Meeting Detection",
                    description: "Automatically detect and record meetings from Zoom, Teams, and Google Meet.",
                    isOn: $meetingDetectionEnabled
                )

                if meetingDetectionEnabled {
                    // System Audio Permission status
                    HStack {
                        Image(systemName: "checkmark.circle.fill")
                            .foregroundStyle(.green)
                        VStack(alignment: .leading) {
                            Text("System Audio Permission")
                                .font(.headline)
                            Text("Permission granted")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                        Spacer()
                        Image(systemName: "checkmark.circle.fill")
                            .foregroundStyle(.green)
                    }

                    SettingsToggleRow(
                        title: "Auto-record silently",
                        description: "Start recording automatically when a meeting is detected. If disabled, you'll be asked first.",
                        isOn: $autoRecordMeetings
                    )

                    SettingsToggleRow(
                        title: "Record microphone",
                        description: "Capture your voice along with meeting audio. Recommended for headphone users.",
                        isOn: $recordMicrophone
                    )

                    // App detection toggles
                    VStack(alignment: .leading, spacing: 8) {
                        Text("Detect these apps:")
                            .font(.caption)
                            .foregroundStyle(.secondary)

                        AppDetectionRow(name: "Zoom", icon: "video.fill", iconColor: .blue, isOn: $detectZoom)
                        AppDetectionRow(name: "Microsoft Teams", icon: "person.3.fill", iconColor: .purple, isOn: $detectTeams)
                        AppDetectionRow(name: "Google Meet", icon: "globe", iconColor: .green, isOn: $detectGoogleMeet, note: "(Chrome)")
                    }
                }
            }
            .padding()
            .background(Color(NSColor.textBackgroundColor).opacity(0.3))
            .cornerRadius(8)
        }
    }

    private var feedbackSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            sectionHeader("FEEDBACK & SUPPORT")

            VStack(spacing: 0) {
                FeedbackRow(icon: "star", title: "Review SecureVox", action: {
                    // Open App Store review page when available
                })
                Divider()
                FeedbackRow(icon: "heart", title: "Share SecureVox", action: {
                    // Share sheet
                })
                Divider()
                FeedbackRow(icon: "envelope", title: "Contact Support", detail: "kreativekoala.llc/contact", action: {
                    if let url = URL(string: "https://kreativekoala.llc/contact") {
                        NSWorkspace.shared.open(url)
                    }
                })
                Divider()
                FeedbackRow(icon: "globe", iconColor: .blue, title: "Website", detail: "kreativekoala.llc", action: {
                    if let url = URL(string: "https://kreativekoala.llc") {
                        NSWorkspace.shared.open(url)
                    }
                })
            }
            .padding()
            .background(Color(NSColor.textBackgroundColor).opacity(0.3))
            .cornerRadius(8)
        }
    }
}


// MARK: - Settings Toggle Row

struct SettingsToggleRow: View {
    let title: String
    var description: String? = nil
    @Binding var isOn: Bool

    var body: some View {
        Toggle(isOn: $isOn) {
            VStack(alignment: .leading, spacing: 4) {
                Text(title)

                if let description = description {
                    Text(description)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
        }
        .toggleStyle(.switch)
    }
}

// MARK: - Settings Picker Row

struct SettingsPickerRow: View {
    let title: String
    var description: String? = nil
    @Binding var selection: String
    let options: [String]

    var body: some View {
        HStack {
            VStack(alignment: .leading, spacing: 4) {
                Text(title)

                if let description = description {
                    Text(description)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }

            Spacer()

            Picker("", selection: $selection) {
                ForEach(options, id: \.self) { option in
                    Text(option).tag(option)
                }
            }
            .labelsHidden()
            .frame(width: 200)
        }
    }
}

// MARK: - Model Selection Row

struct ModelSelectionRow: View {
    let model: AppConstants.WhisperModel
    let isSelected: Bool
    let isAvailable: Bool
    var downloadProgress: Double?
    let onSelect: () -> Void
    let onDownload: () -> Void

    var body: some View {
        HStack {
            // Radio button / selection
            Button(action: onSelect) {
                HStack {
                    Image(systemName: isSelected ? "circle.inset.filled" : "circle")
                        .foregroundStyle(isSelected ? .orange : (isAvailable ? .secondary : .secondary.opacity(0.5)))

                    VStack(alignment: .leading, spacing: 4) {
                        HStack {
                            Text(model.displayName)
                                .font(.headline)
                                .foregroundStyle(isAvailable ? .primary : .secondary)

                            Text(model.subtitle)
                                .font(.caption)
                                .padding(.horizontal, 6)
                                .padding(.vertical, 2)
                                .background(model.isBundled ? Color.green.opacity(0.2) : Color.blue.opacity(0.2))
                                .foregroundStyle(model.isBundled ? .green : .blue)
                                .cornerRadius(4)

                            if model.isBundled {
                                Text("Built-in")
                                    .font(.caption)
                                    .padding(.horizontal, 6)
                                    .padding(.vertical, 2)
                                    .background(Color.green.opacity(0.2))
                                    .foregroundStyle(.green)
                                    .cornerRadius(4)
                            }
                        }

                        Text("\(model.approximateSpeed) · \(model.expectedRAM) · \(model.downloadSize)")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .disabled(!isAvailable)

            Spacer()

            // Download button or progress
            if !model.isBundled {
                if let progress = downloadProgress {
                    // Downloading
                    HStack(spacing: 8) {
                        ProgressView(value: progress)
                            .frame(width: 60)
                        Text("\(Int(progress * 100))%")
                            .font(.caption)
                            .monospacedDigit()
                    }
                } else if isAvailable {
                    // Downloaded
                    HStack(spacing: 4) {
                        Image(systemName: "checkmark.circle.fill")
                            .foregroundStyle(.green)
                        Text("Downloaded")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                } else {
                    // Need to download
                    Button(action: onDownload) {
                        HStack(spacing: 4) {
                            Image(systemName: "arrow.down.circle")
                            Text("Download")
                        }
                        .font(.caption)
                    }
                    .buttonStyle(.bordered)
                }
            }
        }
    }
}

// MARK: - App Detection Row

struct AppDetectionRow: View {
    let name: String
    let icon: String
    let iconColor: Color
    @Binding var isOn: Bool
    var note: String? = nil

    var body: some View {
        HStack {
            Image(systemName: icon)
                .foregroundStyle(iconColor)
                .frame(width: 20)

            Text(name)

            if let note = note {
                Text(note)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            Spacer()

            Toggle("", isOn: $isOn)
                .toggleStyle(.switch)
                .labelsHidden()
        }
    }
}

// MARK: - Feedback Row

struct FeedbackRow: View {
    let icon: String
    var iconColor: Color = .primary
    let title: String
    var detail: String? = nil
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack {
                Image(systemName: icon)
                    .foregroundStyle(iconColor)
                    .frame(width: 20)

                Text(title)

                Spacer()

                if let detail = detail {
                    Text(detail)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }

                Image(systemName: "chevron.right")
                    .font(.caption)
                    .foregroundStyle(.tertiary)
            }
            .padding(.vertical, 8)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

// MARK: - Launch at Login Row

struct LaunchAtLoginRow: View {
    @ObservedObject var service: LaunchAtLoginService

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Toggle(isOn: Binding(
                get: { service.isEnabled },
                set: { service.setEnabled($0) }
            )) {
                VStack(alignment: .leading, spacing: 4) {
                    Text("Launch at login")

                    Text("Automatically start SecureVox when you log in to your Mac.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
            .toggleStyle(.switch)

            // Show status message if requires approval
            if service.requiresUserAction {
                HStack(spacing: 8) {
                    Image(systemName: "exclamationmark.triangle.fill")
                        .foregroundStyle(.yellow)

                    Text("Requires approval in System Settings")
                        .font(.caption)
                        .foregroundStyle(.secondary)

                    Spacer()

                    Button("Open Settings") {
                        service.openLoginItemsSettings()
                    }
                    .font(.caption)
                    .buttonStyle(.bordered)
                }
            }
        }
    }
}

// MARK: - Preview

#Preview {
    SettingsView()
}
