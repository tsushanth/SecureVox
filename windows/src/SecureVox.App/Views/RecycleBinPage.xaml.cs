using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using SecureVox.Core.Models;

namespace SecureVox.App.Views;

public sealed partial class RecycleBinPage : Page
{
    public RecycleBinPage()
    {
        this.InitializeComponent();
        LoadDeletedRecordings();
    }

    private void LoadDeletedRecordings()
    {
        // TODO: Load deleted recordings from database
        // For now, show empty state
        DeletedList.Visibility = Visibility.Collapsed;
        EmptyState.Visibility = Visibility.Visible;
    }

    private void Restore_Click(object sender, RoutedEventArgs e)
    {
        if (sender is Button button && button.Tag is Recording recording)
        {
            // TODO: Restore recording
        }
    }

    private async void DeletePermanently_Click(object sender, RoutedEventArgs e)
    {
        if (sender is Button button && button.Tag is Recording recording)
        {
            var dialog = new ContentDialog
            {
                Title = "Delete Permanently",
                Content = "This recording will be permanently deleted and cannot be recovered.",
                PrimaryButtonText = "Delete",
                CloseButtonText = "Cancel",
                XamlRoot = this.XamlRoot
            };

            var result = await dialog.ShowAsync();
            if (result == ContentDialogResult.Primary)
            {
                // TODO: Delete permanently
            }
        }
    }

    private async void EmptyBin_Click(object sender, RoutedEventArgs e)
    {
        var dialog = new ContentDialog
        {
            Title = "Empty Recycle Bin",
            Content = "All deleted recordings will be permanently removed. This action cannot be undone.",
            PrimaryButtonText = "Empty",
            CloseButtonText = "Cancel",
            XamlRoot = this.XamlRoot
        };

        var result = await dialog.ShowAsync();
        if (result == ContentDialogResult.Primary)
        {
            // TODO: Empty recycle bin
        }
    }
}
