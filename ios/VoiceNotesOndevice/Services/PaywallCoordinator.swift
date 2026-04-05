import Foundation
import Combine
import RevenueCat

/// Tracks paywall dismisses, app opens, and determines when to show paywalls.
/// Uses UserDefaults to persist dismiss count, app open count, and cooldown timestamps.
@MainActor
final class PaywallCoordinator: ObservableObject {

    static let shared = PaywallCoordinator()

    // MARK: - Published

    @Published var showWinbackOffer = false
    @Published var showPaywall = false
    @Published var isPremium = false

    // MARK: - UserDefaults Keys

    private enum Keys {
        static let dismissCount = "com.securevox.paywall.dismissCount"
        static let lastDismissDate = "com.securevox.paywall.lastDismissDate"
        static let lastWinbackShownDate = "com.securevox.paywall.lastWinbackShownDate"
        static let appOpenCount = "com.securevox.paywall.appOpenCount"
    }

    // MARK: - Configuration

    /// Number of paywall dismisses required before showing winback
    private let requiredDismisses = 3

    /// Minimum seconds between the last dismiss and showing the winback (1 day)
    private let cooldownInterval: TimeInterval = 86_400

    /// App opens that trigger the paywall (1st, 3rd, 5th, then every 3rd)
    private let paywallTriggerOpens: Set<Int> = [1, 3, 5]

    /// After the initial set, show every Nth open
    private let paywallRecurringInterval = 3

    // MARK: - Computed

    var paywallDismissCount: Int {
        get { UserDefaults.standard.integer(forKey: Keys.dismissCount) }
        set { UserDefaults.standard.set(newValue, forKey: Keys.dismissCount) }
    }

    private var appOpenCount: Int {
        get { UserDefaults.standard.integer(forKey: Keys.appOpenCount) }
        set { UserDefaults.standard.set(newValue, forKey: Keys.appOpenCount) }
    }

    private var lastDismissDate: Date? {
        get {
            let ti = UserDefaults.standard.double(forKey: Keys.lastDismissDate)
            return ti > 0 ? Date(timeIntervalSince1970: ti) : nil
        }
        set {
            if let date = newValue {
                UserDefaults.standard.set(date.timeIntervalSince1970, forKey: Keys.lastDismissDate)
            }
        }
    }

    private var lastWinbackShownDate: Date? {
        get {
            let ti = UserDefaults.standard.double(forKey: Keys.lastWinbackShownDate)
            return ti > 0 ? Date(timeIntervalSince1970: ti) : nil
        }
        set {
            if let date = newValue {
                UserDefaults.standard.set(date.timeIntervalSince1970, forKey: Keys.lastWinbackShownDate)
            }
        }
    }

    // MARK: - Init

    private init() {}

    // MARK: - Premium Check

    /// Check premium status via RevenueCat
    func refreshPremiumStatus() async {
        do {
            let customerInfo = try await Purchases.shared.customerInfo()
            isPremium = customerInfo.entitlements["premium"]?.isActive == true
        } catch {
            print("[PaywallCoordinator] Failed to check premium status: \(error)")
        }
    }

    // MARK: - App Open Paywall

    /// Call on each app open / foreground to potentially show paywall.
    /// Shows on opens 1, 3, 5, then every 3rd open.
    func handleAppOpen() async {
        await refreshPremiumStatus()

        guard !isPremium else { return }

        appOpenCount += 1
        let count = appOpenCount

        let shouldShow: Bool
        if paywallTriggerOpens.contains(count) {
            shouldShow = true
        } else if count > 5 && (count - 5) % paywallRecurringInterval == 0 {
            shouldShow = true
        } else {
            shouldShow = false
        }

        if shouldShow {
            // Small delay so the UI has time to appear
            try? await Task.sleep(nanoseconds: 500_000_000)
            showPaywall = true
        }
    }

    // MARK: - Public

    /// Call when the user dismisses a paywall (or subscription prompt).
    func trackDismiss() {
        paywallDismissCount += 1
        lastDismissDate = Date()
    }

    /// Evaluate whether the winback offer should be displayed.
    /// Typically called when the app enters foreground.
    func checkWinbackEligibility() {
        guard !isPremium else { return }
        guard paywallDismissCount >= requiredDismisses else { return }

        // Ensure at least 1 day has passed since last dismiss
        if let lastDismiss = lastDismissDate {
            guard Date().timeIntervalSince(lastDismiss) >= cooldownInterval else { return }
        }

        // Don't show more than once per day
        if let lastShown = lastWinbackShownDate,
           Calendar.current.isDateInToday(lastShown) {
            return
        }

        showWinbackOffer = true
        lastWinbackShownDate = Date()
    }
}
