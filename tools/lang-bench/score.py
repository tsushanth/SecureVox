"""Score results_raw.json from run.py. Writes results.csv and auto_detect.csv, prints markdown tables.

Normalization is ClearVoice tools/asr-bench/ml_score.py: NFKC, lowercase, punctuation/symbols -> space,
collapse whitespace; zh/ja/ko are scored as CER on the string with spaces removed, everything else as WER.
Chinese is also scored after Traditional->Simplified conversion (zh*) because Whisper often writes Traditional
while FLEURS cmn_hans references are Simplified (needs `pip install opencc-python-reimplemented`; skipped if absent).
Confidence interval: 95% percentile bootstrap over utterances (corpus error rate = sum errors / sum reference units).

Usage: python score.py [results_raw.json] [outdir]
"""
import csv, json, random, re, sys, unicodedata
import jiwer

try:
    from opencc import OpenCC
    _t2s = OpenCC("t2s").convert
except Exception:  # optional
    _t2s = None

RAW = sys.argv[1] if len(sys.argv) > 1 else "results_raw.json"
OUTDIR = sys.argv[2] if len(sys.argv) > 2 else "."
NOSPACE = {"zh", "ja", "ko"}
ORDER = ["en", "es", "fr", "de", "it", "pt", "nl", "pl", "ru", "zh", "ja", "ko", "ar", "hi"]
MODELS = ["tiny", "base", "small"]
B = 2000


def norm(s, nospace):
    s = unicodedata.normalize("NFKC", s).lower()
    s = "".join(" " if unicodedata.category(c)[0] in "PS" else c for c in s)
    s = re.sub(r"\s+", " ", s).strip()
    return s.replace(" ", "") if nospace else s


def utt_errors(ref, hyp, nospace):
    """(errors, reference units) for one utterance."""
    if not ref:
        return None
    if not hyp:
        return len(ref) if nospace else len(ref.split()), len(ref) if nospace else len(ref.split())
    o = (jiwer.process_characters if nospace else jiwer.process_words)(ref, hyp)
    return o.substitutions + o.deletions + o.insertions, sum(len(x) for x in [ref]) if nospace else len(ref.split())


def boot(pairs, seed=0):
    rnd = random.Random(seed)
    n = len(pairs)
    vals = []
    for _ in range(B):
        idx = [rnd.randrange(n) for _ in range(n)]
        e = sum(pairs[i][0] for i in idx)
        t = sum(pairs[i][1] for i in idx)
        vals.append(100 * e / t)
    vals.sort()
    return vals[int(0.025 * B)], vals[int(0.975 * B)]


raw = json.load(open(RAW))
rows_out, table = [], {}
for lang in ORDER:
    ns = lang in NOSPACE
    for model in MODELS:
        cell = raw.get(f"{model}/{lang}")
        if not cell:
            continue
        variants = [("", lambda s: s)] + ([("*", _t2s)] if lang == "zh" and _t2s else [])
        for suffix, conv in variants:
            pairs, per = [], []
            for r in cell:
                ref = norm(r["ref"], ns)
                hyp = norm(conv(r["hyp"]), ns)
                pe = utt_errors(ref, hyp, ns)
                if pe is None:
                    continue
                pairs.append(pe)
                per.append(pe[0] / pe[1])
            err = 100 * sum(e for e, _ in pairs) / sum(t for _, t in pairs)
            lo, hi = boot(pairs)
            per_sorted = sorted(per)
            med = 100 * per_sorted[len(per_sorted) // 2]
            bad = sum(1 for p in per if p >= 0.5)
            dur = sum(r["dur"] for r in cell)
            # median per-utterance RTF: robust to a few utterances stalled by other load on the machine
            rtfs = sorted((r["sec"] or 0) / r["dur"] for r in cell)
            sec = rtfs[len(rtfs) // 2] * dur
            row = dict(model=model, lang=lang + suffix, metric="CER" if ns else "WER", n_utts=len(pairs),
                       ref_units=sum(t for _, t in pairs), error_pct=round(err, 1), ci_lo=round(lo, 1), ci_hi=round(hi, 1),
                       median_utt_error_pct=round(med, 1), utts_ge_50pct=bad, audio_s=round(dur), rtf_mac_4t=round(sec / dur, 3))
            rows_out.append(row)
            table[(model, lang + suffix)] = row

with open(f"{OUTDIR}/results.csv", "w", newline="") as f:
    w = csv.DictWriter(f, fieldnames=list(rows_out[0].keys()))
    w.writeheader()
    w.writerows(rows_out)

# language auto-detection
auto = []
for lang in ORDER:
    for model in MODELS:
        cell = raw.get(f"{model}/{lang}")
        if not cell:
            continue
        sub = [r for r in cell if "auto_lang" in r]
        if not sub:
            continue
        ok = sum(1 for r in sub if r["auto_lang"] == lang)
        wrong = sorted({r["auto_lang"] or "none" for r in sub if r["auto_lang"] != lang})
        auto.append(dict(model=model, lang=lang, n=len(sub), correct=ok, wrong_as=" ".join(wrong)))
with open(f"{OUTDIR}/auto_detect.csv", "w", newline="") as f:
    w = csv.DictWriter(f, fieldnames=["model", "lang", "n", "correct", "wrong_as"])
    w.writeheader()
    w.writerows(auto)

print("| lang | metric | tiny | base | small | n utts | ref units | RTF tiny/base/small |")
print("|---|---|---|---|---|---|---|---|")
for lang in ORDER + (["zh*"] if _t2s else []):
    cells = [table.get((m, lang)) for m in MODELS]
    if not any(cells):
        continue
    def c(r):
        return "-" if not r else f"{r['error_pct']:.1f} [{r['ci_lo']:.0f}-{r['ci_hi']:.0f}]"
    first = next(r for r in cells if r)
    rtf = "/".join("-" if not r else f"{r['rtf_mac_4t']:.2f}" for r in cells)
    print(f"| {lang} | {first['metric']} | " + " | ".join(c(r) for r in cells) + f" | {first['n_utts']} | {first['ref_units']} | {rtf} |")
print()
print("| lang | " + " | ".join(MODELS) + " |")
print("|---|---|---|---|")
for lang in ORDER:
    cells = {a["model"]: a for a in auto if a["lang"] == lang}
    if cells:
        print(f"| {lang} | " + " | ".join(f"{cells[m]['correct']}/{cells[m]['n']}" + (f" (as {cells[m]['wrong_as']})" if cells[m]["wrong_as"] else "") if m in cells else "-" for m in MODELS) + " |")
