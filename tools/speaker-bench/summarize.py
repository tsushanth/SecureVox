"""Aggregate results.csv into markdown tables (printed to stdout; paste into results.md).

usage: python summarize.py results.csv
"""
import csv
import sys
from collections import defaultdict

import numpy as np

rows = list(csv.DictReader(open(sys.argv[1] if len(sys.argv) > 1 else "results.csv")))
for r in rows:
    for k, v in r.items():
        if k != "scenario":
            r[k] = float(v)


def agg(rs):
    n = len(rs)
    exact = sum(r["count_ok"] for r in rs)
    over = sum(r["est_spk"] > r["true_spk"] for r in rs)
    under = sum(r["est_spk"] < r["true_spk"] for r in rs)
    m = lambda k: 100 * np.mean([r[k] for r in rs])
    return dict(n=n, exact=exact, over=over, under=under, der=m("der"), miss=m("miss"), fa=m("fa"),
                conf=m("conf"), s_gt=m("seg_gt_jitter_app"), s_gt_o=m("seg_gt_jitter_oracle"),
                s_sub=m("seg_sub_jitter_app"), s_sub_o=m("seg_sub_jitter_oracle"),
                s_cut=m("seg_sub_cuts_app"), proc=np.mean([r["proc_s"] / r["dur_s"] for r in rs]))


def table(title, sel):
    print(f"\n### {title}\n")
    print("| scenario | mixes | right count | over / under | DER % (miss / FA / conf) | seg acc, GT+-0.5s: app (oracle) | seg acc, sub-cuts+-0.5s: app (oracle) |")
    print("|---|---|---|---|---|---|---|")
    groups = defaultdict(list)
    for r in rows:
        if sel(r):
            groups[r["scenario"]].append(r)
    for name in sorted(groups):
        a = agg(groups[name])
        print(f"| {name} | {a['n']} | {a['exact']}/{a['n']} | {a['over']} / {a['under']} | "
              f"{a['der']:.1f} ({a['miss']:.1f} / {a['fa']:.1f} / {a['conf']:.1f}) | "
              f"{a['s_gt']:.1f} ({a['s_gt_o']:.1f}) | {a['s_sub']:.1f} ({a['s_sub_o']:.1f}) |")


APP = lambda r: (r["th"], r["on"], r["off"]) == (0.85, 0.3, 0.5)
table("App configuration (threshold 0.85, min on 0.3 s, min off 0.5 s)", APP)

print("\n### Threshold sweep (min on 0.3 / off 0.5): right-count / mean DER % / seg acc GT+-0.5 s (app)\n")
ths = sorted({r["th"] for r in rows if (r["on"], r["off"]) == (0.3, 0.5)})
core = sorted({r["scenario"] for r in rows if not APP(r)})
print("| scenario | " + " | ".join(f"{t:g}" for t in ths) + " |")
print("|---|" + "---|" * len(ths))
for s in core:
    cells = []
    for t in ths:
        rs = [r for r in rows if r["scenario"] == s and r["th"] == t and (r["on"], r["off"]) == (0.3, 0.5)]
        a = agg(rs) if rs else None
        cells.append(f"{a['exact']}/{a['n']} / {a['der']:.1f} / {a['s_gt']:.0f}" if a else "-")
    print(f"| {s} | " + " | ".join(cells) + " |")

print("\n### Min-duration sweep (threshold 0.85): right-count / mean DER % / seg acc GT+-0.5 s (app)\n")
combos = sorted({(r["on"], r["off"]) for r in rows if r["th"] == 0.85})
print("| scenario | " + " | ".join(f"on {a:g} / off {b:g}" for a, b in combos) + " |")
print("|---|" + "---|" * len(combos))
for s in core:
    cells = []
    for on, off in combos:
        rs = [r for r in rows if r["scenario"] == s and r["th"] == 0.85 and (r["on"], r["off"]) == (on, off)]
        a = agg(rs) if rs else None
        cells.append(f"{a['exact']}/{a['n']} / {a['der']:.1f} / {a['s_gt']:.0f}" if a else "-")
    print(f"| {s} | " + " | ".join(cells) + " |")

print("\n### Pooled over the core scenarios that have a true speaker count > 1 (excludes single speaker)\n")
print("| threshold | right count | over | under | mean DER % | seg acc GT+-0.5 s |")
print("|---|---|---|---|---|---|")
pool_ths = [t for t in ths if t >= 0.8]  # scenarios swept at 0.8-0.95 (plus 0.85 from the app run)
multi = [s for s in core if not s.startswith("h_")]
multi = [s for s in multi if all(any(r["scenario"] == s and r["th"] == t and (r["on"], r["off"]) == (0.3, 0.5)
                                    for r in rows) for t in pool_ths)]
print("scenarios pooled: " + ", ".join(multi) + "\n")
print("| threshold | right count | over | under | mean DER % | seg acc GT+-0.5 s |")
print("|---|---|---|---|---|---|")
for t in pool_ths:
    rs = [r for r in rows if r["scenario"] in multi and r["th"] == t and (r["on"], r["off"]) == (0.3, 0.5)]
    a = agg(rs)
    print(f"| {t:g} | {a['exact']}/{a['n']} | {a['over']} | {a['under']} | {a['der']:.1f} | {a['s_gt']:.1f} |")
print("\nSingle speaker false-split (est > 1) by threshold:\n")
print("| threshold | h_single_speaker | h_single_pink_snr10 |")
print("|---|---|---|")
for t in ths:
    c = []
    for s in ("h_single_speaker", "h_single_pink_snr10"):
        rs = [r for r in rows if r["scenario"] == s and r["th"] == t and (r["on"], r["off"]) == (0.3, 0.5)]
        c.append(f"{sum(r['est_spk'] > 1 for r in rs)}/{len(rs)}")
    print(f"| {t:g} | " + " | ".join(c) + " |")
