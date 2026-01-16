import SwiftUI
import SwiftData

/// Main entry point for VoiceNotesOndevice app
@main
struct VoiceNotesOndeviceApp: App {

    // MARK: - Theme

    @AppStorage(AppConstants.UserDefaultsKeys.appTheme) private var appTheme: String = AppConstants.AppTheme.system.rawValue

    private var colorScheme: ColorScheme? {
        guard let theme = AppConstants.AppTheme(rawValue: appTheme) else { return nil }
        switch theme {
        case .system: return nil
        case .light: return .light
        case .dark: return .dark
        }
    }

    // MARK: - Deep Link State

    @StateObject private var deepLinkHandler = DeepLinkHandler.shared

    // MARK: - Model Container

    /// Result of attempting to create the model container
    private let modelContainerResult: Result<ModelContainer, Error>

    /// The model container if successfully created
    private var sharedModelContainer: ModelContainer? {
        try? modelContainerResult.get()
    }

    init() {
        let schema = Schema([
            Recording.self,
            TranscriptSegment.self
        ])
        let modelConfiguration = ModelConfiguration(schema: schema, isStoredInMemoryOnly: false)

        do {
            let container = try ModelContainer(for: schema, configurations: [modelConfiguration])
            modelContainerResult = .success(container)
        } catch {
            modelContainerResult = .failure(error)
        }
    }

    // MARK: - Body

    var body: some Scene {
        WindowGroup {
            if let container = sharedModelContainer {
                ContentView()
                    .preferredColorScheme(colorScheme)
                    .environmentObject(deepLinkHandler)
                    .onOpenURL { url in
                        handleDeepLink(url)
                    }
                    .modelContainer(container)
                    .task {
                        // Clean up expired recordings from recycle bin on app launch
                        await performStartupCleanup(container: container)
                    }
            } else {
                DatabaseErrorView(error: modelContainerResult.error)
                    .preferredColorScheme(colorScheme)
            }
        }
    }

    // MARK: - Startup Tasks

    @MainActor
    private func performStartupCleanup(container: ModelContainer) async {
        // Clean up expired recordings from recycle bin
        let cleanedCount = RecycleBinService.shared.cleanupExpiredRecordings(modelContext: container.mainContext)
        if cleanedCount > 0 {
            print("[App] Startup cleanup: removed \(cleanedCount) expired recording(s) from recycle bin")
        }

        // Clean up old temporary import files
        Task.detached {
            await MediaImportService.shared.cleanupTemporaryFiles()
        }
    }

    // MARK: - Deep Link Handling

    private func handleDeepLink(_ url: URL) {
        guard url.scheme == "securevox" else { return }

        switch url.host {
        case "record":
            // Set flag to start recording - will be picked up by RecordingsListView
            deepLinkHandler.shouldStartRecording = true
        case "recordings":
            // Just open app to recordings (default behavior)
            break
        default:
            break
        }
    }
}

// MARK: - Deep Link Handler

/// Observable object to handle deep link state across the app
final class DeepLinkHandler: ObservableObject {
    static let shared = DeepLinkHandler()

    @Published var shouldStartRecording = false

    private init() {}
}

// MARK: - Result Extension

private extension Result {
    /// Extract the error from a Result, returns nil if success
    var error: Failure? {
        switch self {
        case .success:
            return nil
        case .failure(let error):
            return error
        }
    }
}

// MARK: - Database Error View

/// View shown when the database fails to initialize
private struct DatabaseErrorView: View {
    let error: Error?

    var body: some View {
        VStack(spacing: 24) {
            Image(systemName: "exclamationmark.triangle.fill")
                .font(.system(size: 64))
                .foregroundColor(.orange)

            Text("Database Error")
                .font(.title)
                .fontWeight(.bold)

            Text("SecureVox was unable to initialize its database. This may be due to insufficient storage space or a corrupted database file.")
                .font(.body)
                .foregroundColor(.secondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal)

            if let error = error {
                Text(error.localizedDescription)
                    .font(.caption)
                    .foregroundColor(.secondary)
                    .padding()
                    .background(Color(.systemGray6))
                    .cornerRadius(8)
            }

            VStack(spacing: 12) {
                Text("Try the following:")
                    .font(.headline)

                VStack(alignment: .leading, spacing: 8) {
                    Label("Free up storage space on your device", systemImage: "internaldrive")
                    Label("Restart the app", systemImage: "arrow.clockwise")
                    Label("Reinstall the app if the issue persists", systemImage: "arrow.down.app")
                }
                .font(.subheadline)
                .foregroundColor(.secondary)
            }
            .padding()
        }
        .padding()
    }
}
