"""Long-recording proxy: runtime and peak RSS of the app's diarizer on a ~N-minute concatenation.

usage: python long_run.py --minutes 30 --speakers 4 [--threads 2] [--json out.json]
Run one process per measurement so ru_maxrss is that run's peak. Mac numbers are only a proxy
for a phone (different CPU, memory pressure, thermal limits). The corpus only has ~19 min of
unique speech, so longer inputs reuse random windows of each speaker's audio (same voices, not
identical clips).
"""
import argparse
import json
import resource
import time

import numpy as np

import sbench as sb

ap = argparse.ArgumentParser()
ap.add_argument("--minutes", type=float, default=30)
ap.add_argument("--speakers", type=int, default=4)
ap.add_argument("--threads", type=int, default=2)
ap.add_argument("--json", default="")
a = ap.parse_args()

c = sb.Corpus()
rng = np.random.default_rng(11)
spk = list(c.speakers); rng.shuffle(spk); spk = spk[: a.speakers]
n_turns = int(a.minutes * 60 / 7.5)
mix = sb.build_mix(c, spk, n_turns, (3, 12), rng, order="random")
total = len(mix.audio) / sb.SR
base = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss
t0 = time.time()
spans = sb.diarize(mix.audio, threads=a.threads)
el = time.time() - t0
peak = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss  # bytes on macOS
d, miss, fa, conf, k = sb.der(mix.turns, spans, total)
out = dict(minutes=round(total / 60, 1), true_spk=mix.n_spk, est_spk=k, der=round(d, 4),
           miss=round(miss, 4), fa=round(fa, 4), conf=round(conf, 4), threads=a.threads,
           proc_s=round(el, 1), realtime_x=round(total / el, 1),
           peak_rss_mb=round(peak / 1e6), rss_before_diarize_mb=round(base / 1e6))
print(json.dumps(out))
if a.json:
    open(a.json, "a").write(json.dumps(out) + "\n")
