using Microsoft.Extensions.DependencyInjection;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using SecureVox.App.ViewModels;

namespace SecureVox.App.Views;

public sealed partial class SettingsPage : Page
{
    private SettingsViewModel ViewModel { get; }

    public SettingsPage()
    {
        this.InitializeComponent();
        ViewModel = App.Current.Services.GetRequiredService<SettingsViewModel>();

        ViewModel.CheckModelStatusCommand.Execute(null);
        ModelStatus.Text = ViewModel.CurrentModelStatus ?? string.Empty;
    }

    private async void DownloadModel_Click(object sender, RoutedEventArgs e)
    {
        if (ModelSelector.SelectedItem is RadioButton selected && selected.Tag is string modelName)
        {
            DownloadProgress.Visibility = Visibility.Visible;
            DownloadModelButton.IsEnabled = false;

            var progress = new Progress<double>(value =>
            {
                DownloadProgress.Value = value;
            });

            await ViewModel.DownloadModelCommand.ExecuteAsync(modelName);

            DownloadProgress.Visibility = Visibility.Collapsed;
            DownloadModelButton.IsEnabled = true;
            ModelStatus.Text = ViewModel.CurrentModelStatus ?? string.Empty;
        }
    }
}
