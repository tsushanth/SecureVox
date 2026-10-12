"""Shared helpers for the SecureVox speaker-label benchmark.

Mirrors the app: same models, same sherpa-onnx version (1.13.8), same clustering/min-duration
parameters as SpeakerDiarizer.kt, and a faithful port of SpeakerAlign.kt assignSpeakers.
Only real human speech (LibriSpeech) is used as speakers; synthetic TTS voices merge into one.
"""
import os
import pickle

import numpy as np
import sherpa_onnx
from scipy.optimize import linear_sum_assignment

SR = 16000
FR = 100  # scoring frames per second
HERE = os.path.dirname(os.path.abspath(__file__))
MODELS = os.environ.get("SB_MODELS", os.path.join(HERE, "models"))
PKL = os.environ.get("SB_PKL", os.path.join(HERE, "data", "diar_utts.pkl"))

# App defaults (SpeakerDiarizer.kt)
APP = dict(th=0.85, on=0.3, off=0.5)


# ---------------------------------------------------------------- data
def trim(y, pad=0.1, db=-40):
    """Trim leading/trailing silence so reference intervals are tight."""
    w = 160
    n = len(y) // w
    e = np.sqrt((y[: n * w].reshape(n, w) ** 2).mean(1) + 1e-12)
    thr = e.max() * 10 ** (db / 20)
    idx = np.where(e > thr)[0]
    if len(idx) == 0:
        return y
    a = max(0, idx[0] * w - int(pad * SR))
    b = min(len(y), (idx[-1] + 1) * w + int(pad * SR))
    return y[a:b]


def f0_median(y):
    """Crude autocorrelation pitch, used only to label speakers M/F (no metadata offline)."""
    vals = []
    w = 800
    for i in range(0, len(y) - w * 2, 1600):
        x = y[i : i + w * 2]
        if np.sqrt((x ** 2).mean()) < 0.02:
            continue
        x = x - x.mean()
        ac = np.correlate(x, x, "full")[len(x) - 1 :]
        lo, hi = SR // 400, SR // 70
        k = lo + int(np.argmax(ac[lo:hi]))
        if ac[k] > 0.4 * ac[0]:
            vals.append(SR / k)
    return float(np.median(vals)) if vals else 0.0


class Corpus:
    def __init__(self, pkl=PKL):
        raw = pickle.load(open(pkl, "rb"))
        self.pool = {}   # speaker -> concatenated trimmed speech
        self.utts = {}   # speaker -> list of trimmed utterances
        self.gender = {}
        for s, us in raw.items():
            us = [trim(u) for u in us]
            self.utts[s] = us
            self.pool[s] = np.concatenate(us)
            self.gender[s] = "F" if f0_median(self.pool[s]) > 165 else "M"
        self.speakers = sorted(self.pool)

    def window(self, spk, dur, rng):
        """A random window of real speech from this speaker (may reuse audio when dur > pool)."""
        p = self.pool[spk]
        n = int(dur * SR)
        if n >= len(p):
            reps = int(np.ceil(n / len(p)))
            return np.tile(p, reps)[:n]
        a = rng.integers(0, len(p) - n)
        return p[a : a + n]


# ---------------------------------------------------------------- mixes
class Mix:
    def __init__(self, audio, turns, n_spk, tag=""):
        self.audio = audio.astype("float32")
        self.turns = turns  # list of (start_s, end_s, speaker)
        self.n_spk = n_spk
        self.tag = tag


