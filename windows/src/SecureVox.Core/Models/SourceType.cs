namespace SecureVox.Core.Models;

/// <summary>
/// The source/origin type of a recording
/// </summary>
public enum SourceType
{
    Recorded,
    Imported,
    Meeting,
    Quick
}

public static class SourceTypeExtensions
{
    public static string GetDisplayName(this SourceType sourceType) => sourceType switch
    {
        SourceType.Recorded => "Recorded",
        SourceType.Imported => "Imported",
        SourceType.Meeting => "Meeting",
        SourceType.Quick => "Quick",
        _ => sourceType.ToString()
    };

    public static string GetIcon(this SourceType sourceType) => sourceType switch
    {
        SourceType.Recorded => "\uE720",    // Microphone
        SourceType.Imported => "\uE896",    // Download
        SourceType.Meeting => "\uE714",     // Video
        SourceType.Quick => "\uE945",       // Lightning bolt
        _ => "\uE8A5"                        // Document
    };
}
