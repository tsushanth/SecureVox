namespace SecureVox.Whisper;

/// <summary>
/// Result of a transcription segment from whisper
/// </summary>
public record TranscriptionSegmentResult(
    string Text,
    double StartTimeMs,
    double EndTimeMs
)
{
    /// <summary>
    /// Start time in seconds
    /// </summary>
    public double StartTimeSec => StartTimeMs / 1000.0;

    /// <summary>
    /// End time in seconds
    /// </summary>
    public double EndTimeSec => EndTimeMs / 1000.0;

    /// <summary>
    /// Duration in milliseconds
    /// </summary>
    public double DurationMs => EndTimeMs - StartTimeMs;
}

/// <summary>
/// Complete transcription result
/// </summary>
public class TranscriptionResult
{
    public List<TranscriptionSegmentResult> Segments { get; init; } = new();

    public bool IsSuccess { get; init; }

    public string? ErrorMessage { get; init; }

    /// <summary>
    /// Full transcript text
    /// </summary>
    public string FullText => string.Join(" ", Segments.Select(s => s.Text.Trim()));

    /// <summary>
    /// Total duration in milliseconds
    /// </summary>
    public double TotalDurationMs => Segments.Count > 0
        ? Segments.Max(s => s.EndTimeMs)
        : 0;

    public static TranscriptionResult Success(List<TranscriptionSegmentResult> segments) => new()
    {
        Segments = segments,
        IsSuccess = true
    };

    public static TranscriptionResult Failure(string error) => new()
    {
        IsSuccess = false,
        ErrorMessage = error
    };
}
