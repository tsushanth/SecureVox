import Foundation
import AVFoundation
import Combine

/// Service for managing microphone selection and enumeration
@MainActor
class MicrophoneService: ObservableObject {

    // MARK: - Singleton

    static let shared = MicrophoneService()

    // MARK: - Types

    struct Microphone: Identifiable, Hashable {
        let id: String
        let name: String
        let isDefault: Bool

        static let automatic = Microphone(
            id: "automatic",
            name: "Automatic (System Default)",
            isDefault: false
        )
    }

    // MARK: - Published Properties

    @Published private(set) var availableMicrophones: [Microphone] = []
    @Published var selectedMicrophoneID: String {
        didSet {
            UserDefaults.standard.set(selectedMicrophoneID, forKey: "selectedMicrophoneID")
        }
    }

    // MARK: - Private Properties

    private var cancellables = Set<AnyCancellable>()

    // MARK: - Initialization

    private init() {
        selectedMicrophoneID = UserDefaults.standard.string(forKey: "selectedMicrophoneID") ?? "automatic"
        refreshMicrophones()
        setupNotifications()
    }

    // MARK: - Public Methods

    /// Refresh the list of available microphones
    func refreshMicrophones() {
        var microphones: [Microphone] = [.automatic]

        // Get the default input device
        let defaultDeviceID = getDefaultInputDeviceID()

        // Get all audio input devices
        let discoverySession = AVCaptureDevice.DiscoverySession(
            deviceTypes: [.microphone, .builtInMicrophone, .externalUnknown],
            mediaType: .audio,
            position: .unspecified
        )

        for device in discoverySession.devices {
            let isDefault = device.uniqueID == defaultDeviceID
            let mic = Microphone(
                id: device.uniqueID,
                name: device.localizedName,
                isDefault: isDefault
            )
            microphones.append(mic)
        }

        availableMicrophones = microphones

        // Validate current selection
        if selectedMicrophoneID != "automatic" &&
           !microphones.contains(where: { $0.id == selectedMicrophoneID }) {
            selectedMicrophoneID = "automatic"
        }
    }

    /// Get the currently selected microphone (or default if automatic)
    func getSelectedMicrophone() -> AVCaptureDevice? {
        if selectedMicrophoneID == "automatic" {
            return AVCaptureDevice.default(for: .audio)
        }

        return AVCaptureDevice.devices(for: .audio).first { $0.uniqueID == selectedMicrophoneID }
    }

    /// Get the display name for the selected microphone
    var selectedMicrophoneName: String {
        if selectedMicrophoneID == "automatic" {
            return "Automatic (System Default)"
        }
        return availableMicrophones.first { $0.id == selectedMicrophoneID }?.name ?? "Unknown"
    }

    // MARK: - Private Methods

    private func setupNotifications() {
        // Listen for audio device changes
        NotificationCenter.default.publisher(for: .AVCaptureDeviceWasConnected)
            .sink { [weak self] _ in
                Task { @MainActor in
                    self?.refreshMicrophones()
                }
            }
            .store(in: &cancellables)

        NotificationCenter.default.publisher(for: .AVCaptureDeviceWasDisconnected)
            .sink { [weak self] _ in
                Task { @MainActor in
                    self?.refreshMicrophones()
                }
            }
            .store(in: &cancellables)
    }

    private func getDefaultInputDeviceID() -> String? {
        var deviceID: AudioDeviceID = 0
        var size = UInt32(MemoryLayout<AudioDeviceID>.size)

        var address = AudioObjectPropertyAddress(
            mSelector: kAudioHardwarePropertyDefaultInputDevice,
            mScope: kAudioObjectPropertyScopeGlobal,
            mElement: kAudioObjectPropertyElementMain
        )

        let status = AudioObjectGetPropertyData(
            AudioObjectID(kAudioObjectSystemObject),
            &address,
            0,
            nil,
            &size,
            &deviceID
        )

        guard status == noErr else { return nil }

        // Get the UID for this device
        var uid: CFString?
        size = UInt32(MemoryLayout<CFString?>.size)

        address = AudioObjectPropertyAddress(
            mSelector: kAudioDevicePropertyDeviceUID,
            mScope: kAudioObjectPropertyScopeGlobal,
            mElement: kAudioObjectPropertyElementMain
        )

        let uidStatus = AudioObjectGetPropertyData(
            deviceID,
            &address,
            0,
            nil,
            &size,
            &uid
        )

        guard uidStatus == noErr, let uidString = uid as String? else { return nil }
        return uidString
    }
}
