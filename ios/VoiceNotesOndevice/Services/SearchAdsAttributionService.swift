import Foundation
import AdServices

/// Service for handling Apple Search Ads attribution
/// Fetches and stores install attribution data for ASA bid optimization
final class SearchAdsAttributionService {

    // MARK: - Singleton

    static let shared = SearchAdsAttributionService()

    // MARK: - Constants

    private enum Constants {
        static let attributionFetchedKey = "searchAdsAttributionFetched"
        static let attributionTokenKey = "searchAdsAttributionToken"
        static let attributionDataKey = "searchAdsAttributionData"
    }

    // MARK: - Properties

    private var hasAttemptedFetch: Bool {
        get { UserDefaults.standard.bool(forKey: Constants.attributionFetchedKey) }
        set { UserDefaults.standard.set(newValue, forKey: Constants.attributionFetchedKey) }
    }

    // MARK: - Initialization

    private init() {}

    // MARK: - Public Methods

    /// Request attribution data on first app launch
    /// Should be called once during app startup
    func requestAttributionIfNeeded() {
        guard !hasAttemptedFetch else {
            Logger.info("Attribution already fetched, skipping", category: Logger.attribution)
            return
        }

        Task {
            await fetchAttribution()
        }
    }

    /// Get stored attribution token if available
    func getStoredAttributionToken() -> String? {
        return UserDefaults.standard.string(forKey: Constants.attributionTokenKey)
    }

    /// Get stored attribution data if available
    func getStoredAttributionData() -> [String: Any]? {
        guard let data = UserDefaults.standard.data(forKey: Constants.attributionDataKey),
              let attribution = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return nil
        }
        return attribution
    }

    // MARK: - Private Methods

    private func fetchAttribution() async {
        Logger.info("Starting Apple Search Ads attribution fetch", category: Logger.attribution)

        do {
            // Get attribution token using AdServices framework (iOS 14.3+)
            let token = try AAAttribution.attributionToken()
            Logger.info("Successfully obtained attribution token", category: Logger.attribution)

            // Store the token
            UserDefaults.standard.set(token, forKey: Constants.attributionTokenKey)

            // Log token length for debugging (don't log the actual token for privacy)
            Logger.debug("Attribution token length: \(token.count)", category: Logger.attribution)

            // Fetch full attribution data from Apple's server
            await fetchAttributionDataFromServer(token: token)

        } catch {
            Logger.error(error, context: "Failed to get attribution token", category: Logger.attribution)
        }

        hasAttemptedFetch = true
    }

    private func fetchAttributionDataFromServer(token: String) async {
        // Apple's attribution endpoint
        let urlString = "https://api-adservices.apple.com/api/v1/"

        guard let url = URL(string: urlString) else {
            Logger.error("Invalid attribution API URL", category: Logger.attribution)
            return
        }

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("text/plain", forHTTPHeaderField: "Content-Type")
        request.httpBody = token.data(using: .utf8)

        do {
            let (data, response) = try await URLSession.shared.data(for: request)

            guard let httpResponse = response as? HTTPURLResponse else {
                Logger.error("Invalid response type from attribution API", category: Logger.attribution)
                return
            }

            if httpResponse.statusCode == 200 {
                // Parse and store attribution data
                if let attributionData = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
                    storeAttributionData(attributionData)
                    logAttributionSummary(attributionData)
                }
            } else {
                Logger.warning("Attribution API returned status: \(httpResponse.statusCode)", category: Logger.attribution)

                // Status 400 typically means organic install (no attribution)
                if httpResponse.statusCode == 400 {
                    Logger.info("No attribution data available (likely organic install)", category: Logger.attribution)
                }
            }

        } catch {
            Logger.error(error, context: "Failed to fetch attribution data from server", category: Logger.attribution)
        }
    }

    private func storeAttributionData(_ data: [String: Any]) {
        do {
            let jsonData = try JSONSerialization.data(withJSONObject: data, options: [])
            UserDefaults.standard.set(jsonData, forKey: Constants.attributionDataKey)
            Logger.info("Attribution data stored successfully", category: Logger.attribution)
        } catch {
            Logger.error(error, context: "Failed to store attribution data", category: Logger.attribution)
        }
    }

    private func logAttributionSummary(_ data: [String: Any]) {
        // Log non-sensitive attribution details for debugging
        if let attribution = data["attribution"] as? Bool {
            Logger.info("Install attributed to Apple Search Ads: \(attribution)", category: Logger.attribution)
        }

        if let orgId = data["orgId"] as? Int {
            Logger.debug("Organization ID: \(orgId)", category: Logger.attribution)
        }

        if let campaignId = data["campaignId"] as? Int {
            Logger.debug("Campaign ID: \(campaignId)", category: Logger.attribution)
        }

        if let adGroupId = data["adGroupId"] as? Int {
            Logger.debug("Ad Group ID: \(adGroupId)", category: Logger.attribution)
        }

        if let keywordId = data["keywordId"] as? Int {
            Logger.debug("Keyword ID: \(keywordId)", category: Logger.attribution)
        }

        if let clickDate = data["clickDate"] as? String {
            Logger.debug("Click date: \(clickDate)", category: Logger.attribution)
        }

        if let conversionType = data["conversionType"] as? String {
            Logger.debug("Conversion type: \(conversionType)", category: Logger.attribution)
        }
    }
}
