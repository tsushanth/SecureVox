// Turns the result of one whisper_full() run into the JSON the Kotlin side parses.
//
// Header-only so the Android JNI bridge (whisper_jni.cpp) and the Mac harness in
// tools/word-timing/ run exactly the same code.
//
// Shape: [{"text":..., "start":ms, "end":ms, "tokens":[["tok", startMs, endMs], ...]}, ...]
// "tokens" is only present when token timestamps were requested. Tokens are the raw model tokens
// (leading space = word start); Kotlin groups them into words (whisper/WordTimes.kt), so that
// logic is unit tested on the JVM.
#pragma once

#include <cstdint>
#include <cstdio>
#include <string>
#include <vector>
#include "whisper.h"

namespace wjson {

// Escapes for a JSON string. Control characters other than \n \r \t become \u00XX so the
// Kotlin parser never sees a raw control byte.
inline void append_escaped(std::string& out, const std::string& s) {
    for (unsigned char c : s) {
        switch (c) {
            case '"': out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\n': out += "\\n"; break;
            case '\r': out += "\\r"; break;
            case '\t': out += "\\t"; break;
            default:
                if (c < 0x20) {
                    char buf[8];
                    snprintf(buf, sizeof(buf), "\\u%04x", c);
                    out += buf;
                } else {
                    out += (char) c;
                }
        }
    }
}

// Result of looking at the end of a byte string as UTF-8.
enum class Utf8 { Valid, Incomplete, Invalid };

// JNI NewStringUTF takes "modified UTF-8", which has no 4-byte sequences, so anything outside
// the basic plane (emoji) counts as Invalid here and the token is dropped from the token list.
inline Utf8 check_utf8(const std::string& s) {
    size_t i = 0, n = s.size();
    while (i < n) {
        unsigned char c = s[i];
        size_t len;
        if (c < 0x80) len = 1;
        else if ((c & 0xE0) == 0xC0 && c >= 0xC2) len = 2;
        else if ((c & 0xF0) == 0xE0) len = 3;
        else return Utf8::Invalid;
        if (i + len > n) {
            // Only a truncated sequence at the very end can still be completed by the next token.
            for (size_t k = i + 1; k < n; k++) {
                if (((unsigned char) s[k] & 0xC0) != 0x80) return Utf8::Invalid;
            }
            return Utf8::Incomplete;
        }
        for (size_t k = 1; k < len; k++) {
            if (((unsigned char) s[i + k] & 0xC0) != 0x80) return Utf8::Invalid;
        }
        i += len;
    }
    return Utf8::Valid;
}

inline long long cs_to_ms(int64_t cs) { return cs < 0 ? -1 : (long long) cs * 10; }

struct Tok {
    std::string text;
    long long start;
    long long end;
    long long dtw;  // DTW token time in ms, -1 when not computed
};

// Appends the "tokens" array for segment i. Special tokens (text markers and timestamp tokens)
// are skipped. Byte-level tokens that split a multi-byte character are glued back together first.
// useDtw (harness experiments only, the app never sets it) takes token starts from the DTW
// alignment and ends each token where the next begins.
inline void append_tokens(std::string& out, whisper_context* ctx, int i, bool useDtw) {
    const int n = whisper_full_n_tokens(ctx, i);
    const whisper_token eot = whisper_token_eot(ctx);
    std::vector<Tok> toks;
    std::string pending;
    long long pendingStart = -1, pendingDtw = -1;
    for (int j = 0; j < n; j++) {
        const whisper_token_data d = whisper_full_get_token_data(ctx, i, j);
        if (d.id >= eot) continue;
        const char* t = whisper_full_get_token_text(ctx, i, j);
        if (t == nullptr) continue;
        if (pending.empty()) {
            pendingStart = cs_to_ms(d.t0);
            pendingDtw = cs_to_ms(d.t_dtw);
        }
        pending += t;
        Utf8 state = check_utf8(pending);
        if (state == Utf8::Incomplete) continue;  // wait for the rest of the character
        if (state == Utf8::Valid && !pending.empty()) {
            toks.push_back({pending, pendingStart, cs_to_ms(d.t1), pendingDtw});
        }
        pending.clear();
    }
    if (useDtw) {
        const long long segEnd = (long long) whisper_full_get_segment_t1(ctx, i) * 10;
        for (size_t k = 0; k < toks.size(); k++) {
            if (toks[k].dtw >= 0) toks[k].start = toks[k].dtw;
            toks[k].end = (k + 1 < toks.size() && toks[k + 1].dtw >= 0) ? toks[k + 1].dtw : segEnd;
        }
    }
    out += "\"tokens\":[";
    for (size_t k = 0; k < toks.size(); k++) {
        if (k > 0) out += ",";
        out += "[\"";
        append_escaped(out, toks[k].text);
        out += "\",";
        out += std::to_string(toks[k].start);
        out += ",";
        out += std::to_string(toks[k].end);
        out += "]";
    }
    out += "]";
}

// withTokens=false produces exactly the output the app had before word timing existed.
inline std::string segments_to_json(whisper_context* ctx, bool withTokens, bool useDtw = false) {
    std::string json = "[";
    const int numSegments = whisper_full_n_segments(ctx);
    for (int i = 0; i < numSegments; i++) {
        const char* text = whisper_full_get_segment_text(ctx, i);
        const double startMs = whisper_full_get_segment_t0(ctx, i) * 10.0;
        const double endMs = whisper_full_get_segment_t1(ctx, i) * 10.0;

        if (i > 0) json += ",";
        json += "{\"text\":\"";
        append_escaped(json, text ? text : "");
        json += "\",\"start\":" + std::to_string(startMs);
        json += ",\"end\":" + std::to_string(endMs);
        if (withTokens) {
            json += ",";
            append_tokens(json, ctx, i, useDtw);
        }
        json += "}";
    }
    json += "]";
    return json;
}

}  // namespace wjson
