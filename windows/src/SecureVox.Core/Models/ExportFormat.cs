namespace SecureVox.Core.Models;

/// <summary>
/// Supported export formats for transcripts
/// </summary>
public enum ExportFormat
{
    Txt,
    Srt,
    Vtt,
    Json
}

public static class ExportFormatExtensions
{
    public static string GetDisplayName(this ExportFormat format) => format switch
    {
        ExportFormat.Txt => "Plain Text (.txt)",
        ExportFormat.Srt => "SubRip Subtitle (.srt)",
        ExportFormat.Vtt => "WebVTT (.vtt)",
        ExportFormat.Json => "JSON (.json)",
        _ => format.ToString()
    };

    public static string GetFileExtension(this ExportFormat format) => format switch
    {
        ExportFormat.Txt => ".txt",
        ExportFormat.Srt => ".srt",
        ExportFormat.Vtt => ".vtt",
        ExportFormat.Json => ".json",
        _ => ".txt"
    };

    public static string GetMimeType(this ExportFormat format) => format switch
    {
        ExportFormat.Txt => "text/plain",
        ExportFormat.Srt => "application/x-subrip",
        ExportFormat.Vtt => "text/vtt",
        ExportFormat.Json => "application/json",
        _ => "text/plain"
    };
}
