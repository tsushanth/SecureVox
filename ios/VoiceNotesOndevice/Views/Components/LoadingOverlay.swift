import SwiftUI

/// Full-screen loading overlay with optional message
struct LoadingOverlay: View {

    // MARK: - Properties

    var message: String = "Loading..."
    var progress: Double? = nil

    // MARK: - Body

    var body: some View {
        ZStack {
            Color.black.opacity(0.4)
                .ignoresSafeArea()

            VStack(spacing: 16) {
                if let progress = progress {
                    ProgressView(value: progress)
                        .progressViewStyle(.circular)
                        .scaleEffect(1.5)
                } else {
                    ProgressView()
                        .scaleEffect(1.5)
                }

                Text(message)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
            .padding(32)
            .background(.regularMaterial)
            .clipShape(RoundedRectangle(cornerRadius: 16))
        }
    }
}

// MARK: - View Modifier

extension View {
    func loadingOverlay(
        isPresented: Bool,
        message: String = "Loading...",
        progress: Double? = nil
    ) -> some View {
        self.overlay {
            if isPresented {
                LoadingOverlay(message: message, progress: progress)
            }
        }
    }
}

// MARK: - Preview

#Preview {
    VStack {
        Text("Background Content")
    }
    .frame(maxWidth: .infinity, maxHeight: .infinity)
    .background(Color.gray.opacity(0.2))
    .overlay {
        LoadingOverlay(message: "Processing...", progress: 0.65)
    }
}
