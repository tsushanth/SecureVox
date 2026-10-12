#!/usr/bin/env python3
"""Runs the word-timing harness on real speech clips and validates the words it produces.

The harness (wt_harness) runs whisper.cpp with the app's parameters and the app's JSON builder.
This script groups the token list into words the same way the app does for English
(whisper/WordTimes.kt: a token that starts with a space starts a word, punctuation attaches to the
word before it) and checks the result. See README.md.

    word_timing_check.py --harness BUILD/wt_harness --model ggml-small.bin \
        --parquet test.clean/0000.parquet --clips 4 [--dtw small]

Audio is decoded into a temp dir and deleted afterwards; nothing is written into the repo.
"""
import argparse
import io
import json
import shutil
import statistics
import subprocess
import sys
import tempfile
import wave
from pathlib import Path


def load_clips(args, tmp):
    """Returns [(name, wav_path, reference_text_or_None)]."""
    clips = []
    for w in args.wav or []:
        clips.append((Path(w).name, w, None))
    if args.parquet:
        import pyarrow.parquet as pq  # only needed for LibriSpeech parquet input

        pf = pq.ParquetFile(args.parquet)
        for batch in pf.iter_batches(batch_size=16, columns=["audio", "text", "id"]):
            for row in batch.to_pylist():
                if len(clips) >= args.clips + len(args.wav or []):
                    return clips
                flac = tmp / f"{row['id']}.flac"
                flac.write_bytes(row["audio"]["bytes"])
                wav = tmp / f"{row['id']}.wav"
                subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", str(flac), "-ar", "16000",
                                "-ac", "1", "-c:a", "pcm_s16le", str(wav)], check=True)
                with wave.open(str(wav)) as w:
                    dur = w.getnframes() / w.getframerate()
                if 6.0 <= dur <= 28.0:  # one 30 s window, like a chunk in the app
                    clips.append((row["id"], str(wav), row["text"]))
    return clips


def run_harness(args, wav, extra=()):
    out = subprocess.run([args.harness, args.model, wav, *extra], capture_output=True, text=True, check=True)
    return json.loads(out.stdout.strip().splitlines()[-1])


def group_words(tokens):
    """Mirror of groupTokensIntoWords (English): space starts a word, punctuation attaches."""
    words = []
    for text, s, e in tokens:
        if not words or text.startswith(" "):
            words.append([text.strip(), s, e])
        else:
            words[-1][0] += text
            words[-1][2] = e
    return [tuple(w) for w in words if w[0]]


def speech_bounds(wav_path, db=-35.0):
    """Energy-based first and last loud 20 ms frame, in ms (a rough, independent reference)."""
    import array
    import math

    with wave.open(wav_path) as w:
        pcm = array.array("h", w.readframes(w.getnframes()))
    frame = 320
    rms = [math.sqrt(sum(x * x for x in pcm[i:i + frame]) / frame) + 1e-9
           for i in range(0, len(pcm) - frame, frame)]
    ref = sorted(rms)[int(len(rms) * 0.95)]
    loud = [i for i, r in enumerate(rms) if 20 * math.log10(r / ref) > db]
    return (loud[0] * 20, (loud[-1] + 1) * 20) if loud else (0, 0)


