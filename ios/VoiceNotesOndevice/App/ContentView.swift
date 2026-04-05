import SwiftUI

/// Root view containing tab navigation
struct ContentView: View {

    // MARK: - State

    @State private var selectedTab: Tab = .recordings
    @StateObject private var paywallCoordinator = PaywallCoordinator.shared
    @Environment(\.scenePhase) private var scenePhase

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
        .task {
            // Trigger paywall on app open
            await paywallCoordinator.handleAppOpen()
        }
        .onChange(of: scenePhase) { _, newPhase in
            if newPhase == .active {
                // Check winback eligibility when returning to foreground
                paywallCoordinator.checkWinbackEligibility()
            }
        }
        .fullScreenCover(isPresented: $paywallCoordinator.showPaywall) {
            RemotePaywallView(triggerSource: "app_open")
        }
        .sheet(isPresented: $paywallCoordinator.showWinbackOffer) {
            WinbackOfferView()
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
