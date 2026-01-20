using System.Collections.ObjectModel;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using Microsoft.EntityFrameworkCore;
using SecureVox.Core.Data;
using SecureVox.Core.Models;

namespace SecureVox.App.ViewModels;

/// <summary>
/// ViewModel for the recordings list page
/// </summary>
public partial class RecordingsViewModel : ViewModelBase
{
    private readonly SecureVoxDbContext _dbContext;

    [ObservableProperty]
    private ObservableCollection<Recording> _recordings = new();

    [ObservableProperty]
    private Recording? _selectedRecording;

    [ObservableProperty]
    private string _searchQuery = string.Empty;

    [ObservableProperty]
    private SourceType? _filterSourceType;

    [ObservableProperty]
    private bool _isRecording;

    [ObservableProperty]
    private TimeSpan _recordingDuration;

    [ObservableProperty]
    private float _audioLevel;

    public RecordingsViewModel(SecureVoxDbContext dbContext)
    {
        _dbContext = dbContext;
    }

    [RelayCommand]
    private async Task LoadRecordingsAsync()
    {
        try
        {
            IsLoading = true;
            ClearError();

            var query = _dbContext.Recordings
                .Include(r => r.Segments)
                .Where(r => !r.IsDeleted)
                .AsQueryable();

            // Apply search filter
            if (!string.IsNullOrWhiteSpace(SearchQuery))
            {
                var searchLower = SearchQuery.ToLower();
                query = query.Where(r =>
                    r.Title.ToLower().Contains(searchLower) ||
                    r.Segments.Any(s => s.Text.ToLower().Contains(searchLower)));
            }

            // Apply source type filter
            if (FilterSourceType.HasValue)
            {
                query = query.Where(r => r.SourceType == FilterSourceType.Value);
            }

            var recordings = await query
                .OrderByDescending(r => r.CreatedAt)
                .ToListAsync();

            Recordings.Clear();
            foreach (var recording in recordings)
            {
                Recordings.Add(recording);
            }
        }
        catch (Exception ex)
        {
            SetError($"Failed to load recordings: {ex.Message}");
        }
        finally
        {
            IsLoading = false;
        }
    }

    [RelayCommand]
    private async Task StartRecordingAsync()
    {
        // TODO: Implement audio recording with NAudio
        IsRecording = true;
        RecordingDuration = TimeSpan.Zero;
    }

    [RelayCommand]
    private async Task StopRecordingAsync()
    {
        // TODO: Implement stop recording
        IsRecording = false;

        // Create new recording entry
        var recording = new Recording
        {
            Title = $"Recording {DateTime.Now:yyyy-MM-dd HH:mm}",
            SourceType = SourceType.Recorded,
            Duration = RecordingDuration.TotalSeconds,
            TranscriptionStatus = TranscriptionStatus.Pending
        };

        _dbContext.Recordings.Add(recording);
        await _dbContext.SaveChangesAsync();

        Recordings.Insert(0, recording);
        SelectedRecording = recording;
    }

    [RelayCommand]
    private async Task DeleteRecordingAsync(Recording? recording)
    {
        if (recording == null) return;

        // Soft delete
        recording.IsDeleted = true;
        recording.DeletedAt = DateTime.UtcNow;
        recording.UpdatedAt = DateTime.UtcNow;

        await _dbContext.SaveChangesAsync();
        Recordings.Remove(recording);

        if (SelectedRecording == recording)
        {
            SelectedRecording = Recordings.FirstOrDefault();
        }
    }

    [RelayCommand]
    private async Task ToggleFavoriteAsync(Recording? recording)
    {
        if (recording == null) return;

        recording.IsFavorite = !recording.IsFavorite;
        recording.UpdatedAt = DateTime.UtcNow;

        await _dbContext.SaveChangesAsync();
    }

    partial void OnSearchQueryChanged(string value)
    {
        // Debounced search could be implemented here
        _ = LoadRecordingsAsync();
    }

    partial void OnFilterSourceTypeChanged(SourceType? value)
    {
        _ = LoadRecordingsAsync();
    }
}