def validate(segments):
    """Returns (problems list, stats dict) over all words of all segments."""
    problems, stats = [], {"words": 0, "zero_len": 0, "overlap": 0, "outside": 0, "nonmono": 0}
    prev_start = -1
    covered = 0
    total = 0
    for si, seg in enumerate(segments):
        words = group_words(seg.get("tokens", []))
        joined = " ".join(w[0] for w in words)
        norm = lambda t: "".join(t.split())
        if norm(joined) != norm(seg["text"]):
            problems.append(f"seg {si}: words do not rebuild the segment text")
        s0, s1 = seg["start"], seg["end"]
        total += max(s1 - s0, 1)
        for wi, (text, s, e) in enumerate(words):
            stats["words"] += 1
            if e == s:
                stats["zero_len"] += 1
            if s < prev_start:
                stats["nonmono"] += 1
                problems.append(f"seg {si} word {text!r}: start {s} before previous start {prev_start}")
            if s < s0 - 20 or e > s1 + 20:
                stats["outside"] += 1
                problems.append(f"seg {si} word {text!r}: [{s},{e}] outside segment [{s0:.0f},{s1:.0f}]")
            if wi + 1 < len(words) and e > words[wi + 1][1]:
                stats["overlap"] += 1
            covered += max(e - s, 0)
            prev_start = s
    stats["coverage"] = covered / total if total else 0.0
    return problems, stats


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--harness", required=True, help="path to the built wt_harness binary")
    ap.add_argument("--model", required=True, help="ggml model, e.g. ggml-small.bin")
    ap.add_argument("--parquet", help="LibriSpeech parquet (columns audio, text, id)")
    ap.add_argument("--wav", action="append", help="16 kHz mono wav (repeatable)")
    ap.add_argument("--clips", type=int, default=4, help="number of parquet clips")
    ap.add_argument("--dtw", choices=["tiny", "base", "small"], help="also compare against DTW timestamps")
    ap.add_argument("--show", type=int, default=12, help="words to print per clip")
    args = ap.parse_args()

    tmp = Path(tempfile.mkdtemp(prefix="wordtiming-"))
    try:
        clips = load_clips(args, tmp)
        if not clips:
            sys.exit("no clips")
        bad = 0
        all_dtw_diffs = []
        onset, offset = [], []
        for name, wav, ref in clips:
            plain = run_harness(args, wav, ["--no-tokens"])
            segs = run_harness(args, wav)
            same_text = [s["text"] for s in plain] == [s["text"] for s in segs]
            problems, st = validate(segs)
            words = [w for s in segs for w in group_words(s.get("tokens", []))]
            lo, hi = speech_bounds(wav)
            if words:
                onset.append(words[0][1] - lo)
                offset.append(words[-1][2] - hi)
            print(f"\n== {name}: {len(segs)} segments, {st['words']} words, text unchanged by token timing: {same_text}")
            if ref:
                print(f"   reference: {ref.lower()[:110]}")
            print(f"   zero-length {st['zero_len']}, overlapping {st['overlap']}, non-monotonic {st['nonmono']}, "
                  f"outside segment {st['outside']}, coverage {st['coverage']:.0%}")
            if words:
                print(f"   first word starts {words[0][1] - lo:+d} ms vs energy onset; "
                      f"last word ends {words[-1][2] - hi:+d} ms vs energy offset")
            print("   " + " ".join(f"{w[0]}[{w[1] / 1000:.2f}-{w[2] / 1000:.2f}]" for w in words[:args.show]) + " ...")
            for p in problems[:5]:
                print("   PROBLEM:", p)
            if not same_text or st["nonmono"] or problems:
                bad += 1
            if args.dtw:
                dtw = run_harness(args, wav, ["--dtw", args.dtw])
                dwords = [w for s in dtw for w in group_words(s.get("tokens", []))]
                if len(dwords) == len(words):
                    diffs = [abs(a[1] - b[1]) for a, b in zip(words, dwords)]
                    all_dtw_diffs += diffs
                    print(f"   heuristic vs DTW word starts: median {statistics.median(diffs):.0f} ms, "
                          f"p90 {sorted(diffs)[int(len(diffs) * .9)]} ms")
                    dp, ds = validate(dtw)
                    print(f"   DTW: zero-length {ds['zero_len']}, overlapping {ds['overlap']}, "
                          f"non-monotonic {ds['nonmono']}, coverage {ds['coverage']:.0%}")
                else:
                    print(f"   DTW produced {len(dwords)} words vs {len(words)}; not comparable")
        print("\n== summary")
        print(f"   clips with problems: {bad}/{len(clips)}")
        if onset:
            print(f"   first-word start vs energy onset: median {statistics.median(onset):+.0f} ms")
            print(f"   last-word end vs energy offset:   median {statistics.median(offset):+.0f} ms")
        if all_dtw_diffs:
            print(f"   heuristic vs DTW word starts: median {statistics.median(all_dtw_diffs):.0f} ms over {len(all_dtw_diffs)} words")
        sys.exit(1 if bad else 0)
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


if __name__ == "__main__":
    main()
