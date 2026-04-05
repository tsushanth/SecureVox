import SwiftUI
import RevenueCat
import PaywallKit

/// PaywallKit-powered winback paywall for SecureVox.
struct WinbackOfferView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var paywallProducts: [PaywallProduct] = []
    @State private var didPurchaseOrRestore = false

    var body: some View {
        PaywallKit.PaywallView(
            appId: "securevox",
            appName: "SecureVox Pro",
            features: [
                PaywallFeature(icon: "\u{1F512}", title: "End-to-End Encryption", description: "Your recordings stay private"),
                PaywallFeature(icon: "\u{1F399}", title: "Unlimited Recordings", description: "No storage limits"),
                PaywallFeature(icon: "\u{1F4DD}", title: "Transcription", description: "Speech to text"),
                PaywallFeature(icon: "\u{2601}\u{FE0F}", title: "Secure Cloud Backup", description: "Encrypted backup"),
                PaywallFeature(icon: "\u{1F5C2}", title: "Custom Folders", description: "Organize recordings")
            ],
            products: paywallProducts,
            theme: PaywallTheme(accent: Color(red: 0.2, green: 0.7, blue: 0.4), accent2: Color(red: 0.0, green: 0.6, blue: 0.6)),
            showWinback: true,
            onPurchase: { productId in
                return await purchaseProduct(productId: productId)
            },
            onRestore: {
                await restorePurchases()
            },
            onDismiss: {
                if !didPurchaseOrRestore {
                    PaywallCoordinator.shared.trackDismiss()
                }
                dismiss()
            }
        )
        .task {
            await loadProducts()
        }
    }

    // MARK: - Product Loading

    private func loadProducts() async {
        do {
            let offerings = try await Purchases.shared.offerings()
            let packages = offerings.current?.availablePackages ?? []

            paywallProducts = packages.compactMap { pkg -> PaywallProduct? in
                let product = pkg.storeProduct
                let period: PaywallProduct.Period
                switch pkg.packageType {
                case .weekly: period = .weekly
                case .monthly: period = .monthly
                case .annual: period = .yearly
                case .lifetime: period = .lifetime
                default:
                    if product.productIdentifier.contains("lifetime") { period = .lifetime }
                    else if product.productIdentifier.contains("yearly") || product.productIdentifier.contains("annual") { period = .yearly }
                    else if product.productIdentifier.contains("monthly") { period = .monthly }
                    else if product.productIdentifier.contains("weekly") { period = .weekly }
                    else { return nil }
                }

                var trialDays: Int? = nil
                if let intro = product.introductoryDiscount,
                   intro.paymentMode == .freeTrial {
                    let sub = intro.subscriptionPeriod
                    switch sub.unit {
                    case .day: trialDays = sub.value
                    case .week: trialDays = sub.value * 7
                    case .month: trialDays = sub.value * 30
                    case .year: trialDays = sub.value * 365
                    @unknown default: trialDays = sub.value
                    }
                }

                return PaywallProduct(
                    id: product.productIdentifier,
                    localizedPrice: product.localizedPriceString,
                    price: product.price,
                    currencyCode: product.currencyCode ?? "USD",
                    trialDays: trialDays,
                    period: period
                )
            }
        } catch {
            print("[SecureVoxPaywall] Failed to load products: \(error)")
        }
    }

    // MARK: - Purchase

    private func purchaseProduct(productId: String) async -> Bool {
        do {
            let offerings = try await Purchases.shared.offerings()
            let packages = offerings.current?.availablePackages ?? []

            guard let package = packages.first(where: {
                $0.storeProduct.productIdentifier == productId
            }) else {
                print("[SecureVoxPaywall] No package found for \(productId)")
                return false
            }

            let result = try await Purchases.shared.purchase(package: package)
            if result.customerInfo.entitlements["premium"]?.isActive == true {
                didPurchaseOrRestore = true
                await MainActor.run {
                    PaywallCoordinator.shared.isPremium = true
                    dismiss()
                }
                return true
            }
            return false
        } catch {
            let nsError = error as NSError
            if nsError.domain == RevenueCat.ErrorCode.errorDomain,
               nsError.code == RevenueCat.ErrorCode.purchaseCancelledError.rawValue {
                return false
            }
            print("[SecureVoxPaywall] Purchase failed: \(error)")
            return false
        }
    }

    // MARK: - Restore

    private func restorePurchases() async {
        do {
            let customerInfo = try await Purchases.shared.restorePurchases()
            if customerInfo.entitlements["premium"]?.isActive == true {
                didPurchaseOrRestore = true
                await MainActor.run {
                    PaywallCoordinator.shared.isPremium = true
                    dismiss()
                }
            }
        } catch {
            print("[SecureVoxPaywall] Restore failed: \(error)")
        }
    }
}

#Preview {
    WinbackOfferView()
}
