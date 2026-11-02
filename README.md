# SecureVox

Privacy-first voice transcription app powered by OpenAI's Whisper model. All processing happens entirely on your device - no internet connection required, no data ever leaves your phone.

## Features

- **On-device transcription**: Transcription runs on your phone using Whisper AI (the internet is only used to download speech models)
- **Privacy First**: SecureVox does not upload your audio or transcripts
- **Multiple Languages**: Support for 15+ languages with auto-detection
- **Timed Transcripts**: Word-level timestamps for easy navigation
- **Cross-Platform**: Native apps for iOS and Android

## Platforms

### iOS (`/ios`)
- SwiftUI with iOS 17+
- WhisperKit for CoreML-accelerated transcription
- Apple Speech fallback for older devices
- Mac Catalyst support

### Android (`/android`)
- Jetpack Compose with Material 3
- whisper.cpp for native C++ transcription
- WorkManager for background processing
- Minimum SDK 26 (Android 8.0)

## Getting Started

### iOS

1. Open `ios/VoiceNotesOndevice.xcodeproj` in Xcode
2. Select your development team
3. Build and run

### Android

1. Clone whisper.cpp:
   ```bash
   cd android/app/src/main/cpp
   ./setup_whisper.sh
   ```

2. Download a Whisper model from [HuggingFace](https://huggingface.co/ggerganov/whisper.cpp/tree/main):
   - `ggml-tiny.bin` (~40MB) - Fastest
   - `ggml-base.bin` (~150MB) - Balanced
   - `ggml-small.bin` (~500MB) - Best accuracy

3. Place the model in `android/app/src/main/assets/`

4. Open the project in Android Studio and build

## Architecture

### iOS
```
ios/
├── App/                    # App entry point and constants
├── Models/                 # SwiftData models
├── ViewModels/             # MVVM view models
├── Views/                  # SwiftUI views
├── Services/               # Audio, transcription services
└── CoreML/                 # WhisperKit integration
```

### Android
```
android/app/src/main/
├── java/com/securevox/app/
│   ├── data/              # Room database, repositories
│   ├── presentation/      # Compose UI, ViewModels
│   ├── service/           # Audio, transcription services
│   └── whisper/           # JNI wrapper for whisper.cpp
└── cpp/                   # Native C++ code
    ├── whisper.cpp/       # whisper.cpp library
    └── whisper_jni.cpp    # JNI bridge
```

## Model Performance

| Model | Size | iOS (Neural Engine) | Android (CPU) |
|-------|------|---------------------|---------------|
| Tiny  | 40MB | ~0.5x realtime | ~1x realtime |
| Base  | 150MB | ~1x realtime | ~2-3x realtime |
| Small | 500MB | ~2x realtime | ~5-8x realtime |

*Times are approximate and vary by device*

## Privacy

SecureVox does not upload your audio or transcripts. Optional anonymous crash reports are off unless you turn them on in Settings.

- Transcription runs on your device
- An internet connection is used to download the speech models you choose; it is not needed for recording or transcribing once a model is on the phone
- No account and no ads
- Optional anonymous crash reports (Android): off by default. The app asks once after your first finished transcription, and you can change it any time in Settings. If on, a report contains the app version, Android version, phone model, free storage, the name of the feature that failed, and the error type with code locations. It never contains audio, text, titles or file names.
- Recordings are stored on your device. Android's automatic backup may copy app data to your Google account if you have backup turned on
- You can delete your recordings any time

## License

MIT License - see LICENSE file for details.

## Credits

- [OpenAI Whisper](https://github.com/openai/whisper) - Speech recognition model
- [WhisperKit](https://github.com/argmaxinc/WhisperKit) - iOS CoreML implementation
- [whisper.cpp](https://github.com/ggerganov/whisper.cpp) - C++ implementation
