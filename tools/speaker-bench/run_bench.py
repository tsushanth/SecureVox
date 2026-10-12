"""Run all scenarios x configs and write results.csv (one row per mix x config).

usage: python run_bench.py [--n 12] [--workers 4] [--sweep] [--out results.csv]
Without --sweep only the app configuration (th 0.85, on 0.3, off 0.5) is run on every scenario.
With --sweep the core scenarios are also run over the threshold and min-duration grids.
Mixes are generated deterministically from (scenario, index), so reruns are reproducible.
"""
import argparse
import csv
import itertools
import time
from concurrent.futures import ProcessPoolExecutor

import numpy as np

import sbench as sb

NOISE = ("pink", "white")


def scenarios():
    """name -> (builder(corpus, rng) -> Mix). Speakers are drawn at random from the corpus."""
    S = {}

    def pick(c, rng, n, kind=None):
        spk = list(c.speakers)
        rng.shuffle(spk)
        if kind == "same":
            g = "M" if rng.random() < 0.5 else "F"
            spk = [s for s in spk if c.gender[s] == g]
        elif kind == "mixed":
            m = [s for s in spk if c.gender[s] == "M"]; f = [s for s in spk if c.gender[s] == "F"]
            return [m[0], f[0]] if n == 2 else None
        return spk[:n]

    def clean(n, turns, dur=(4, 10), overlap=0.0, kind=None, order="roundrobin"):
        def b(c, rng):
            return sb.build_mix(c, pick(c, rng, n, kind), turns, dur, rng, overlap=overlap, order=order)
        return b

    S["a_2spk_clean"] = clean(2, 8)
    S["a_3spk_clean"] = clean(3, 9)
    S["b_4spk_clean"] = clean(4, 12, order="random")
    S["b_5spk_clean"] = clean(5, 15, order="random")
    S["b_6spk_clean"] = clean(6, 18, order="random")
    S["d_short_turns_2spk"] = clean(2, 24, dur=(1, 3))
    S["d_short_turns_3spk"] = clean(3, 27, dur=(1, 3), order="random")
    S["d_long_turns_2spk"] = clean(2, 4, dur=(18, 26))
    S["e_overlap20_2spk"] = clean(2, 8, overlap=0.2)
    S["e_overlap50_2spk"] = clean(2, 8, overlap=0.5)
    S["e_overlap20_3spk"] = clean(3, 9, overlap=0.2)
    S["e_overlap50_3spk"] = clean(3, 9, overlap=0.5)
    S["f_same_gender_2spk"] = clean(2, 8, kind="same")
    S["f_mixed_gender_2spk"] = clean(2, 8, kind="mixed")

    def single(c, rng):
        s = pick(c, rng, 1)
        return sb.build_mix(c, s, 6, (4, 10), rng)
    S["h_single_speaker"] = single

    def single_short(c, rng):
        return sb.build_mix(c, pick(c, rng, 1), 20, (1, 3), rng)
    S["h_single_speaker_short"] = single_short

    for kind in NOISE:
        for snr in (20, 10, 5):
            for n in (2, 3):
                def b(c, rng, n=n, snr=snr, kind=kind):
                    m = clean(n, 8 if n == 2 else 9)(c, rng)
                    m.audio = sb.add_noise(m.audio, snr, kind, rng)
                    return m
                S[f"c_{kind}_snr{snr}_{n}spk"] = b
        def bs(c, rng, kind=kind):
            m = single(c, rng)
            m.audio = sb.add_noise(m.audio, 10, kind, rng)
            return m
        S[f"h_single_{kind}_snr10"] = bs
    return S


CORE = ["a_2spk_clean", "b_4spk_clean", "d_short_turns_2spk", "e_overlap20_2spk",
        "c_pink_snr10_2spk", "h_single_speaker"]

_corpus = None


