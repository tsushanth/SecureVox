using Microsoft.EntityFrameworkCore;
using SecureVox.Core.Models;

namespace SecureVox.Core.Data;

/// <summary>
/// Entity Framework Core database context for SecureVox
/// </summary>
public class SecureVoxDbContext : DbContext
{
    public DbSet<Recording> Recordings => Set<Recording>();
    public DbSet<TranscriptSegment> TranscriptSegments => Set<TranscriptSegment>();

    private readonly string _dbPath;

    public SecureVoxDbContext()
    {
        var localFolder = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
        var appFolder = Path.Combine(localFolder, "SecureVox");
        Directory.CreateDirectory(appFolder);
        _dbPath = Path.Combine(appFolder, "securevox.db");
    }

    public SecureVoxDbContext(DbContextOptions<SecureVoxDbContext> options) : base(options)
    {
        var localFolder = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
        var appFolder = Path.Combine(localFolder, "SecureVox");
        Directory.CreateDirectory(appFolder);
        _dbPath = Path.Combine(appFolder, "securevox.db");
    }

    protected override void OnConfiguring(DbContextOptionsBuilder options)
    {
        if (!options.IsConfigured)
        {
            options.UseSqlite($"Data Source={_dbPath}");
        }
    }

    protected override void OnModelCreating(ModelBuilder modelBuilder)
    {
        base.OnModelCreating(modelBuilder);

        // Recording configuration
        modelBuilder.Entity<Recording>(entity =>
        {
            entity.HasKey(e => e.Id);

            entity.HasIndex(e => e.CreatedAt);
            entity.HasIndex(e => e.IsDeleted);
            entity.HasIndex(e => e.IsFavorite);
            entity.HasIndex(e => e.TranscriptionStatus);

            entity.Property(e => e.TranscriptionStatus)
                .HasConversion<string>();

            entity.Property(e => e.SourceType)
                .HasConversion<string>();

            // Configure relationship with segments (cascade delete)
            entity.HasMany(e => e.Segments)
                .WithOne(s => s.Recording)
                .HasForeignKey(s => s.RecordingId)
                .OnDelete(DeleteBehavior.Cascade);
        });

        // TranscriptSegment configuration
        modelBuilder.Entity<TranscriptSegment>(entity =>
        {
            entity.HasKey(e => e.Id);

            entity.HasIndex(e => e.RecordingId);
            entity.HasIndex(e => e.SegmentIndex);
        });
    }
}
