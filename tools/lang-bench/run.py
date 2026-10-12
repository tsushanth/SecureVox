"""Run SecureVox's own ggml models through whisper.cpp's CLI with the app's parameters.

App parameters (android/app/src/main/cpp/whisper_jni.cpp): WHISPER_SAMPLING_GREEDY, translate=false,
language fixed to the code, n_threads=4, no_context=true, default temperature fallback (temperature_inc 0.2,
entropy 2.4, logprob -1.0). The vendored examples/main defaults to BEAM search (beam 5), so `-bs 1` forces greedy.

Env: WHISPER_MAIN (path to the built `main`), LB_MODELS (dir with ggml-*.bin), LB_DATA (from fetch.py),
     LB_OUT (default results_raw.json), MODELS (default "tiny base small"), AUTO_N (utterances per language
     used for the language-detection check, default 10; 0 to skip).
Resumable: finished (model, lang) cells are skipped.
"""
import json, os, re, subprocess, sys, time

MAIN = os.environ["WHISPER_MAIN"]
MODELS_DIR = os.environ["LB_MODELS"]
DATA = os.environ.get("LB_DATA", "data")
OUT = os.environ.get("LB_OUT", "results_raw.json")
MODELS = os.environ.get("MODELS", "tiny base small").split()
AUTO_N = int(os.environ.get("AUTO_N", "10"))
LANGS = sys.argv[1:] or ["en", "es", "fr", "de", "it", "pt", "nl", "pl", "ru", "zh", "ja", "ko", "ar", "hi"]

res = json.load(open(OUT)) if os.path.exists(OUT) else {}


def transcribe(model, wav, lang):
    cmd = [MAIN, "-m", f"{MODELS_DIR}/ggml-{model}.bin", "-f", wav, "-l", lang, "-t", "4", "-bs", "1", "-nt"]
    p = subprocess.run(cmd, capture_output=True)
    # CJK tokens can be split mid-character by the CLI's token-by-token printing; keep going, count it as an error
    p.stdout = p.stdout.decode("utf-8", errors="replace")
    err = p.stderr.decode("utf-8", errors="replace")
    m_total = re.search(r"total time =\s*([\d.]+) ms", err)
    m_load = re.search(r"load time =\s*([\d.]+) ms", err)
    m_det = re.search(r"auto-detected language: (\w+)", err)
    # inference time = total minus one-off model load, so RTF is not inflated by per-file process start
    sec = (float(m_total.group(1)) - float(m_load.group(1))) / 1000 if m_total and m_load else None
    return " ".join(p.stdout.split()), sec, (m_det.group(1) if m_det else None), p.returncode


for model in MODELS:
    for lang in LANGS:
        key = f"{model}/{lang}"
        if key in res:
            continue
        items = json.load(open(f"{DATA}/{lang}.json"))
        rows = []
        for i, it in enumerate(items):
            hyp, sec, _, rc = transcribe(model, it["wav"], lang)
            row = {"wav": it["wav"], "ref": it["ref"], "dur": it["dur"], "hyp": hyp, "sec": sec, "rc": rc}
            if i < AUTO_N:
                ahyp, asec, det, _ = transcribe(model, it["wav"], "auto")
                row.update({"auto_hyp": ahyp, "auto_lang": det})
            rows.append(row)
        res[key] = rows
        json.dump(res, open(OUT, "w"), ensure_ascii=False)
        tot = sum(r["dur"] for r in rows)
        print(key, "RTF", round(sum(r["sec"] or 0 for r in rows) / tot, 3), flush=True)