def work(task):
    global _corpus
    name, idx, th, on, off = task
    if _corpus is None:
        _corpus = sb.Corpus()
    seed = sum(map(ord, name)) * 1000 + idx  # deterministic (str hash() is salted per process)
    rng = np.random.default_rng(seed)
    mix = scenarios()[name](_corpus, rng)
    total = len(mix.audio) / sb.SR
    t0 = time.time()
    spans = sb.diarize(mix.audio, th, on, off)
    el = time.time() - t0
    d, miss, fa, conf, k = sb.der(mix.turns, spans, total)
    row = dict(scenario=name, mix=idx, th=th, on=on, off=off, dur_s=round(total, 1),
               true_spk=mix.n_spk, est_spk=k, count_ok=int(k == mix.n_spk), der=round(d, 4),
               miss=round(miss, 4), fa=round(fa, 4), conf=round(conf, 4), proc_s=round(el, 2))
    # app-level segment labelling: app spans vs oracle (ground-truth spans) on same pseudo-segments
    oracle = [(int(a * 1000), int(b * 1000), s) for a, b, s in mix.turns]
    for mode in ("gt_jitter", "sub_cuts", "sub_jitter"):
        srng = np.random.default_rng(seed + 7)
        segs = sb.pseudo_segments(mix.turns, srng, mode)
        truth = [sb.truth_label(s, mix.turns) for s in segs]
        row[f"seg_{mode}_app"] = round(sb.seg_accuracy(sb.assign_speakers(segs, spans), truth), 4) \
            if spans else 0.0
        row[f"seg_{mode}_oracle"] = round(sb.seg_accuracy(sb.assign_speakers(segs, oracle), truth), 4)
    row["n_segs_gt_jitter"] = len(sb.pseudo_segments(mix.turns, np.random.default_rng(seed + 7), "gt_jitter"))
    return row


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--n", type=int, default=12)
    ap.add_argument("--workers", type=int, default=4)
    ap.add_argument("--sweep", action="store_true")
    ap.add_argument("--only", default="")
    ap.add_argument("--core", default="", help="comma list overriding the sweep scenarios")
    ap.add_argument("--ths", default="0.6,0.7,0.8,0.9,0.95", help="sweep thresholds")
    ap.add_argument("--no-mins", action="store_true", help="skip the min-duration sweep")
    ap.add_argument("--skip-app", action="store_true", help="do not run the app config baseline")
    ap.add_argument("--sweep-n", type=int, default=0, help="mixes per sweep scenario (default: --n)")
    ap.add_argument("--out", default="results.csv")
    a = ap.parse_args()
    S = scenarios()
    names = [x for x in S if (not a.only or a.only in x)]
    app = (sb.APP["th"], sb.APP["on"], sb.APP["off"])
    tasks = [] if a.skip_app else [(n, i, *app) for n in names for i in range(a.n)]
    if a.sweep:
        ths = [float(x) for x in a.ths.split(",")]
        mins = [] if a.no_mins else [(0.1, 0.3), (0.5, 0.5), (0.3, 1.0)]
        core = a.core.split(",") if a.core else CORE
        for n in [x for x in core if x in names]:
            for i in range(a.sweep_n or a.n):
                tasks += [(n, i, th, 0.3, 0.5) for th in ths]
                tasks += [(n, i, 0.85, on, off) for on, off in mins]
    print(len(tasks), "tasks", flush=True)
    f = open(a.out, "w", newline="")  # rows are appended as they finish, so partial runs are usable
    w = None
    with ProcessPoolExecutor(a.workers) as ex:
        for k, r in enumerate(ex.map(work, tasks, chunksize=1)):
            if w is None:
                w = csv.DictWriter(f, fieldnames=list(r)); w.writeheader()
            w.writerow(r); f.flush()
            if k % 25 == 0:
                print(k, r["scenario"], r["th"], r["true_spk"], r["est_spk"], r["der"], flush=True)
    f.close()
    print("wrote", a.out)


if __name__ == "__main__":
    main()
