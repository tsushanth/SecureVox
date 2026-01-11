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

    var sharedModelContainer: ModelContainer = {
        let schema = Schema([
            Recording.self,
            TranscriptSegment.self
        ])
        let modelConfiguration = ModelConfiguration(schema: schema, isStoredInMemoryOnly: false)

        do {
            return try ModelContainer(for: schema, configurations: [modelConfiguration])
        } catch {
            fatalError("Could not create ModelContainer: \(error)")
        }
    }()

    // MARK: - Body

    var body: some Scene {
        WindowGroup {
            ContentView()
                .preferredColorScheme(colorScheme)
                .environmentObject(deepLinkHandler)
                .onOpenURL { url in
                    handleDeepLink(url)
                }
        }
        .modelContainer(sharedModelContainer)
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
