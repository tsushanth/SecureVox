using System.ComponentModel.DataAnnotations;
using System.ComponentModel.DataAnnotations.Schema;

namespace SecureVox.Core.Models;

/// <summary>
/// A voice recording with optional transcription
/// </summary>
public class Recording
{
    [Key]
    public Guid Id { get; set; } = Guid.NewGuid();

    [Required]
    [MaxLength(500)]
    public string Title { get; set; } = string.Empty;

    public DateTime CreatedAt { get; set; } = DateTime.UtcNow;
    public DateTime UpdatedAt { get; set; } = DateTime.UtcNow;

    /// <summary>
    /// Duration in seconds
    /// </summary>
    public double Duration { get; set; }

    [MaxLength(260)]
    public string? AudioFileName { get; set; }

    public long AudioFileSize { get; set; }

    public TranscriptionStatus TranscriptionStatus { get; set; } = TranscriptionStatus.Pending;

    /// <summary>
    /// Transcription progress (0.0 - 1.0)
    /// </summary>
    public double TranscriptionProgress { get; set; }

    public SourceType SourceType { get; set; } = SourceType.Recorded;

    public bool IsFavorite { get; set; }

    public bool IsDeleted { get; set; }

    public DateTime? DeletedAt { get; set; }

    // Transcription metadata
    [MaxLength(100)]
    public string? TranscriptionModel { get; set; }

    [MaxLength(100)]
    public string? TranscriptionEngine { get; set; }

    [MaxLength(10)]
    public string? DetectedLanguage { get; set; }

    [MaxLength(1000)]
    public string? TranscriptionError { get; set; }

    [MaxLength(260)]
    public string? OriginalFileName { get; set; }

    [MaxLength(10)]
    public string? Language { get; set; }

    // Navigation property - segments
    public ICollection<TranscriptSegment> Segments { get; set; } = new List<TranscriptSegment>();

    // Computed properties (not mapped to database)
    [NotMapped]
    public string FullTranscript
    {
        get
        {
            if (Segments == null || !Segments.Any())
                return string.Empty;

            return string.Join(" ", Segments
                .OrderBy(s => s.StartTime)
                .Select(s => s.Text));
        }
    }

    [NotMapped]
    public string FormattedDuration
    {
        get
        {
            var hours = (int)Duration / 3600;
            var minutes = ((int)Duration % 3600) / 60;
            var seconds = (int)Duration % 60;

            return hours > 0
                ? $"{hours}:{minutes:D2}:{seconds:D2}"
                : $"{minutes}:{seconds:D2}";
        }
    }

    [NotMapped]
    public string FormattedDate
    {
        get
        {
            var now = DateTime.Now;
            var created = CreatedAt.ToLocalTime();

            if (created.Date == now.Date)
                return $"Today {created:HH:mm}";
            if (created.Date == now.Date.AddDays(-1))
                return $"Yesterday {created:HH:mm}";
            if (created.Year == now.Year)
                return created.ToString("MMM d, HH:mm");

            return created.ToString("MMM d, yyyy HH:mm");
        }
    }

    [NotMapped]
    public string FormattedFileSize
    {
        get
        {
            string[] sizes = { "B", "KB", "MB", "GB" };
            double len = AudioFileSize;
            int order = 0;
            while (len >= 1024 && order < sizes.Length - 1)
            {
                order++;
                len /= 1024;
            }
            return $"{len:0.##} {sizes[order]}";
        }
    }

    [NotMapped]
    public bool HasAudio => !string.IsNullOrEmpty(AudioFileName) && AudioFileSize > 0;

    [NotMapped]
    public bool HasTranscript => Segments != null && Segments.Any();

    [NotMapped]
    public int? DaysUntilPermanentDeletion
    {
        get
        {
            if (!IsDeleted || DeletedAt == null)
                return null;

            // Default retention period of 30 days
            const int retentionDays = 30;
            var expirationDate = DeletedAt.Value.AddDays(retentionDays);
            var daysRemaining = (expirationDate - DateTime.UtcNow).Days;
            return Math.Max(0, daysRemaining);
        }
    }

    [NotMapped]
    public string? AudioFilePath
    {
        get
        {
            if (string.IsNullOrEmpty(AudioFileName))
                return null;

            var localFolder = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
            return Path.Combine(localFolder, "SecureVox", "Recordings", AudioFileName);
        }
    }
}
