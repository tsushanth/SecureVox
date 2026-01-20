using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using System.Collections.ObjectModel;

namespace SecureVox.App.Views;

public sealed partial class CustomDictionaryPage : Page
{
    private readonly ObservableCollection<string> _words = new();

    public CustomDictionaryPage()
    {
        this.InitializeComponent();
        WordsList.ItemsSource = _words;
        UpdateWordCount();
    }

    private void AddWord_Click(object sender, RoutedEventArgs e)
    {
        var word = NewWordBox.Text?.Trim();
        if (!string.IsNullOrEmpty(word) && !_words.Contains(word))
        {
            _words.Add(word);
            NewWordBox.Text = string.Empty;
            UpdateWordCount();
        }
    }

    private void RemoveWord_Click(object sender, RoutedEventArgs e)
    {
        if (sender is Button button && button.Tag is string word)
        {
            _words.Remove(word);
            UpdateWordCount();
        }
    }

    private async void Import_Click(object sender, RoutedEventArgs e)
    {
        // TODO: Implement import from file
        var dialog = new ContentDialog
        {
            Title = "Import",
            Content = "Import words from a text file (one word per line)",
            PrimaryButtonText = "Select File",
            CloseButtonText = "Cancel",
            XamlRoot = this.XamlRoot
        };
        await dialog.ShowAsync();
    }

    private async void Export_Click(object sender, RoutedEventArgs e)
    {
        // TODO: Implement export to file
        var dialog = new ContentDialog
        {
            Title = "Export",
            Content = $"Export {_words.Count} words to a text file",
            PrimaryButtonText = "Save",
            CloseButtonText = "Cancel",
            XamlRoot = this.XamlRoot
        };
        await dialog.ShowAsync();
    }

    private async void ClearAll_Click(object sender, RoutedEventArgs e)
    {
        var dialog = new ContentDialog
        {
            Title = "Clear All",
            Content = "Are you sure you want to remove all custom words?",
            PrimaryButtonText = "Clear",
            CloseButtonText = "Cancel",
            XamlRoot = this.XamlRoot
        };

        var result = await dialog.ShowAsync();
        if (result == ContentDialogResult.Primary)
        {
            _words.Clear();
            UpdateWordCount();
        }
    }

    private void UpdateWordCount()
    {
        WordCount.Text = $"{_words.Count} / 150 words";
    }
}