def build_mix(corpus, speakers, n_turns, dur_range, rng, overlap=0.0, gap_range=(0.3, 1.0),
              order="roundrobin"):
    """Alternate turns between `speakers`; `overlap` = fraction of the shorter of two adjacent
    turns that is spoken simultaneously (0 = clean gaps)."""
    turns, pieces = [], []
    t = 0.0
    prev = None
    k = len(speakers)
    seq = [speakers[i % k] for i in range(n_turns)] if order == "roundrobin" else \
        [speakers[int(rng.integers(k))] for _ in range(n_turns)]
    if order != "roundrobin":  # no immediate repeats, every speaker at least once
        for i in range(1, n_turns):
            while seq[i] == seq[i - 1]:
                seq[i] = speakers[int(rng.integers(k))]
        for j, s in enumerate(speakers):
            if s not in seq:
                seq[j] = s
    for s in seq:
        d = float(rng.uniform(*dur_range))
        y = corpus.window(s, d, rng)
        L = len(y) / SR
        if prev is None:
            start = 0.0
        elif overlap > 0:
            start = max(0.0, prev[1] - overlap * min(L, prev[1] - prev[0]))
        else:
            start = prev[1] + float(rng.uniform(*gap_range))
        turns.append((start, start + L, s))
        pieces.append((int(start * SR), y))
        prev = (start, start + L)
    total = int(max(e for _, e, _ in turns) * SR) + int(0.5 * SR)
    audio = np.zeros(total, dtype="float32")
    for off, y in pieces:
        audio[off : off + len(y)] += y
    return Mix(np.clip(audio, -1, 1), turns, len(set(seq)))


def add_noise(audio, snr_db, kind, rng):
    n = len(audio)
    if kind == "white":
        z = rng.standard_normal(n)
    else:  # pink, 1/f power
        f = np.fft.rfft(rng.standard_normal(n))
        fr = np.arange(len(f)); fr[0] = 1
        z = np.fft.irfft(f / np.sqrt(fr), n)
    sig = np.sqrt((audio ** 2).mean() + 1e-12)
    z = z / (np.sqrt((z ** 2).mean()) + 1e-12) * sig * 10 ** (-snr_db / 20)
    return np.clip(audio + z, -1, 1).astype("float32")


# ---------------------------------------------------------------- diarizer
_cache = {}


def make_diarizer(th, on, off, threads=1):
    key = (th, on, off, threads)
    if key not in _cache:
        cfg = sherpa_onnx.OfflineSpeakerDiarizationConfig(
            segmentation=sherpa_onnx.OfflineSpeakerSegmentationModelConfig(
                pyannote=sherpa_onnx.OfflineSpeakerSegmentationPyannoteModelConfig(
                    model=os.path.join(MODELS, "segmentation.onnx")),
                num_threads=threads),
            embedding=sherpa_onnx.SpeakerEmbeddingExtractorConfig(
                model=os.path.join(MODELS, "embedding.onnx"), num_threads=threads),
            clustering=sherpa_onnx.FastClusteringConfig(num_clusters=-1, threshold=th),
            min_duration_on=on, min_duration_off=off)
        _cache[key] = sherpa_onnx.OfflineSpeakerDiarization(cfg)
    return _cache[key]


def diarize(audio, th=APP["th"], on=APP["on"], off=APP["off"], threads=1):
    """Returns spans [(start_ms, end_ms, speaker)] exactly like SpeakerDiarizer.diarize."""
    segs = make_diarizer(th, on, off, threads).process(audio).sort_by_start_time()
    return [(int(s.start * 1000), int(s.end * 1000), int(s.speaker)) for s in segs]


# ---------------------------------------------------------------- scoring
def frame_sets(spans_s, total_s):
    n = int(total_s * FR) + 2
    sets = [[] for _ in range(n)]
    for a, b, s in spans_s:
        for i in range(int(a * FR), min(n, int(b * FR))):
            sets[i].append(s)
    return sets


