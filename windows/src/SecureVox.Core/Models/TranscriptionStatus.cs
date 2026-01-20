namespace SecureVox.Core.Models;

/// <summary>
/// Status of the transcription process for a recording
/// </summary>
public enum TranscriptionStatus
{
    Pending,
    InProgress,
    Completed,
    Failed
}

public static class TranscriptionStatusExtensions
{
    public static string GetDisplayName(this TranscriptionStatus status) => status switch
    {
        TranscriptionStatus.Pending => "Pending",
        TranscriptionStatus.InProgress => "Transcribing...",
        TranscriptionStatus.Completed => "Completed",
        TranscriptionStatus.Failed => "Failed",
        _ => status.ToString()
    };

    public static string GetIcon(this TranscriptionStatus status) => status switch
    {
        TranscriptionStatus.Pending => "\uE823",      // Clock
        TranscriptionStatus.InProgress => "\uE720",   // Processing
        TranscriptionStatus.Completed => "\uE73E",    // Checkmark
        TranscriptionStatus.Failed => "\uE7BA",       // Warning
        _ => "\uE946"                                  // Info
    };
}
