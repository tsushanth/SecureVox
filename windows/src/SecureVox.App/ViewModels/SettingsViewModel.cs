using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using SecureVox.Core.Configuration;

namespace SecureVox.App.ViewModels;

/// <summary>
/// ViewModel for the settings page
/// </summary>
public partial class SettingsViewModel : ViewModelBase
{
    [ObservableProperty]
    private string _selectedModel = AppConstants.Models.TinyModel;

    [ObservableProperty]
    private string _selectedLanguage = AppConstants.Transcription.DefaultLanguage;

    [ObservableProperty]
    private bool _autoTranscribe = true;

    [ObservableProperty]
    private bool _startWithWindows;

    [ObservableProperty]
    private int _recycleBinRetentionDays = AppConstants.RecycleBin.DefaultRetentionDays;

    [ObservableProperty]
    private bool _isModelDownloading;

    [ObservableProperty]
    private double _modelDownloadProgress;

    [ObservableProperty]
    private string? _currentModelStatus;

    public List<ModelOption> AvailableModels { get; } = new()
    {
        new ModelOption(AppConstants.Models.TinyModel, "Tiny", "~75 MB", "Fast, good accuracy"),
        new ModelOption(AppConstants.Models.BaseModel, "Base", "~148 MB", "Balanced speed and accuracy"),
        new ModelOption(AppConstants.Models.SmallModel, "Small", "~488 MB", "Higher accuracy, slower"),
        new ModelOption(AppConstants.Models.LargeModel, "Large V3 Turbo", "~1.5 GB", "Best accuracy, slowest")
    };

    public List<LanguageOption> AvailableLanguages { get; } = new()
    {
        new LanguageOption("auto", "Auto-detect"),
        new LanguageOption("en", "English"),
        new LanguageOption("es", "Spanish"),
        new LanguageOption("fr", "French"),
        new LanguageOption("de", "German"),
        new LanguageOption("it", "Italian"),
        new LanguageOption("pt", "Portuguese"),
        new LanguageOption("zh", "Chinese"),
        new LanguageOption("ja", "Japanese"),
        new LanguageOption("ko", "Korean"),
        new LanguageOption("ru", "Russian"),
        new LanguageOption("ar", "Arabic"),
        new LanguageOption("hi", "Hindi"),
        // Add more languages as needed
    };

    [RelayCommand]
    private async Task DownloadModelAsync(string modelName)
    {
        if (IsModelDownloading) return;

        try
        {
            IsModelDownloading = true;
            ModelDownloadProgress = 0;
            CurrentModelStatus = $"Downloading {modelName}...";

            var url = $"{AppConstants.Models.HuggingFaceBaseUrl}/{modelName}";
            var localPath = GetModelPath(modelName);

            // Ensure directory exists
            var directory = Path.GetDirectoryName(localPath);
            if (!string.IsNullOrEmpty(directory))
            {
                Directory.CreateDirectory(directory);
            }

            using var httpClient = new HttpClient();
            using var response = await httpClient.GetAsync(url, HttpCompletionOption.ResponseHeadersRead);
            response.EnsureSuccessStatusCode();

            var totalBytes = response.Content.Headers.ContentLength ?? 0;

            await using var contentStream = await response.Content.ReadAsStreamAsync();
            await using var fileStream = new FileStream(localPath, FileMode.Create, FileAccess.Write, FileShare.None);

            var buffer = new byte[81920];
            long totalRead = 0;
            int bytesRead;

            while ((bytesRead = await contentStream.ReadAsync(buffer)) > 0)
            {
                await fileStream.WriteAsync(buffer.AsMemory(0, bytesRead));
                totalRead += bytesRead;

                if (totalBytes > 0)
                {
                    ModelDownloadProgress = (double)totalRead / totalBytes * 100;
                }
            }

            CurrentModelStatus = $"{modelName} downloaded successfully!";
            SelectedModel = modelName;
        }
        catch (Exception ex)
        {
            CurrentModelStatus = $"Download failed: {ex.Message}";
            SetError(ex.Message);
        }
        finally
        {
            IsModelDownloading = false;
        }
    }

    [RelayCommand]
    private void CheckModelStatus()
    {
        var modelPath = GetModelPath(SelectedModel);
        if (File.Exists(modelPath))
        {
            var fileInfo = new FileInfo(modelPath);
            CurrentModelStatus = $"Model ready ({fileInfo.Length / 1024 / 1024} MB)";
        }
        else
        {
            CurrentModelStatus = "Model not downloaded";
        }
    }

    private static string GetModelPath(string modelName)
    {
        var localFolder = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
        return Path.Combine(localFolder, "SecureVox", AppConstants.Storage.ModelsDirectory, modelName);
    }

    public bool IsModelDownloaded(string modelName)
    {
        return File.Exists(GetModelPath(modelName));
    }
}

public record ModelOption(string FileName, string DisplayName, string Size, string Description);
public record LanguageOption(string Code, string Name);
