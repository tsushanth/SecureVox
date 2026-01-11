import SwiftUI
import StoreKit
import os.log

/// A prompt asking users if they're enjoying the app
struct RatingPromptView: View {

    // MARK: - Environment

    @Environment(\.dismiss) private var dismiss
    @Environment(\.requestReview) private var requestReview

    // MARK: - Bindings

    @Binding var isPresented: Bool

    // MARK: - State

    @State private var showingMailError = false

    // MARK: - Logger

    private static let logger = os.Logger(
        subsystem: Bundle.main.bundleIdentifier ?? "com.voicenotes.ondevice",
        category: "rating"
    )

    // MARK: - Body

    var body: some View {
        VStack(spacing: 24) {
            // App icon placeholder
            Image(systemName: "waveform.circle.fill")
                .font(.system(size: 60))
                .foregroundStyle(.blue)

            Text("Enjoying \(AppConstants.appName)?")
                .font(.title2)
                .fontWeight(.semibold)

            Text("Your feedback helps us improve the app for everyone.")
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal)

            VStack(spacing: 12) {
                Button {
                    handleYes()
                } label: {
                    Text("Yes, I love it!")
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 4)
                }
                .buttonStyle(.borderedProminent)

                Button {
                    handleNo()
                } label: {
                    Text("Not Really")
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 4)
                }
                .buttonStyle(.bordered)

                Button {
                    handleNotNow()
                } label: {
                    Text("Not Now")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
                .padding(.top, 4)
            }
            .padding(.horizontal)
        }
        .padding(.vertical, 32)
        .alert("Unable to Open Mail", isPresented: $showingMailError) {
            Button("OK") { }
        } message: {
            Text("Please email us at \(AppConstants.Support.email)")
        }
        .interactiveDismissDisabled()
    }

    // MARK: - Actions

    private func handleYes() {
        Self.logger.info("Rating prompt: User tapped 'Yes, I love it!'")

        // Track the response
        UserDefaults.standard.set(true, forKey: AppConstants.UserDefaultsKeys.hasShownRatingPrompt)
        UserDefaults.standard.set("yes", forKey: AppConstants.UserDefaultsKeys.ratingPromptResponse)
        UserDefaults.standard.set(Date(), forKey: AppConstants.UserDefaultsKeys.ratingPromptLastShownDate)
        UserDefaults.standard.set(0, forKey: AppConstants.UserDefaultsKeys.transcriptionsSinceLastPrompt)

        // Dismiss first, then request review
        isPresented = false

        // Small delay to allow sheet to dismiss before showing review prompt
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
            Self.logger.info("Rating prompt: Requesting App Store review")
            requestReview()
        }
    }

    private func handleNo() {
        Self.logger.info("Rating prompt: User tapped 'Not Really'")

        // Track the response - don't ask again
        UserDefaults.standard.set(true, forKey: AppConstants.UserDefaultsKeys.hasShownRatingPrompt)
        UserDefaults.standard.set("no", forKey: AppConstants.UserDefaultsKeys.ratingPromptResponse)
        UserDefaults.standard.set(Date(), forKey: AppConstants.UserDefaultsKeys.ratingPromptLastShownDate)
        UserDefaults.standard.set(0, forKey: AppConstants.UserDefaultsKeys.transcriptionsSinceLastPrompt)

        // Open email to support
        let subject = AppConstants.Support.emailSubject.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? ""
        let urlString = "mailto:\(AppConstants.Support.email)?subject=\(subject)"

        if let url = URL(string: urlString) {
            #if os(iOS)
            UIApplication.shared.open(url) { success in
                if !success {
                    showingMailError = true
                }
            }
            #else
            // macOS fallback
            isPresented = false
            #endif
        }

        isPresented = false
    }

    private func handleNotNow() {
        Self.logger.info("Rating prompt: User tapped 'Not Now'")

        // Track the response - can ask again later
        let notNowCount = UserDefaults.standard.integer(forKey: AppConstants.UserDefaultsKeys.ratingPromptNotNowCount) + 1
        UserDefaults.standard.set(notNowCount, forKey: AppConstants.UserDefaultsKeys.ratingPromptNotNowCount)
        UserDefaults.standard.set("notNow", forKey: AppConstants.UserDefaultsKeys.ratingPromptResponse)
        UserDefaults.standard.set(Date(), forKey: AppConstants.UserDefaultsKeys.ratingPromptLastShownDate)
        UserDefaults.standard.set(0, forKey: AppConstants.UserDefaultsKeys.transcriptionsSinceLastPrompt)

        Self.logger.info("Rating prompt: 'Not Now' count is now \(notNowCount)")

        isPresented = false
    }
}

// MARK: - Preview

#Preview {
    RatingPromptView(isPresented: .constant(true))
}
