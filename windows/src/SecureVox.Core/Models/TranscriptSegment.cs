using System.ComponentModel.DataAnnotations;
using System.ComponentModel.DataAnnotations.Schema;

namespace SecureVox.Core.Models;

/// <summary>
/// A segment of transcribed text with timing information
/// </summary>
public class TranscriptSegment
{
    [Key]
    public Guid Id { get; set; } = Guid.NewGuid();

    [Required]
    public string Text { get; set; } = string.Empty;

    /// <summary>
    /// Start time in seconds
    /// </summary>
    public double StartTime { get; set; }

    /// <summary>
    /// End time in seconds
    /// </summary>
    public double EndTime { get; set; }

    /// <summary>
    /// Confidence score (0.0 - 1.0)
    /// </summary>
    public float Confidence { get; set; } = 1.0f;

    /// <summary>
    /// Optional speaker identification
    /// </summary>
    public string? SpeakerLabel { get; set; }

    /// <summary>
    /// Segment index within the recording
    /// </summary>
    public int SegmentIndex { get; set; }

    // Navigation property
    public Guid RecordingId { get; set; }
    public Recording? Recording { get; set; }

    // Computed properties
    [NotMapped]
    public TimeSpan Duration => TimeSpan.FromSeconds(EndTime - StartTime);

    [NotMapped]
    public string FormattedStartTime => FormatTime(StartTime);

    [NotMapped]
    public string FormattedEndTime => FormatTime(EndTime);

    [NotMapped]
    public string FormattedTimeRange => $"{FormattedStartTime} - {FormattedEndTime}";

    /// <summary>
    /// SRT format timestamp for start time (00:00:00,000)
    /// </summary>
    [NotMapped]
    public string SrtStartTimestamp => FormatSrtTime(StartTime);

    /// <summary>
    /// SRT format timestamp for end time (00:00:00,000)
    /// </summary>
    [NotMapped]
    public string SrtEndTimestamp => FormatSrtTime(EndTime);

    /// <summary>
    /// VTT format timestamp for start time (00:00:00.000)
    /// </summary>
    [NotMapped]
    public string VttStartTimestamp => FormatVttTime(StartTime);

    /// <summary>
    /// VTT format timestamp for end time (00:00:00.000)
    /// </summary>
    [NotMapped]
    public string VttEndTimestamp => FormatVttTime(EndTime);

    [NotMapped]
    public bool HasValidTiming => StartTime >= 0 && EndTime >= StartTime;

    private static string FormatTime(double timeInSeconds)
    {
        var time = Math.Max(0, timeInSeconds);
        var minutes = (int)time / 60;
        var seconds = (int)time % 60;
        return $"{minutes}:{seconds:D2}";
    }

    /// <summary>
    /// Format time for SRT subtitle format (00:00:00,000)
    /// </summary>
    private static string FormatSrtTime(double timeInSeconds)
    {
        var time = Math.Max(0, timeInSeconds);
        var hours = (int)time / 3600;
        var minutes = ((int)time % 3600) / 60;
        var seconds = (int)time % 60;
        var milliseconds = (int)((time % 1) * 1000);
        return $"{hours:D2}:{minutes:D2}:{seconds:D2},{milliseconds:D3}";
    }

    /// <summary>
    /// Format time for VTT subtitle format (00:00:00.000)
    /// </summary>
    private static string FormatVttTime(double timeInSeconds)
    {
        var time = Math.Max(0, timeInSeconds);
        var hours = (int)time / 3600;
        var minutes = ((int)time % 3600) / 60;
        var seconds = (int)time % 60;
        var milliseconds = (int)((time % 1) * 1000);
        return $"{hours:D2}:{minutes:D2}:{seconds:D2}.{milliseconds:D3}";
    }
}
