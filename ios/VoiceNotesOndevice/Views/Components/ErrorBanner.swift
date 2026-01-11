import SwiftUI

/// Dismissable error banner for displaying errors
struct ErrorBanner: View {

    // MARK: - Properties

    let message: String
    let onDismiss: () -> Void

    // MARK: - Body

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: "exclamationmark.triangle.fill")
                .foregroundStyle(.white)

            Text(message)
                .font(.subheadline)
                .foregroundStyle(.white)
                .lineLimit(2)

            Spacer()

            Button {
                withAnimation {
                    onDismiss()
                }
            } label: {
                Image(systemName: "xmark")
                    .font(.caption)
                    .foregroundStyle(.white.opacity(0.8))
            }
        }
        .padding()
        .background(Color.red)
        .clipShape(RoundedRectangle(cornerRadius: 12))
        .shadow(color: .black.opacity(0.2), radius: 4, y: 2)
        .padding(.horizontal)
        .transition(.move(edge: .top).combined(with: .opacity))
    }
}

// MARK: - View Extension

extension View {
    func errorBanner(
        message: String?,
        onDismiss: @escaping () -> Void
    ) -> some View {
        self.overlay(alignment: .top) {
            if let message = message {
                ErrorBanner(message: message, onDismiss: onDismiss)
                    .padding(.top, 8)
            }
        }
    }
}

// MARK: - Preview

#Preview {
    VStack {
        Text("Content")
    }
    .frame(maxWidth: .infinity, maxHeight: .infinity)
    .errorBanner(
        message: "Failed to transcribe: The audio file is corrupted.",
        onDismiss: {}
    )
}
