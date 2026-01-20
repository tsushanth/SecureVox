using Microsoft.Extensions.DependencyInjection;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using SecureVox.App.ViewModels;
using SecureVox.Core.Models;

namespace SecureVox.App.Views;

/// <summary>
/// Recordings list page
/// </summary>
public sealed partial class RecordingsPage : Page
{
    private RecordingsViewModel ViewModel { get; }

    public RecordingsPage()
    {
        this.InitializeComponent();
        ViewModel = App.Current.Services.GetRequiredService<RecordingsViewModel>();
    }

    private async void Page_Loaded(object sender, RoutedEventArgs e)
    {
        await ViewModel.LoadRecordingsCommand.ExecuteAsync(null);
        RecordingsList.ItemsSource = ViewModel.Recordings;
    }

    private void RecordingsList_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (RecordingsList.SelectedItem is Recording recording)
        {
            ViewModel.SelectedRecording = recording;
            ShowRecordingDetail(recording);
        }
        else
        {
            ViewModel.SelectedRecording = null;
            HideRecordingDetail();
        }
    }

    private void ShowRecordingDetail(Recording recording)
    {
        DetailPanel.Visibility = Visibility.Visible;
        EmptyState.Visibility = Visibility.Collapsed;

        DetailTitle.Text = recording.Title;
        DetailDuration.Text = recording.FormattedDuration;
        DetailDate.Text = recording.FormattedDate;
        TranscriptText.Text = recording.HasTranscript
            ? recording.FullTranscript
            : "No transcript available. Click 'Transcribe' to generate one.";
    }

    private void HideRecordingDetail()
    {
        DetailPanel.Visibility = Visibility.Collapsed;
        EmptyState.Visibility = Visibility.Visible;
    }

    private async void RecordButton_Click(object sender, RoutedEventArgs e)
    {
        // TODO: Show recording UI
        if (ViewModel.IsRecording)
        {
            await ViewModel.StopRecordingCommand.ExecuteAsync(null);
        }
        else
        {
            await ViewModel.StartRecordingCommand.ExecuteAsync(null);
        }
    }

    private async void ImportButton_Click(object sender, RoutedEventArgs e)
    {
        var picker = new Windows.Storage.Pickers.FileOpenPicker();
        picker.SuggestedStartLocation = Windows.Storage.Pickers.PickerLocationId.MusicLibrary;
        picker.FileTypeFilter.Add(".mp3");
        picker.FileTypeFilter.Add(".m4a");
        picker.FileTypeFilter.Add(".wav");
        picker.FileTypeFilter.Add(".mp4");
        picker.FileTypeFilter.Add(".mov");

        // Initialize the picker with the window handle
        var hwnd = WinRT.Interop.WindowNative.GetWindowHandle(App.Current.MainWindow);
        WinRT.Interop.InitializeWithWindow.Initialize(picker, hwnd);

        var file = await picker.PickSingleFileAsync();
        if (file != null)
        {
            // TODO: Import the file
            var dialog = new ContentDialog
            {
                Title = "Import",
                Content = $"Importing: {file.Name}",
                CloseButtonText = "OK",
                XamlRoot = this.XamlRoot
            };
            await dialog.ShowAsync();
        }
    }

    private void PlayButton_Click(object sender, RoutedEventArgs e)
    {
        // TODO: Implement playback
    }

    private async void ExportButton_Click(object sender, RoutedEventArgs e)
    {
        if (ViewModel.SelectedRecording == null) return;

        var dialog = new ContentDialog
        {
            Title = "Export Transcript",
            Content = "Choose export format:",
            PrimaryButtonText = "TXT",
            SecondaryButtonText = "SRT",
            CloseButtonText = "Cancel",
            XamlRoot = this.XamlRoot
        };

        var result = await dialog.ShowAsync();
        // TODO: Implement export based on result
    }

    private async void DeleteButton_Click(object sender, RoutedEventArgs e)
    {
        if (ViewModel.SelectedRecording == null) return;

        var dialog = new ContentDialog
        {
            Title = "Delete Recording",
            Content = "This recording will be moved to the recycle bin.",
            PrimaryButtonText = "Delete",
            CloseButtonText = "Cancel",
            XamlRoot = this.XamlRoot
        };

        var result = await dialog.ShowAsync();
        if (result == ContentDialogResult.Primary)
        {
            await ViewModel.DeleteRecordingCommand.ExecuteAsync(ViewModel.SelectedRecording);
        }
    }
}
