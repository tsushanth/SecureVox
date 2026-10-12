"""Why does the count go wrong, and would dropping tiny clusters help?

For multi-speaker scenarios, run the app configuration once per mix, print the speech duration of
every hypothesis cluster, and re-score after discarding clusters whose total speech is below a
minimum (absolute seconds and/or share of all speech). Dropped spans become gaps, so
assign_speakers falls back to the nearest remaining span, exactly as the app would.

usage: python analyze_splits.py [--n 8] [--workers 8] [--th 0.85] [--out splits.csv]
"""
import argparse
import csv
from concurrent.futures import ProcessPoolExecutor

import numpy as np

import run_bench as rb
import sbench as sb

SCEN = ["a_2spk_clean", "a_3spk_clean", "b_4spk_clean", "b_5spk_clean", "b_6spk_clean",
        "c_pink_snr10_2spk", "c_pink_snr5_3spk", "c_white_snr10_3spk", "d_short_turns_2spk",
        "e_overlap20_2spk", "e_overlap50_2spk", "f_same_gender_2spk", "f_mixed_gender_2spk",
        "h_single_speaker", "h_single_speaker_short"]
# (min total seconds, min share of speech); a cluster is dropped if below EITHER limit
RULES = [(0, 0), (3, 0), (5, 0), (8, 0), (0, 0.05), (0, 0.10), (5, 0.05), (8, 0.10)]
_c = None


def drop_small(spans, min_s, min_frac):
    dur = {}
    for a, b, s in spans:
        dur[s] = dur.get(s, 0) + (b - a)
    tot = sum(dur.values()) or 1
    keep = {s for s, d in dur.items() if d >= min_s * 1000 and d / tot >= min_frac}
    if not keep:  # never drop everything
        keep = {max(dur, key=dur.get)}
    return [x for x in spans if x[2] in keep]


def work(task):
    global _c
    name, idx, th = task
    _c = _c or sb.Corpus()
    seed = sum(map(ord, name)) * 1000 + idx  # same mixes as run_bench
    mix = rb.scenarios()[name](_c, np.random.default_rng(seed))
    total = len(mix.audio) / sb.SR
    spans = sb.diarize(mix.audio, th)
    segs = sb.pseudo_segments(mix.turns, np.random.default_rng(seed + 7), "gt_jitter")
    truth = [sb.truth_label(s, mix.turns) for s in segs]
    dur = {}
    for a, b, s in spans:
        dur[s] = dur.get(s, 0) + (b - a) / 1000
    out = []
    for ms, mf in RULES:
        sp = drop_small(spans, ms, mf)
        d, miss, fa, conf, k = sb.der(mix.turns, sp, total)
        out.append(dict(scenario=name, mix=idx, th=th, min_s=ms, min_frac=mf, true_spk=mix.n_spk,
                        est_spk=k, count_ok=int(k == mix.n_spk), der=round(d, 4),
                        seg_acc=round(sb.seg_accuracy(sb.assign_speakers(segs, sp), truth), 4),
                        clusters_s=" ".join(f"{v:.1f}" for v in sorted(dur.values(), reverse=True))))
    return out


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--n", type=int, default=8)
    ap.add_argument("--workers", type=int, default=8)
    ap.add_argument("--th", type=float, default=0.85)
    ap.add_argument("--out", default="splits.csv")
    a = ap.parse_args()
    tasks = [(s, i, a.th) for s in SCEN for i in range(a.n)]
    rows = []
    with ProcessPoolExecutor(a.workers) as ex:
        for r in ex.map(work, tasks, chunksize=1):
            rows += r
    with open(a.out, "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=list(rows[0])); w.writeheader(); w.writerows(rows)
    print("cluster speech seconds (largest first) for runs with a wrong count, rule 0/0:")
    for r in rows:
        if r["min_s"] == 0 and r["min_frac"] == 0 and not r["count_ok"]:
            print(f"  {r['scenario']:22s} true {r['true_spk']} est {r['est_spk']}  [{r['clusters_s']}]")
    print("\nrule (min s, min share): right count / mean seg acc (multi-speaker) | single-speaker false splits")
    for ms, mf in RULES:
        sel = [r for r in rows if r["min_s"] == ms and r["min_frac"] == mf]
        multi = [r for r in sel if not r["scenario"].startswith("h_")]
        single = [r for r in sel if r["scenario"].startswith("h_")]
        print(f"  ({ms:>2}, {mf:.2f}): {sum(r['count_ok'] for r in multi)}/{len(multi)}  "
              f"seg {100*np.mean([r['seg_acc'] for r in multi]):.1f}  DER {100*np.mean([r['der'] for r in multi]):.1f}"
              f" | single {sum(not r['count_ok'] for r in single)}/{len(single)}")
