# speaker-bench

Measures the on-device speaker labels of the SecureVox Android app (sherpa-onnx pyannote
segmentation 3.0 + NeMo TitaNet-small, fast clustering) outside the phone, on the Mac. Nothing
here ships in the app. Method follows ClearVoice's `tools/asr-bench/diar_fetch.py` / `diar_eval.py`.

## Rules of the benchmark

- Only real human speech (LibriSpeech test-clean). Synthetic `say` voices merge into one speaker
  and are not valid test speakers.
- Same sherpa-onnx version as the app's AAR (1.13.8), same models (size and SHA-256 pinned, see
  `fetch_models.py` and `ModelDownloader.kt`), same parameters as `SpeakerDiarizer.kt`
  (threshold 0.85, min duration on 0.3 s / off 0.5 s). Mac runs use 1 thread per worker.
- Scoring: frame-level (10 ms) diarization error rate, no collar, Hungarian matching of
  hypothesis to reference speakers (miss + false alarm + confusion over reference speaker-frames).
- App-level scoring: pseudo-segments are cut out of each mix and labelled with a faithful Python
  port of `assignSpeakers` (dominant overlap, nearest-span fallback, renumber by first appearance).
  "Oracle" feeds the ground-truth turns through the same rule, so it isolates the loss caused by
  segment boundaries alone. Accuracy uses the best one-to-one label mapping.

## Files

| file | purpose |
|---|---|
| `sbench.py` | corpus, mix builder, noise, diarizer wrapper, DER, `assign_speakers` port |
| `run_bench.py` | all scenarios x configs -> `results.csv` |
| `long_run.py` | ~30 min concatenation: runtime and peak RSS (run once per measurement) |
| `summarize.py` | `results.csv` -> markdown tables |
| `fetch_models.py` | download + verify the two ONNX models (resumable) |

## Run

```
python3 -m venv venv && venv/bin/pip install sherpa-onnx==1.13.8 scipy soundfile numpy datasets
venv/bin/python fetch_models.py                 # -> ./models
# speech: ClearVoice's tools/asr-bench/diar_fetch.py produces diar_utts.pkl (dict speaker -> list of
# float32 16 kHz utterances); put it at ./data/diar_utts.pkl or set SB_PKL / SB_MODELS
venv/bin/python run_bench.py --n 12 --workers 4 --sweep --out results.csv
venv/bin/python summarize.py results.csv
venv/bin/python long_run.py --minutes 30 --speakers 4 --threads 2
```

`models/` and `data/` are git-ignored. Gender for the same-gender/mixed scenarios is estimated
from median F0 (>165 Hz = female) because no speaker metadata was available offline.

See `results.md` for numbers and the reading.
