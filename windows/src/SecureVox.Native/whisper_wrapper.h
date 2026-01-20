#pragma once

#ifdef _WIN32
    #ifdef WHISPER_NATIVE_EXPORTS
        #define WHISPER_API __declspec(dllexport)
    #else
        #define WHISPER_API __declspec(dllimport)
    #endif
#else
    #define WHISPER_API __attribute__((visibility("default")))
#endif

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

// Progress callback type
typedef void (*whisper_progress_callback_t)(int progress, void* user_data);

// Initialize whisper context from model file
// Returns: opaque pointer to context, or nullptr on failure
WHISPER_API void* whisper_wrapper_init(const char* model_path);

// Free whisper context
WHISPER_API void whisper_wrapper_free(void* ctx);

// Transcribe audio samples
// audio_data: array of float samples (16kHz, mono, normalized to [-1, 1])
// n_samples: number of samples
// language: language code (e.g., "en", "auto" for auto-detect)
// progress_callback: optional callback for progress updates (0-100)
// user_data: user data passed to callback
// Returns: JSON string with segments array, caller must free with whisper_wrapper_free_string
WHISPER_API const char* whisper_wrapper_transcribe(
    void* ctx,
    const float* audio_data,
    int n_samples,
    const char* language,
    whisper_progress_callback_t progress_callback,
    void* user_data
);

// Free string returned by whisper_wrapper_transcribe
WHISPER_API void whisper_wrapper_free_string(const char* str);

// Get system info string
WHISPER_API const char* whisper_wrapper_get_system_info(void);

// Check if model is multilingual
WHISPER_API int whisper_wrapper_is_multilingual(void* ctx);

// Get last error message
WHISPER_API const char* whisper_wrapper_get_last_error(void);

#ifdef __cplusplus
}
#endif
