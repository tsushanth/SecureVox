#!/usr/bin/env python3
"""Accuracy check against KNOWN word times (macOS only: uses `say` and ffmpeg).

Real speech has no ground-truth word times offline, so this builds a clip whose word boundaries
are known: each word is synthesised on its own, trimmed to its voiced part and joined with a short
gap. Then it compares what the harness reports with the truth.

Caveat: isolated, evenly paced TTS words are easier than fluent speech in some ways (clear gaps)
and harder in others (odd prosody). Treat the numbers as a bias/spread indicator, not as an
accuracy figure for real recordings.

    synthetic_truth.py --harness BUILD/wt_harness --model ggml-tiny.bin [--dtw tiny] [--gap 0.12]
"""
import argparse
import difflib
import json
import re
import shutil
import statistics
import subprocess
import tempfile
import wave
from pathlib import Path

from word_timing_check import group_words, run_harness

TEXT = ("the count had thrown himself back on his seat leaning his shoulders against the partition "
        "of the tent and remained silent for a long while before he finally spoke to the english "
        "captain about the flowers and the baskets that were sent from the french camp")


def make_clip(tmp, gap_s, voice):
    words = TEXT.split()
    truth, pcm, pos = [], bytearray(), 0.0
    silence = b"\x00\x00" * int(16000 * gap_s)
    for i, w in enumerate(words):
        aiff, wav = tmp / f"{i}.aiff", tmp / f"{i}.wav"
        subprocess.run(["say", "-v", voice, "-o", str(aiff), w], check=True)
        subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", str(aiff),
                        "-af", "silenceremove=start_periods=1:start_threshold=-45dB:"
                               "stop_periods=1:stop_threshold=-45dB:stop_duration=0.05",
                        "-ar", "16000", "-ac", "1", "-c:a", "pcm_s16le", str(wav)], check=True)
        with wave.open(str(wav)) as f:
            data = f.readframes(f.getnframes())
        start = pos
        pcm += data
        pos += len(data) / 2 / 16000
        truth.append((w, start * 1000, pos * 1000))
        pcm += silence
        pos += gap_s
    out = tmp / "synthetic.wav"
    with wave.open(str(out), "wb") as f:
        f.setnchannels(1); f.setsampwidth(2); f.setframerate(16000); f.writeframes(bytes(pcm))
    return out, truth


def norm(w):
    return re.sub(r"[^a-z0-9]", "", w.lower())


def compare(label, segs, truth):
    got = [w for s in segs for w in group_words(s.get("tokens", []))]
    sm = difflib.SequenceMatcher(a=[norm(t[0]) for t in truth], b=[norm(g[0]) for g in got], autojunk=False)
    start_err, end_err = [], []
    for a, b, n in sm.get_matching_blocks():
        for k in range(n):
            start_err.append(got[b + k][1] - truth[a + k][1])
            end_err.append(got[b + k][2] - truth[a + k][2])
    ae = [abs(x) for x in start_err]
    print(f"{label}: matched {len(ae)}/{len(truth)} words")
    print(f"   start error: median {statistics.median(start_err):+.0f} ms (bias; + = late), "
          f"median |err| {statistics.median(ae):.0f} ms, p90 |err| {sorted(ae)[int(len(ae) * .9)]:.0f} ms, "
          f"within 100 ms {sum(a <= 100 for a in ae) / len(ae):.0%}, within 250 ms {sum(a <= 250 for a in ae) / len(ae):.0%}")
    print(f"   end error:   median {statistics.median(end_err):+.0f} ms")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--harness", required=True)
    ap.add_argument("--model", required=True)
    ap.add_argument("--dtw", choices=["tiny", "base", "small"])
    ap.add_argument("--gap", type=float, default=0.12, help="seconds of silence between words")
    ap.add_argument("--voice", default="Samantha")
    args = ap.parse_args()
    tmp = Path(tempfile.mkdtemp(prefix="wordtiming-synth-"))
    try:
        wav, truth = make_clip(tmp, args.gap, args.voice)
        print(f"synthetic clip: {len(truth)} words, {truth[-1][2] / 1000:.1f} s, gap {args.gap * 1000:.0f} ms")
        compare("token timestamps (what the app uses)", run_harness(args, str(wav)), truth)
        if args.dtw:
            compare(f"DTW ({args.dtw} heads)", run_harness(args, str(wav), ["--dtw", args.dtw]), truth)
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


if __name__ == "__main__":
    main()
