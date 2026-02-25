import Foundation
import TikTokBusinessSDK
import AppTrackingTransparency

final class TikTokHelper {
    static let shared = TikTokHelper()

    private let appId = "7610469058450440199"

    private init() {}

    func initialize() {
        let config = TikTokConfig(accessToken: "", appId: appId, tiktokAppId: appId)
        config?.setLogLevel(TikTokLogLevelInfo)
        if let config = config {
            TikTokBusiness.initializeSdk(config)
        }
    }

    func requestTrackingPermission() {
        if #available(iOS 14.5, *) {
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.0) {
                ATTrackingManager.requestTrackingAuthorization { _ in }
            }
        }
    }
}
