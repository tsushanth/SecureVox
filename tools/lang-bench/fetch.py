"""Fetch 25 FLEURS test utterances (4-25 s) per language into $LB_DATA (default ./data; keep OUT of git).

Same selection method as ClearVoice tools/asr-bench/ml_fetch.py: the first 25 test rows with
4 <= duration <= 25 s, audio resampled to 16 kHz mono 16-bit WAV.

Usage: python fetch.py [lang ...]
"""
import io, json, os, sys
import numpy as np, soundfile as sf
from datasets import load_dataset, Audio

LANGS = {"en": "en_us", "es": "es_419", "fr": "fr_fr", "de": "de_de", "it": "it_it", "pt": "pt_br",
         "nl": "nl_nl", "pl": "pl_pl", "ru": "ru_ru", "zh": "cmn_hans_cn", "ja": "ja_jp", "ko": "ko_kr",
         "ar": "ar_eg", "hi": "hi_in"}
N = 25
DATA = os.environ.get("LB_DATA", "data")
os.makedirs(DATA, exist_ok=True)
for code in (sys.argv[1:] or LANGS):
    out = f"{DATA}/{code}.json"
    if os.path.exists(out):
        continue
    rows = []
    try:
        ds = load_dataset("google/fleurs", LANGS[code], split="test", streaming=True).cast_column("audio", Audio(decode=False))
        for r in ds:
            y, sr = sf.read(io.BytesIO(r["audio"]["bytes"]), dtype="float32")
            if y.ndim > 1:
                y = y.mean(axis=1)
            if sr != 16000:
                n = int(len(y) * 16000 / sr)
                y = np.interp(np.linspace(0, len(y) - 1, n), np.arange(len(y)), y).astype("float32")
            d = len(y) / 16000
            if not (4 <= d <= 25):
                continue
            path = f"{DATA}/{code}_{len(rows):03d}.wav"
            sf.write(path, y, 16000, subtype="PCM_16")
            rows.append({"wav": path, "ref": r["raw_transcription"], "dur": d, "lang": code})
            if len(rows) >= N:
                break
    except Exception as e:
        print(code, "FAILED", type(e).__name__, str(e)[:150], flush=True)
        continue
    json.dump(rows, open(out, "w"), ensure_ascii=False)
    print(code, len(rows), "utterances", round(sum(x["dur"] for x in rows)), "s", flush=True)
