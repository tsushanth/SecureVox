import Foundation
import AVFoundation
#if os(iOS)
import UIKit
#endif

/// Service for providing audio and haptic feedback
final class FeedbackService {

    // MARK: - Singleton

    static let shared = FeedbackService()

    // MARK: - Private Properties

    private var audioPlayer: AVAudioPlayer?

    // MARK: - Initialization

    private init() {}

    // MARK: - Sound Effects

    /// Play the recording start sound
    func playRecordingStartSound() {
        guard isSoundEffectsEnabled else { return }
        playSystemSound(id: 1113) // Begin Recording sound
    }

    /// Play the recording stop sound
    func playRecordingStopSound() {
        guard isSoundEffectsEnabled else { return }
        playSystemSound(id: 1114) // End Recording sound
    }

    /// Play a success sound
    func playSuccessSound() {
        guard isSoundEffectsEnabled else { return }
        playSystemSound(id: 1057) // Success sound
    }

    // MARK: - Haptic Feedback

    /// Trigger haptic feedback for recording start
    func triggerRecordingStartHaptic() {
        guard isHapticFeedbackEnabled else { return }
        #if os(iOS)
        let generator = UIImpactFeedbackGenerator(style: .medium)
        generator.prepare()
        generator.impactOccurred()
        #endif
    }

    /// Trigger haptic feedback for recording stop
    func triggerRecordingStopHaptic() {
        guard isHapticFeedbackEnabled else { return }
        #if os(iOS)
        let generator = UINotificationFeedbackGenerator()
        generator.prepare()
        generator.notificationOccurred(.success)
        #endif
    }

    /// Trigger a light haptic tap
    func triggerLightHaptic() {
        guard isHapticFeedbackEnabled else { return }
        #if os(iOS)
        let generator = UIImpactFeedbackGenerator(style: .light)
        generator.prepare()
        generator.impactOccurred()
        #endif
    }

    /// Trigger a selection haptic
    func triggerSelectionHaptic() {
        guard isHapticFeedbackEnabled else { return }
        #if os(iOS)
        let generator = UISelectionFeedbackGenerator()
        generator.prepare()
        generator.selectionChanged()
        #endif
    }

    // MARK: - Settings Check

    private var isSoundEffectsEnabled: Bool {
        if UserDefaults.standard.object(forKey: AppConstants.UserDefaultsKeys.soundEffectsEnabled) != nil {
            return UserDefaults.standard.bool(forKey: AppConstants.UserDefaultsKeys.soundEffectsEnabled)
        }
        return true // Default to enabled
    }

    private var isHapticFeedbackEnabled: Bool {
        if UserDefaults.standard.object(forKey: AppConstants.UserDefaultsKeys.hapticFeedbackEnabled) != nil {
            return UserDefaults.standard.bool(forKey: AppConstants.UserDefaultsKeys.hapticFeedbackEnabled)
        }
        return true // Default to enabled
    }

    // MARK: - Private Methods

    private func playSystemSound(id: SystemSoundID) {
        AudioServicesPlaySystemSound(id)
    }
}
