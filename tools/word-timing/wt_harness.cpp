// Mac harness: runs whisper.cpp with the SAME parameters and the SAME JSON builder as the Android
// JNI bridge (android/app/src/main/cpp/whisper_jni.cpp + whisper_json.h) and prints the JSON.
//
//   wt_harness <model.bin> <audio.wav 16 kHz mono 16-bit> [--no-tokens] [--dtw tiny|base|small]
//
// --no-tokens  : token_timestamps off (used to prove token timing does not change the text)
// --dtw <name> : EXPERIMENT. Loads the context with DTW token timestamps (not used by the app).
#include <algorithm>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <iterator>
#include <string>
#include <thread>
#include <vector>
#include "whisper.h"
#include "whisper_json.h"

static bool read_wav16(const char* path, std::vector<float>& out) {
    std::ifstream f(path, std::ios::binary);
    if (!f) return false;
    std::vector<char> b((std::istreambuf_iterator<char>(f)), std::istreambuf_iterator<char>());
    if (b.size() < 44 || memcmp(b.data(), "RIFF", 4) != 0) return false;
    size_t pos = 12;
    while (pos + 8 <= b.size()) {
        uint32_t sz;
        memcpy(&sz, &b[pos + 4], 4);
        if (memcmp(&b[pos], "data", 4) == 0) {
            size_t n = std::min<size_t>(sz, b.size() - pos - 8) / 2;
            out.resize(n);
            const int16_t* s = reinterpret_cast<const int16_t*>(&b[pos + 8]);
            for (size_t i = 0; i < n; i++) out[i] = s[i] / 32768.0f;
            return true;
        }
        pos += 8 + sz + (sz & 1);
    }
    return false;
}

int main(int argc, char** argv) {
    if (argc < 3) {
        fprintf(stderr, "usage: %s model wav [--no-tokens] [--dtw tiny|base|small]\n", argv[0]);
        return 2;
    }
    bool tokens = true;
    std::string dtw;
    for (int i = 3; i < argc; i++) {
        if (!strcmp(argv[i], "--no-tokens")) tokens = false;
        else if (!strcmp(argv[i], "--dtw") && i + 1 < argc) dtw = argv[++i];
    }
    std::vector<float> audio;
    if (!read_wav16(argv[2], audio)) { fprintf(stderr, "cannot read wav\n"); return 1; }

    whisper_context_params cp = whisper_context_default_params();
    cp.use_gpu = false;  // same as the app: CPU only
    cp.flash_attn = false;
    if (!dtw.empty()) {
        cp.dtw_token_timestamps = true;
        cp.dtw_aheads_preset = dtw == "tiny" ? WHISPER_AHEADS_TINY
                             : dtw == "base" ? WHISPER_AHEADS_BASE
                             : dtw == "small" ? WHISPER_AHEADS_SMALL : WHISPER_AHEADS_NONE;
    }
    whisper_context* ctx = whisper_init_from_file_with_params(argv[1], cp);
    if (!ctx) { fprintf(stderr, "model load failed\n"); return 1; }

    // Keep in sync with whisper_jni.cpp (non-fast path).
    whisper_full_params p = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    p.print_realtime = false;
    p.print_progress = false;
    p.print_timestamps = true;
    p.print_special = false;
    p.translate = false;
    p.language = "en";
    p.n_threads = std::min(4, (int) std::thread::hardware_concurrency());
    p.offset_ms = 0;
    p.no_context = true;
    p.single_segment = false;
    p.token_timestamps = tokens;

    if (whisper_full(ctx, p, audio.data(), (int) audio.size()) != 0) {
        fprintf(stderr, "whisper_full failed\n");
        return 1;
    }
    printf("%s\n", wjson::segments_to_json(ctx, p.token_timestamps, !dtw.empty()).c_str());
    whisper_free(ctx);
    return 0;
}
