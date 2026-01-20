#include "whisper_wrapper.h"
#include "whisper.h"

#include <string>
#include <thread>
#include <cstring>
#include <mutex>

// Thread-safe error message storage
static std::string g_last_error;
static std::mutex g_error_mutex;

static void set_error(const std::string& error) {
    std::lock_guard<std::mutex> lock(g_error_mutex);
    g_last_error = error;
}

extern "C" {

WHISPER_API void* whisper_wrapper_init(const char* model_path) {
    if (!model_path) {
        set_error("Model path is null");
        return nullptr;
    }

    struct whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;  // CPU only for maximum compatibility

    whisper_context* ctx = whisper_init_from_file_with_params(model_path, cparams);

    if (ctx == nullptr) {
        set_error("Failed to load model from: " + std::string(model_path));
        return nullptr;
    }

    return ctx;
}

WHISPER_API void whisper_wrapper_free(void* ctx) {
    if (ctx != nullptr) {
        whisper_free(static_cast<whisper_context*>(ctx));
    }
}

// Callback data structure
struct CallbackData {
    whisper_progress_callback_t callback;
    void* user_data;
};

WHISPER_API const char* whisper_wrapper_transcribe(
    void* ctx,
    const float* audio_data,
    int n_samples,
    const char* language,
    whisper_progress_callback_t progress_callback,
    void* user_data
) {
    if (ctx == nullptr) {
        set_error("Context is null");
        return nullptr;
    }

    if (audio_data == nullptr || n_samples <= 0) {
        set_error("Invalid audio data");
        return nullptr;
    }

    auto* whisper_ctx = static_cast<whisper_context*>(ctx);

    // Configure whisper parameters
    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_realtime = false;
    params.print_progress = false;
    params.print_timestamps = true;
    params.print_special = false;
    params.translate = false;
    params.language = language ? language : "en";
    params.n_threads = std::min(4, static_cast<int>(std::thread::hardware_concurrency()));
    params.offset_ms = 0;
    params.no_context = true;
    params.single_segment = false;

    // Set up progress callback
    CallbackData cbData = { progress_callback, user_data };

    if (progress_callback != nullptr) {
        params.progress_callback_user_data = &cbData;
        params.progress_callback = [](struct whisper_context* /*ctx*/,
                                      struct whisper_state* /*state*/,
                                      int progress,
                                      void* user_data) {
            auto* data = static_cast<CallbackData*>(user_data);
            if (data->callback != nullptr) {
                data->callback(progress, data->user_data);
            }
        };
    }

    // Run transcription
    int result = whisper_full(whisper_ctx, params, audio_data, n_samples);

    if (result != 0) {
        set_error("Transcription failed with code: " + std::to_string(result));
        return nullptr;
    }

    // Build result JSON with segments
    std::string jsonResult = "[";
    int numSegments = whisper_full_n_segments(whisper_ctx);

    for (int i = 0; i < numSegments; i++) {
        const char* text = whisper_full_get_segment_text(whisper_ctx, i);
        int64_t t0 = whisper_full_get_segment_t0(whisper_ctx, i);
        int64_t t1 = whisper_full_get_segment_t1(whisper_ctx, i);

        // Convert to milliseconds (t0/t1 are in centiseconds)
        double startMs = t0 * 10.0;
        double endMs = t1 * 10.0;

        if (i > 0) jsonResult += ",";

        // Escape text for JSON
        std::string escapedText;
        if (text) {
            for (const char* p = text; *p; p++) {
                switch (*p) {
                    case '"': escapedText += "\\\""; break;
                    case '\\': escapedText += "\\\\"; break;
                    case '\n': escapedText += "\\n"; break;
                    case '\r': escapedText += "\\r"; break;
                    case '\t': escapedText += "\\t"; break;
                    default: escapedText += *p;
                }
            }
        }

        jsonResult += "{";
        jsonResult += "\"text\":\"" + escapedText + "\",";
        jsonResult += "\"start\":" + std::to_string(startMs) + ",";
        jsonResult += "\"end\":" + std::to_string(endMs);
        jsonResult += "}";
    }

    jsonResult += "]";

    // Allocate and return a copy (caller must free)
    char* result_str = new char[jsonResult.size() + 1];
    std::strcpy(result_str, jsonResult.c_str());
    return result_str;
}

WHISPER_API void whisper_wrapper_free_string(const char* str) {
    if (str != nullptr) {
        delete[] str;
    }
}

WHISPER_API const char* whisper_wrapper_get_system_info(void) {
    return whisper_print_system_info();
}

WHISPER_API int whisper_wrapper_is_multilingual(void* ctx) {
    if (ctx == nullptr) return 0;
    return whisper_is_multilingual(static_cast<whisper_context*>(ctx)) ? 1 : 0;
}

WHISPER_API const char* whisper_wrapper_get_last_error(void) {
    std::lock_guard<std::mutex> lock(g_error_mutex);
    return g_last_error.c_str();
}

} // extern "C"
