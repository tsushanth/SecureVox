import AVFoundation
import Photos
#if os(iOS)
import UIKit
#elseif os(macOS)
import AppKit
#endif

/// Manages app permissions for microphone and photo library
enum PermissionManager {

    // MARK: - Permission Status

    enum PermissionStatus {
        case authorized
        case denied
        case notDetermined
        case restricted
    }

    // MARK: - Microphone

    /// Current microphone permission status
    static var microphoneStatus: PermissionStatus {
        switch AVAudioApplication.shared.recordPermission {
        case .granted:
            return .authorized
        case .denied:
            return .denied
        case .undetermined:
            return .notDetermined
        @unknown default:
            return .notDetermined
        }
    }

    /// Request microphone permission
    static func requestMicrophonePermission() async -> Bool {
        await withCheckedContinuation { continuation in
            AVAudioApplication.requestRecordPermission { granted in
                continuation.resume(returning: granted)
            }
        }
    }

    // MARK: - Photo Library

    /// Current photo library permission status
    static var photoLibraryStatus: PermissionStatus {
        let status = PHPhotoLibrary.authorizationStatus(for: .readWrite)

        switch status {
        case .authorized, .limited:
            return .authorized
        case .denied:
            return .denied
        case .notDetermined:
            return .notDetermined
        case .restricted:
            return .restricted
        @unknown default:
            return .notDetermined
        }
    }

    /// Request photo library permission
    static func requestPhotoLibraryPermission() async -> Bool {
        let status = await PHPhotoLibrary.requestAuthorization(for: .readWrite)
        return status == .authorized || status == .limited
    }

    // MARK: - Settings

    /// Open app settings
    static func openSettings() {
        #if os(iOS)
        guard let url = URL(string: UIApplication.openSettingsURLString) else { return }
        Task { @MainActor in
            await UIApplication.shared.open(url)
        }
        #elseif os(macOS)
        // On macOS, open System Preferences to Privacy & Security
        if let url = URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_Microphone") {
            NSWorkspace.shared.open(url)
        }
        #endif
    }

    // MARK: - Convenience

    /// Check if microphone is available
    static var isMicrophoneAvailable: Bool {
        microphoneStatus == .authorized
    }

    /// Check if photo library is available
    static var isPhotoLibraryAvailable: Bool {
        photoLibraryStatus == .authorized
    }

    /// Request all required permissions
    static func requestAllPermissions() async -> (microphone: Bool, photoLibrary: Bool) {
        async let mic = requestMicrophonePermission()
        async let photos = requestPhotoLibraryPermission()

        return await (mic, photos)
    }
}

// MARK: - Permission Descriptions

extension PermissionManager {

    /// User-friendly description of microphone permission status
    static var microphoneStatusDescription: String {
        switch microphoneStatus {
        case .authorized:
            return "Microphone access granted"
        case .denied:
            return "Microphone access denied. Enable in Settings."
        case .notDetermined:
            return "Microphone permission not requested"
        case .restricted:
            return "Microphone access restricted"
        }
    }

    /// User-friendly description of photo library permission status
    static var photoLibraryStatusDescription: String {
        switch photoLibraryStatus {
        case .authorized:
            return "Photo library access granted"
        case .denied:
            return "Photo library access denied. Enable in Settings."
        case .notDetermined:
            return "Photo library permission not requested"
        case .restricted:
            return "Photo library access restricted"
        }
    }
}
