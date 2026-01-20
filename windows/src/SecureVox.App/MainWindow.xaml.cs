using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using SecureVox.App.Views;

namespace SecureVox.App;

/// <summary>
/// Main application window with navigation
/// </summary>
public sealed partial class MainWindow : Window
{
    public MainWindow()
    {
        this.InitializeComponent();

        // Set window title and size
        Title = "SecureVox - Voice Transcription";
        AppWindow.Resize(new Windows.Graphics.SizeInt32(1200, 800));

        // Navigate to recordings page by default
        ContentFrame.Navigate(typeof(RecordingsPage));
        NavView.SelectedItem = NavView.MenuItems[0];
    }

    private void NavView_SelectionChanged(NavigationView sender, NavigationViewSelectionChangedEventArgs args)
    {
        if (args.IsSettingsSelected)
        {
            ContentFrame.Navigate(typeof(SettingsPage));
            return;
        }

        if (args.SelectedItem is NavigationViewItem item)
        {
            var tag = item.Tag?.ToString();
            var pageType = tag switch
            {
                "Recordings" => typeof(RecordingsPage),
                "Vocabulary" => typeof(CustomDictionaryPage),
                "Shortcuts" => typeof(ShortcutsPage),
                "FAQ" => typeof(FAQPage),
                "RecycleBin" => typeof(RecycleBinPage),
                _ => typeof(RecordingsPage)
            };

            ContentFrame.Navigate(pageType);
        }
    }
}
