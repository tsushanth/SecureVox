import SwiftUI
import SwiftData
import AppKit
import os.log

private let logger = Logger(subsystem: "com.voicenotes.ondevice.macos", category: "App")

@main
struct SecureVoxApp: App {

    // MARK: - State

    @StateObject private var appState = AppState()
    @NSApplicationDelegateAdaptor(AppDelegate.self) var appDelegate
    @AppStorage("hasCompletedOnboarding") private var hasCompletedOnboarding = false
    @State private var modelContainerError: Error?

    // MARK: - SwiftData

    var sharedModelContainer: ModelContainer = {
        let schema = Schema([
            Recording.self,
            TranscriptSegment.self,
        ])
        let modelConfiguration = ModelConfiguration(
            schema: schema,
            isStoredInMemoryOnly: false
        )

        do {
            return try ModelContainer(for: schema, configurations: [modelConfiguration])
        } catch {
            // Log the error and attempt recovery with in-memory storage
            logger.error("Failed to create ModelContainer: \(error.localizedDescription)")

            // Try fallback to in-memory storage
            let fallbackConfig = ModelConfiguration(schema: schema, isStoredInMemoryOnly: true)
            do {
                logger.warning("Using in-memory storage as fallback")
                return try ModelContainer(for: schema, configurations: [fallbackConfig])
            } catch {
                // Last resort: create minimal container
                logger.critical("Failed to create fallback ModelContainer: \(error.localizedDescription)")
                // Return a minimal working container - this should rarely fail
                return try! ModelContainer(for: schema)
            }
        }
    }()

    // MARK: - Initialization

    init() {
        // Connect app state to app delegate for keyboard shortcuts
        DispatchQueue.main.async { [self] in
            appDelegate.setAppState(appState)
        }
    }

    // MARK: - Body

    var body: some Scene {
        WindowGroup {
            if hasCompletedOnboarding {
                ContentView()
                    .environmentObject(appState)
            } else {
                ModelSelectionView {
                    hasCompletedOnboarding = true
                }
            }
        }
        .modelContainer(sharedModelContainer)
        .windowStyle(.hiddenTitleBar)
        .windowResizability(.contentSize)
        .defaultSize(width: 1000, height: 700)
        .commands {
            // File menu
            CommandGroup(replacing: .newItem) {
                Button("New Recording") {
                    appState.startRecording()
                }
                .keyboardShortcut("n", modifiers: .command)

                Button("Import Audio...") {
                    appState.showImportPanel = true
                }
                .keyboardShortcut("i", modifiers: .command)
            }

            // View menu
            CommandGroup(after: .sidebar) {
                Divider()

                Button("Home") {
                    appState.selectedTab = .home
                }
                .keyboardShortcut("1", modifiers: .command)

                Button("Vocabulary") {
                    appState.selectedTab = .vocabulary
                }
                .keyboardShortcut("2", modifiers: .command)

                Button("Settings") {
                    appState.selectedTab = .settings
                }
                .keyboardShortcut(",", modifiers: .command)
            }
        }

        // Settings window
        Settings {
            SettingsView()
                .environmentObject(appState)
                .modelContainer(sharedModelContainer)
        }

        // Menu bar icon
        MenuBarExtra("SecureVox", systemImage: "waveform.circle.fill") {
            MenuBarView()
                .environmentObject(appState)
        }
        .menuBarExtraStyle(.menu)
    }
}

// MARK: - Menu Bar View

struct MenuBarView: View {
    @EnvironmentObject private var appState: AppState
    @Environment(\.openWindow) private var openWindow

    var body: some View {
        Button("New Recording") {
            appState.startRecording()
            NSApp.activate(ignoringOtherApps: true)
        }
        .keyboardShortcut("n", modifiers: .command)

        Button("Import Audio...") {
            appState.showImportPanel = true
            NSApp.activate(ignoringOtherApps: true)
        }
        .keyboardShortcut("i", modifiers: .command)

        Divider()

        Button("Open SecureVox") {
            NSApp.activate(ignoringOtherApps: true)
        }

        Divider()

        Button("Quit SecureVox") {
            NSApp.terminate(nil)
        }
        .keyboardShortcut("q", modifiers: .command)
    }
}

// MARK: - App Delegate

class AppDelegate: NSObject, NSApplicationDelegate {

    private var appState: AppState?

    func setAppState(_ state: AppState) {
        self.appState = state
    }

    func applicationDidFinishLaunching(_ notification: Notification) {
        // App launched
    }

    func applicationShouldHandleReopen(_ sender: NSApplication, hasVisibleWindows flag: Bool) -> Bool {
        // Reopen main window when dock icon clicked
        if !flag {
            for window in sender.windows {
                window.makeKeyAndOrderFront(self)
            }
        }
        return true
    }
}

// MARK: - App State

class AppState: ObservableObject {
    @Published var selectedTab: SidebarTab = .home
    @Published var selectedHomeCategory: HomeCategory = .quick
    @Published var selectedRecording: Recording?
    @Published var isRecording = false
    @Published var showImportPanel = false
    @Published var searchQuery = ""

    func startRecording() {
        isRecording = true
    }

    func stopRecording() {
        isRecording = false
    }
}

// MARK: - Sidebar Tab

enum SidebarTab: String, CaseIterable, Identifiable {
    case home = "Home"
    case vocabulary = "Vocabulary"
    case settings = "Settings"
    case faq = "FAQ"
    case recycleBin = "Recycle Bin"

    var id: String { rawValue }

    var icon: String {
        switch self {
        case .home: return "house"
        case .vocabulary: return "text.book.closed"
        case .settings: return "gearshape"
        case .faq: return "questionmark.circle"
        case .recycleBin: return "trash"
        }
    }

    // Tabs shown in sidebar main section
    static var mainTabs: [SidebarTab] {
        [.home, .vocabulary, .settings]
    }

    // Tabs shown in sidebar secondary section
    static var secondaryTabs: [SidebarTab] {
        [.faq, .recycleBin]
    }
}

// MARK: - Home Category

enum HomeCategory: String, CaseIterable, Identifiable {
    case quick = "Quick"
    case recorded = "Recorded"
    case imported = "Imported"
    case meeting = "Meeting"

    var id: String { rawValue }

    var icon: String {
        switch self {
        case .quick: return "bolt"
        case .recorded: return "mic"
        case .imported: return "square.and.arrow.down"
        case .meeting: return "video"
        }
    }
}
