using Microsoft.Extensions.DependencyInjection;
using Microsoft.UI.Xaml;
using SecureVox.Core.Data;
using SecureVox.Whisper;

namespace SecureVox.App;

/// <summary>
/// Provides application-specific behavior to supplement the default Application class.
/// </summary>
public partial class App : Application
{
    private Window? _window;

    /// <summary>
    /// Gets the current App instance.
    /// </summary>
    public static new App Current => (App)Application.Current;

    /// <summary>
    /// Gets the service provider for dependency injection.
    /// </summary>
    public IServiceProvider Services { get; }

    /// <summary>
    /// Gets the main window.
    /// </summary>
    public Window? MainWindow => _window;

    public App()
    {
        Services = ConfigureServices();
        this.InitializeComponent();
    }

    /// <summary>
    /// Invoked when the application is launched.
    /// </summary>
    protected override void OnLaunched(LaunchActivatedEventArgs args)
    {
        // Ensure database is created
        using (var scope = Services.CreateScope())
        {
            var dbContext = scope.ServiceProvider.GetRequiredService<SecureVoxDbContext>();
            dbContext.Database.EnsureCreated();
        }

        _window = new MainWindow();
        _window.Activate();
    }

    private static IServiceProvider ConfigureServices()
    {
        var services = new ServiceCollection();

        // Database
        services.AddDbContext<SecureVoxDbContext>();

        // Whisper processor (singleton for model caching)
        services.AddSingleton<WhisperProcessor>();

        // ViewModels
        services.AddTransient<ViewModels.RecordingsViewModel>();
        services.AddTransient<ViewModels.SettingsViewModel>();

        return services.BuildServiceProvider();
    }
}
