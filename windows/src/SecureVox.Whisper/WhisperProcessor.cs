using System.Runtime.InteropServices;
using System.Text.Json;

namespace SecureVox.Whisper;

/// <summary>
/// High-level wrapper for whisper transcription
/// </summary>
public class WhisperProcessor : IDisposable
{
    private IntPtr _context;
    private bool _disposed;
    private readonly object _lock = new();

    /// <summary>
    /// Whether the processor is initialized with a model
    /// </summary>
    public bool IsInitialized => _context != IntPtr.Zero;

    /// <summary>
    /// Whether the loaded model supports multiple languages
    /// </summary>
    public bool IsMultilingual
    {
        get
        {
            if (!IsInitialized) return false;
            return WhisperInterop.whisper_wrapper_is_multilingual(_context) != 0;
        }
    }

    /// <summary>
    /// Initialize the processor with a model file
    /// </summary>
    /// <param name="modelPath">Path to the GGML model file</param>
    /// <returns>True if successful</returns>
    public bool Initialize(string modelPath)
    {
        if (string.IsNullOrEmpty(modelPath))
            throw new ArgumentNullException(nameof(modelPath));

        if (!File.Exists(modelPath))
            throw new FileNotFoundException("Model file not found", modelPath);

        lock (_lock)
        {
            // Free existing context if any
            if (_context != IntPtr.Zero)
            {
                WhisperInterop.whisper_wrapper_free(_context);
                _context = IntPtr.Zero;
            }

            _context = WhisperInterop.whisper_wrapper_init(modelPath);
            return _context != IntPtr.Zero;
        }
    }

    /// <summary>
    /// Transcribe audio samples
    /// </summary>
    /// <param name="audioSamples">Float array of audio samples (16kHz, mono, normalized [-1, 1])</param>
    /// <param name="language">Language code (e.g., "en", "auto" for auto-detect)</param>
    /// <param name="progress">Optional progress reporter (0-100)</param>
    /// <param name="cancellationToken">Cancellation token</param>
    /// <returns>Transcription result with segments</returns>
    public async Task<TranscriptionResult> TranscribeAsync(
        float[] audioSamples,
        string language = "en",
        IProgress<int>? progress = null,
        CancellationToken cancellationToken = default)
    {
        if (!IsInitialized)
            return TranscriptionResult.Failure("Whisper processor not initialized");

        if (audioSamples == null || audioSamples.Length == 0)
            return TranscriptionResult.Failure("No audio samples provided");

        return await Task.Run(() =>
        {
            lock (_lock)
            {
                if (cancellationToken.IsCancellationRequested)
                    return TranscriptionResult.Failure("Transcription cancelled");

                // Set up progress callback
                WhisperInterop.ProgressCallback? callback = null;
                if (progress != null)
                {
                    callback = (int progressValue, IntPtr userData) =>
                    {
                        progress.Report(progressValue);
                    };
                }

                // Call native transcription
                IntPtr resultPtr = WhisperInterop.whisper_wrapper_transcribe(
                    _context,
                    audioSamples,
                    audioSamples.Length,
                    language,
                    callback,
                    IntPtr.Zero);

                if (resultPtr == IntPtr.Zero)
                {
                    var errorPtr = WhisperInterop.whisper_wrapper_get_last_error();
                    var error = errorPtr != IntPtr.Zero
                        ? Marshal.PtrToStringAnsi(errorPtr) ?? "Unknown error"
                        : "Transcription failed";
                    return TranscriptionResult.Failure(error);
                }

                try
                {
                    // Parse JSON result
                    var jsonString = Marshal.PtrToStringAnsi(resultPtr);
                    if (string.IsNullOrEmpty(jsonString))
                        return TranscriptionResult.Failure("Empty result from transcription");

                    var segments = ParseSegmentsJson(jsonString);
                    return TranscriptionResult.Success(segments);
                }
                finally
                {
                    WhisperInterop.whisper_wrapper_free_string(resultPtr);
                }
            }
        }, cancellationToken);
    }

    /// <summary>
    /// Get system information string
    /// </summary>
    public string GetSystemInfo()
    {
        var ptr = WhisperInterop.whisper_wrapper_get_system_info();
        return ptr != IntPtr.Zero
            ? Marshal.PtrToStringAnsi(ptr) ?? string.Empty
            : string.Empty;
    }

    private static List<TranscriptionSegmentResult> ParseSegmentsJson(string json)
    {
        var segments = new List<TranscriptionSegmentResult>();

        try
        {
            using var doc = JsonDocument.Parse(json);
            var root = doc.RootElement;

            if (root.ValueKind == JsonValueKind.Array)
            {
                foreach (var element in root.EnumerateArray())
                {
                    var text = element.GetProperty("text").GetString() ?? string.Empty;
                    var start = element.GetProperty("start").GetDouble();
                    var end = element.GetProperty("end").GetDouble();

                    segments.Add(new TranscriptionSegmentResult(text, start, end));
                }
            }
        }
        catch (JsonException ex)
        {
            // Log or handle JSON parsing error
            System.Diagnostics.Debug.WriteLine($"Failed to parse segments JSON: {ex.Message}");
        }

        return segments;
    }

    public void Dispose()
    {
        Dispose(true);
        GC.SuppressFinalize(this);
    }

    protected virtual void Dispose(bool disposing)
    {
        if (!_disposed)
        {
            lock (_lock)
            {
                if (_context != IntPtr.Zero)
                {
                    WhisperInterop.whisper_wrapper_free(_context);
                    _context = IntPtr.Zero;
                }
            }
            _disposed = true;
        }
    }

    ~WhisperProcessor()
    {
        Dispose(false);
    }
}
