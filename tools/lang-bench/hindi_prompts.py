"""Hindi prompt experiment: Tiny/Base/Small on the 25 FLEURS hi_in clips with no prompt, a Devanagari prompt and a
Roman-letter prompt, using the app's decoder settings (greedy, 4 threads, language hi).

Env: WHISPER_MAIN (built `main`), LB_MODELS (ggml-*.bin), LB_DATA (hi.json from fetch.py hi).
Usage: python hindi_prompts.py tiny,base,small   -> hindi_prompts_raw.json (every hypothesis)
Scored with score-style normalisation by hindi_phonetic_cer.py (CER on romanised, phonetically folded text) and
WER with the same NFKC / punctuation-to-space normaliser as score.py."""
import json, os, re, subprocess, sys, unicodedata, jiwer
MAIN = os.environ["WHISPER_MAIN"]
MODELS = os.environ["LB_MODELS"]
DATA = os.environ.get("LB_DATA", "data")
items = json.load(open(f"{DATA}/hi.json"))
DEV = "नमस्ते, आप कैसे हैं? मैं ठीक हूँ। यह एक हिंदी वाक्य है।"
ROM = "Namaste, aap kaise hain? Main theek hoon. Yeh ek Hindi vaakya hai."
VARIANTS = {"plain": None, "devprompt": DEV, "romprompt": ROM}
def norm(s):
    s = unicodedata.normalize("NFKC", s).lower()
    s = "".join(" " if unicodedata.category(c)[0] in "PS" else c for c in s)
    return re.sub(r"\s+", " ", s).strip()
out = {}
for model in sys.argv[1].split(","):
    for vn, pr in VARIANTS.items():
        key = f"{model}/{vn}"
        rows = []
        for it in items:
            cmd = [MAIN, "-m", f"{MODELS}/ggml-{model}.bin", "-f", it["wav"], "-l", "hi", "-t", "4", "-bs", "1", "-nt"]
            if pr: cmd += ["--prompt", pr]
            p = subprocess.run(cmd, capture_output=True)
            rows.append({"ref": it["ref"], "hyp": " ".join(p.stdout.decode("utf-8", "replace").split())})
        wer = jiwer.wer([norm(r["ref"]) for r in rows], [norm(r["hyp"]) for r in rows]) * 100
        out[key] = {"wer": round(wer, 1), "rows": rows}
        print(key, round(wer, 1), flush=True)
        json.dump(out, open("hindi_prompts_raw.json", "w"), ensure_ascii=False)
