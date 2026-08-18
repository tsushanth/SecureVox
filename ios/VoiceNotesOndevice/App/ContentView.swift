import SwiftUI

/// Root view containing tab navigation
struct ContentView: View {

    // MARK: - State

    @State private var selectedTab: Tab = .recordings

    // MARK: - Body

    var body: some View {
        TabView(selection: $selectedTab) {
            RecordingsListView()
                .tabItem {
                    Label("Recordings", systemImage: "list.bullet")
                }
                .tag(Tab.recordings)

            SettingsView()
                .tabItem {
                    Label("Settings", systemImage: "gear")
                }
                .tag(Tab.settings)
        }
    }

    // MARK: - Tab Enum

    enum Tab: Hashable {
        case recordings
        case settings
    }
}

// MARK: - Preview

#Preview {
    ContentView()
}
