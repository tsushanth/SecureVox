# Word timing harness

Mac tools to look at the word timings the app produces, without a phone.

- `wt_harness.cpp` runs whisper.cpp with the same parameters as `whisper_jni.cpp` (greedy, `no_context`,
  `token_timestamps`) and builds its JSON with the same header the app uses (`android/app/src/main/cpp/whisper_json.h`).
- `word_timing_check.py` groups the tokens into words (a Python mirror of `groupTokensIntoWords` for English),
  checks them, and prints what the data looks like.
- `synthetic_truth.py` measures timing error against KNOWN word boundaries (macOS `say` + ffmpeg).
- `migration_dryrun.sh` dry-runs the Room 3 -> 4 migration on a v3-shaped SQLite file.

## Run

```sh
# 1. build (CPU only). WHISPER_CPP_DIR is the git-ignored vendored copy.
cmake -S tools/word-timing -B /tmp/wtbuild -DCMAKE_BUILD_TYPE=Release \
      -DWHISPER_CPP_DIR=$PWD/android/app/src/main/cpp/whisper.cpp
cmake --build /tmp/wtbuild -j8

# 2. real speech: LibriSpeech test-clean parquet (HuggingFace openslr/librispeech_asr, "all/test.clean")
python3 tools/word-timing/word_timing_check.py --harness /tmp/wtbuild/wt_harness \
    --model ggml-small.bin --parquet <.../test.clean/0000.parquet> --clips 4 --dtw small

# 3. known-truth check
python3 tools/word-timing/synthetic_truth.py --harness /tmp/wtbuild/wt_harness --model ggml-tiny.bin --dtw tiny
```

Models and audio are never committed; clips are decoded into a temp dir and removed.

## What it validates per clip

Words rebuild the segment text; starts are monotonic; words stay inside their segment; overlaps; zero-length
words; coverage (share of the segment covered by word spans); token timestamps do not change the text
(run with and without `token_timestamps`); first word start / last word end vs an energy-based speech
onset/offset; and, with `--dtw`, how far DTW starts are from the heuristic ones.

## Observed (2026-10-12, Mac CPU, models identical to the app's tiny and small, English)

LibriSpeech test-clean, 4 clips (small) and 5 clips (tiny), 6-25 s each:

- Structure is clean: 0 non-monotonic starts, 0 words outside their segment, 0 overlaps, text identical with and
  without token timestamps (9/9 clips). Zero-length words: 0-1 per clip (punctuation-only or very short words).
  Coverage of segment time by words: 88-96%.
- Last word ends 60-610 ms before the energy-based speech end (the heuristic end of the last word is early).

Synthetic clip with known boundaries (46 words, isolated TTS words, 120 ms gaps; easier than fluent speech in
some ways, harder in others, so a rough guide only):

| model | start bias | median abs error | within 100 ms | within 250 ms | p90 abs error |
|-------|-----------|------------------|---------------|---------------|---------------|
| small | +11 ms    | 24 ms            | 73%           | 89%           | 254 ms        |
| tiny  | -2 ms     | 68 ms            | 53%           | 67%           | 440 ms        |

Take-aways: small is good enough to follow along and tap-to-seek; tiny's word times are noticeably looser (a third
of words more than 250 ms off in this test), so the app seeks 120 ms before the tapped word.

DTW (`dtw_token_timestamps`, alignment-heads presets) was tried and NOT used: with `t_dtw` as the token start it
ran 250 ms (small) to 480 ms (tiny) late on the synthetic clip, it has to be switched on when the context is
created (so it would also slow live captions' context), and it needs extra memory. It may be fixable with a
constant shift, but that is a separate experiment.

Not measured here: speed and memory on the phone, long recordings, non-English, noisy or overlapping speech.
