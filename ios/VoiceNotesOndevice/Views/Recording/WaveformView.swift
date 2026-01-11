import SwiftUI

/// Animated waveform visualization for audio levels
struct WaveformView: View {

    // MARK: - Properties

    /// Current audio level (0.0 - 1.0)
    let level: Float

    /// Whether the waveform is actively animating
    let isActive: Bool

    /// Number of bars in the waveform
    var barCount: Int = 40

    /// Spacing between bars
    var barSpacing: CGFloat = 3

    // MARK: - State

    @State private var animationPhase: Double = 0

    // MARK: - Body

    var body: some View {
        GeometryReader { geometry in
            HStack(spacing: barSpacing) {
                ForEach(0..<barCount, id: \.self) { index in
                    WaveformBar(
                        height: barHeight(for: index, in: geometry.size.height),
                        isActive: isActive
                    )
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .onChange(of: isActive) { _, newValue in
            if newValue {
                startAnimation()
            }
        }
        .onAppear {
            if isActive {
                startAnimation()
            }
        }
    }

    // MARK: - Private Methods

    private func barHeight(for index: Int, in maxHeight: CGFloat) -> CGFloat {
        guard isActive else {
            return maxHeight * 0.1
        }

        let normalizedIndex = Double(index) / Double(barCount)

        // Create wave pattern
        let waveOffset = sin((normalizedIndex * .pi * 4) + animationPhase)
        let centerFactor = 1.0 - abs(normalizedIndex - 0.5) * 2

        // Combine wave with audio level
        let levelFactor = Double(level) * 0.7 + 0.3
        let height = (0.2 + (waveOffset + 1) * 0.3 * centerFactor) * levelFactor

        return CGFloat(height) * maxHeight
    }

    private func startAnimation() {
        withAnimation(.linear(duration: 1).repeatForever(autoreverses: false)) {
            animationPhase = .pi * 2
        }
    }
}

// MARK: - Waveform Bar

private struct WaveformBar: View {

    let height: CGFloat
    let isActive: Bool

    var body: some View {
        RoundedRectangle(cornerRadius: 2)
            .fill(isActive ? Color.red : Color.secondary.opacity(0.3))
            .frame(height: height)
            .animation(.easeInOut(duration: 0.1), value: height)
    }
}

// MARK: - Preview

#Preview {
    VStack(spacing: 40) {
        WaveformView(level: 0.0, isActive: false)
            .frame(height: 80)
            .padding()
            .background(Color.gray.opacity(0.1))

        WaveformView(level: 0.5, isActive: true)
            .frame(height: 80)
            .padding()
            .background(Color.gray.opacity(0.1))

        WaveformView(level: 0.9, isActive: true)
            .frame(height: 80)
            .padding()
            .background(Color.gray.opacity(0.1))
    }
    .padding()
}
