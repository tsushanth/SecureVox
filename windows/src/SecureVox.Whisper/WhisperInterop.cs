using System.Runtime.InteropServices;

namespace SecureVox.Whisper;

/// <summary>
/// P/Invoke declarations for the native whisper_native.dll
/// </summary>
internal static class WhisperInterop
{
    private const string DllName = "whisper_native";

    /// <summary>
    /// Progress callback delegate matching the native signature
    /// </summary>
    [UnmanagedFunctionPointer(CallingConvention.Cdecl)]
    public delegate void ProgressCallback(int progress, IntPtr userData);

    /// <summary>
    /// Initialize whisper context from model file
    /// </summary>
    /// <param name="modelPath">Path to the model file</param>
    /// <returns>Opaque pointer to context, or IntPtr.Zero on failure</returns>
    [DllImport(DllName, CallingConvention = CallingConvention.Cdecl, CharSet = CharSet.Ansi)]
    public static extern IntPtr whisper_wrapper_init(string modelPath);

    /// <summary>
    /// Free whisper context
    /// </summary>
    [DllImport(DllName, CallingConvention = CallingConvention.Cdecl)]
    public static extern void whisper_wrapper_free(IntPtr ctx);

    /// <summary>
    /// Transcribe audio samples
    /// </summary>
    /// <param name="ctx">Whisper context</param>
    /// <param name="audioData">Float array of audio samples (16kHz, mono, normalized [-1, 1])</param>
    /// <param name="nSamples">Number of samples</param>
    /// <param name="language">Language code (e.g., "en", "auto")</param>
    /// <param name="progressCallback">Optional progress callback</param>
    /// <param name="userData">User data for callback</param>
    /// <returns>JSON string with segments, or IntPtr.Zero on failure</returns>
    [DllImport(DllName, CallingConvention = CallingConvention.Cdecl, CharSet = CharSet.Ansi)]
    public static extern IntPtr whisper_wrapper_transcribe(
        IntPtr ctx,
        [In] float[] audioData,
        int nSamples,
        string language,
        ProgressCallback? progressCallback,
        IntPtr userData);

    /// <summary>
    /// Free string returned by whisper_wrapper_transcribe
    /// </summary>
    [DllImport(DllName, CallingConvention = CallingConvention.Cdecl)]
    public static extern void whisper_wrapper_free_string(IntPtr str);

    /// <summary>
    /// Get system info string
    /// </summary>
    [DllImport(DllName, CallingConvention = CallingConvention.Cdecl)]
    public static extern IntPtr whisper_wrapper_get_system_info();

    /// <summary>
    /// Check if model is multilingual
    /// </summary>
    [DllImport(DllName, CallingConvention = CallingConvention.Cdecl)]
    public static extern int whisper_wrapper_is_multilingual(IntPtr ctx);

    /// <summary>
    /// Get last error message
    /// </summary>
    [DllImport(DllName, CallingConvention = CallingConvention.Cdecl)]
    public static extern IntPtr whisper_wrapper_get_last_error();
}