def der(ref_turns, spans_ms, total_s):
    """Frame-level DER (no collar): (missed + false alarm + confusion) / reference speaker-frames,
    with the optimal one-to-one hypothesis->reference speaker mapping (Hungarian)."""
    R = frame_sets(ref_turns, total_s)
    H = frame_sets([(a / 1000, b / 1000, s) for a, b, s in spans_ms], total_s)
    rn = sorted({s for _, _, s in ref_turns})
    hn = sorted({s for _, _, s in spans_ms})
    C = np.zeros((max(len(hn), 1), max(len(rn), 1)))
    for r, h in zip(R, H):
        for hs in h:
            for rs in r:
                C[hn.index(hs), rn.index(rs)] += 1
    ri, ci = linear_sum_assignment(-C)
    mapping = {hn[i]: rn[j] for i, j in zip(ri, ci) if C[i, j] > 0 and i < len(hn) and j < len(rn)}
    tot = miss = fa = conf = 0
    for r, h in zip(R, H):
        nr, nh = len(r), len(h)
        tot += nr
        mh = [mapping.get(x) for x in h]
        correct = sum(1 for x in set(r) if x in mh)
        miss += max(0, nr - nh)
        fa += max(0, nh - nr)
        conf += min(nr, nh) - correct
    d = (miss + fa + conf) / max(tot, 1)
    return d, miss / max(tot, 1), fa / max(tot, 1), conf / max(tot, 1), len(hn)


# ---------------------------------------------------------------- app labelling port
def _dominant(start, end, spans):
    ov = {}
    for a, b, s in spans:
        sh = min(end, b) - max(start, a)
        if sh > 0:
            ov[s] = ov.get(s, 0) + sh
    if ov:
        return max(ov.items(), key=lambda kv: kv[1])[0]  # Kotlin maxByOrNull: first max wins
    mid = (start + end) // 2

    def dist(sp):
        a, b, _ = sp
        return a - mid if mid < a else (mid - b if mid > b else 0)
    return min(spans, key=dist)[2]


def assign_speakers(segments_ms, spans_ms):
    """Port of SpeakerAlign.kt assignSpeakers (renumbered by first appearance)."""
    if not spans_ms or not segments_ms:
        return [-1] * len(segments_ms)
    raw = [_dominant(a, b, spans_ms) for a, b in segments_ms]
    order = {}
    for r in raw:
        order.setdefault(r, len(order))
    return [order[r] for r in raw]


def pseudo_segments(turns, rng, mode):
    """Sentence-like segments. mode:
    'gt_jitter'  one per ground-truth turn, both boundaries shifted by U(-0.5, 0.5) s
    'sub_cuts'   each turn cut into 2-3 sub-utterance pieces (cuts at random interior points)
    'sub_jitter' sub-utterance pieces with jitter too
    Returns [(start_ms, end_ms)]; segments are made contiguous-free (independent jitter)."""
    segs = []
    for a, b, _ in turns:
        if mode == "gt_jitter":
            pieces = [(a, b)]
        else:
            k = int(rng.integers(2, 4))
            cuts = sorted(rng.uniform(a, b, k - 1)) if b - a > 1.5 else []
            edges = [a] + list(cuts) + [b]
            pieces = list(zip(edges[:-1], edges[1:]))
        for x, y in pieces:
            if mode in ("gt_jitter", "sub_jitter"):
                x += rng.uniform(-0.5, 0.5); y += rng.uniform(-0.5, 0.5)
            x, y = max(0.0, x), max(x + 0.2, y)
            segs.append((int(x * 1000), int(y * 1000)))
    return sorted(segs)


def truth_label(seg, turns):
    a, b = seg[0] / 1000, seg[1] / 1000
    ov = {}
    for s, e, spk in turns:
        sh = min(b, e) - max(a, s)
        if sh > 0:
            ov[spk] = ov.get(spk, 0) + sh
    return max(ov.items(), key=lambda kv: kv[1])[0] if ov else None


def seg_accuracy(pred, truth):
    """Accuracy of predicted labels vs truth after the best one-to-one label mapping."""
    keep = [(p, t) for p, t in zip(pred, truth) if t is not None]
    if not keep:
        return float("nan")
    ps = sorted({p for p, _ in keep}); ts = sorted({t for _, t in keep})
    C = np.zeros((len(ps), len(ts)))
    for p, t in keep:
        C[ps.index(p), ts.index(t)] += 1
    ri, ci = linear_sum_assignment(-C)
    return float(C[ri, ci].sum() / len(keep))
