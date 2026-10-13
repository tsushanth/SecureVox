# lang-bench: SecureVox's own Whisper models, per language

Measures the exact ggml models the app downloads (tiny, base, small; hashes checked against `WhisperModel.kt`)
on FLEURS read speech, with the app's decoding settings. Mac only, nothing here touches a phone.

Settings matched to `android/app/src/main/cpp/whisper_jni.cpp`: greedy sampling, `translate=false`, language fixed
to the code, 4 threads, `no_context`, default temperature fallback. The vendored `examples/main` defaults to beam
search, so run.py passes `-bs 1`. Build: cmake the vendored `whisper.cpp` (CPU only, `-DGGML_METAL=OFF`), target `main`.

    uv venv .venv && uv pip install jiwer datasets soundfile numpy opencc-python-reimplemented
    LB_DATA=/scratch/data python fetch.py                 # 25 utterances (4-25 s) per language
    WHISPER_MAIN=/scratch/build/bin/main LB_MODELS=/scratch/models LB_DATA=/scratch/data python run.py
    python score.py results_raw.json .                    # writes results.csv, auto_detect.csv

Keep audio and models out of git (about 120 MB audio, 680 MB models). Scoring follows ClearVoice
`tools/asr-bench/ml_score.py`: NFKC, lowercase, punctuation to space; WER, but CER for zh/ja/ko. Intervals are a
95% percentile bootstrap over utterances. `results_raw.json` holds every hypothesis. Results and reading: `results.md`.
