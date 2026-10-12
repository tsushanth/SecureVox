import Foundation
import AdServices

/// Apple Search Ads attribution wiring.
/// Fetches the AAAttribution token at first launch and POSTs it to Apple's
/// attribution API so the install registers as an ASA conversion.
/// Persists a one-shot UserDefaults flag so we don't re-send on every launch.
final class AttributionService {
    static let shared = AttributionService()
    private let sentKey = "asa.attribution.sent"
    private init() {}

    func trackAttribution() {
        guard !UserDefaults.standard.bool(forKey: sentKey) else { return }
        Task.detached(priority: .background) {
            do {
                let token = try AAAttribution.attributionToken()
                try await Self.postToApple(token: token)
                UserDefaults.standard.set(true, forKey: self.sentKey)
            } catch {
                // Silent: do not retry within this launch; will retry next launch.
            }
        }
    }

    private static func postToApple(token: String) async throws {
        var req = URLRequest(url: URL(string: "https://api-adservices.apple.com/api/v1/")!)
        req.httpMethod = "POST"
        req.setValue("text/plain", forHTTPHeaderField: "Content-Type")
        req.httpBody = token.data(using: .utf8)
        _ = try await URLSession.shared.data(for: req)
    }
}
