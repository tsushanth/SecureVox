# VoiceNotesOndevice

A fully offline speech-to-text iOS app powered by Whisper CoreML. All transcription happens on-device - your audio never leaves your iPhone.

## Features

- **Unlimited Recording** - Record audio directly in the app
- **Import Media** - Import videos from Camera Roll or audio files from Files
- **100% Offline** - Whisper model runs locally via CoreML
- **Multi-Language** - Auto-detect or manually select from 99 languages
- **Export Options** - TXT, SRT, VTT subtitle formats
- **Privacy First** - Audio never leaves your device

## Requirements

- iOS 16.0+
- Xcode 15.0+
- Swift 5.9+

## Project Structure

```
VoiceNotesOndevice/
├── App/                    # App entry point and configuration
├── Models/                 # SwiftData models
├── ViewModels/             # MVVM view models
├── Views/                  # SwiftUI views
│   ├── Recordings/         # Library screen
│   ├── Recording/          # Recording UI
│   ├── Detail/             # Transcript detail
│   ├── Import/             # Media import
│   ├── Export/             # Export functionality
│   ├── Settings/           # Settings screen
│   ├── FAQ/                # Help & FAQ
│   └── Components/         # Reusable components
├── Services/               # Business logic services
├── CoreML/                 # Whisper model integration
├── Utils/                  # Utilities and helpers
└── Resources/              # Assets and configuration
```

## Setup

1. Clone the repository
2. Open `VoiceNotesOndevice.xcodeproj` in Xcode
3. Download Whisper CoreML model (see CoreML/README.md)
4. Build and run on device (Neural Engine required for optimal performance)

## Whisper Models

| Model | Size | RAM | Speed | Quality |
|-------|------|-----|-------|---------|
| Tiny | ~75MB | ~200MB | 10x realtime | Basic |
| Base | ~150MB | ~400MB | 5x realtime | Good |
| Small | ~500MB | ~1GB | 2x realtime | Great |

The app ships with the Tiny model. Larger models available via On-Demand Resources.

## Privacy

- All audio processing happens on-device
- No network calls for transcription
- No analytics or tracking
- Audio stored only in app's private container

## License

Copyright © 2025. All rights reserved.
