namespace SecureVox.Core.Configuration;

/// <summary>
/// Application-wide constants matching the macOS/Android implementations
/// </summary>
public static class AppConstants
{
    public static class App
    {
        public const string Name = "SecureVox";
        public const string Version = "1.0.0";
        public const string BundleId = "com.kreativekoala.securevox";
    }

    public static class Audio
    {
        /// <summary>
        /// Sample rate required by Whisper (16kHz)
        /// </summary>
        public const int WhisperSampleRate = 16000;

        /// <summary>
        /// Sample rate for high-quality recording (44.1kHz)
        /// </summary>
        public const int RecordingSampleRate = 44100;

        /// <summary>
        /// Mono channel for recording
        /// </summary>
        public const int Channels = 1;

        /// <summary>
        /// Bits per sample for PCM recording
        /// </summary>
        public const int BitsPerSample = 16;

        /// <summary>
        /// Minimum recording duration in seconds
        /// </summary>
        public const double MinRecordingDuration = 0.5;

        /// <summary>
        /// Maximum recording duration in seconds (4 hours)
        /// </summary>
        public const double MaxRecordingDuration = 14400;

        /// <summary>
        /// Maximum file size for import in bytes (2GB)
        /// </summary>
        public const long MaxImportFileSize = 2L * 1024 * 1024 * 1024;
    }

    public static class Storage
    {
        public const string RecordingsDirectory = "Recordings";
        public const string ModelsDirectory = "Models";
        public const string TempDirectory = "Temp";
        public const string DatabaseFileName = "securevox.db";

        /// <summary>
        /// Minimum disk space required to start recording (50 MB)
        /// </summary>
        public const long MinSpaceToStart = 50 * 1024 * 1024;

        /// <summary>
        /// Minimum disk space to continue recording (20 MB)
        /// </summary>
        public const long MinSpaceToContinue = 20 * 1024 * 1024;

        /// <summary>
        /// Low space warning threshold (500 MB)
        /// </summary>
        public const long LowSpaceWarning = 500 * 1024 * 1024;

        /// <summary>
        /// Disk space check interval in seconds
        /// </summary>
        public const int SpaceCheckIntervalSeconds = 10;
    }

    public static class Transcription
    {
        /// <summary>
        /// Maximum chunk duration for long audio (55 seconds)
        /// </summary>
        public const double MaxChunkDuration = 55.0;

        /// <summary>
        /// Overlap between chunks (1 second)
        /// </summary>
        public const double ChunkOverlap = 1.0;

        /// <summary>
        /// Default language code
        /// </summary>
        public const string DefaultLanguage = "en";

        /// <summary>
        /// Number of transcription threads
        /// </summary>
        public const int DefaultThreadCount = 4;
    }

    public static class Models
    {
        public const string TinyModel = "ggml-tiny.bin";
        public const string BaseModel = "ggml-base.bin";
        public const string SmallModel = "ggml-small.bin";
        public const string LargeModel = "ggml-large-v3-turbo.bin";

        public const long TinyModelSize = 75_000_000;      // ~75 MB
        public const long BaseModelSize = 148_000_000;     // ~148 MB
        public const long SmallModelSize = 488_000_000;    // ~488 MB
        public const long LargeModelSize = 1_500_000_000;  // ~1.5 GB

        public const string HuggingFaceBaseUrl = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main";
    }

    public static class RecycleBin
    {
        /// <summary>
        /// Default retention period in days
        /// </summary>
        public const int DefaultRetentionDays = 30;

        /// <summary>
        /// Maximum retention period in days
        /// </summary>
        public const int MaxRetentionDays = 90;
    }

    public static class CustomDictionary
    {
        /// <summary>
        /// Maximum number of custom words
        /// </summary>
        public const int MaxWords = 150;
    }

    public static class UI
    {
        /// <summary>
        /// Audio level update interval in milliseconds
        /// </summary>
        public const int AudioLevelUpdateIntervalMs = 16; // ~60 fps

        /// <summary>
        /// Search debounce delay in milliseconds
        /// </summary>
        public const int SearchDebounceMs = 300;
    }

    public static class SupportedFormats
    {
        public static readonly string[] AudioExtensions = { ".mp3", ".m4a", ".wav", ".aiff", ".flac", ".ogg" };
        public static readonly string[] VideoExtensions = { ".mp4", ".mov", ".m4v", ".avi", ".mkv", ".webm" };

        public static bool IsAudioFile(string path)
        {
            var ext = Path.GetExtension(path).ToLowerInvariant();
            return AudioExtensions.Contains(ext);
        }

        public static bool IsVideoFile(string path)
        {
            var ext = Path.GetExtension(path).ToLowerInvariant();
            return VideoExtensions.Contains(ext);
        }

        public static bool IsSupportedMediaFile(string path)
        {
            return IsAudioFile(path) || IsVideoFile(path);
        }
    }
}
