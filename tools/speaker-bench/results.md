# SecureVox speaker labels: benchmark results

Models: pyannote segmentation 3.0 + NeMo TitaNet-small, sherpa-onnx 1.13.8, app settings
(threshold 0.85, min on 0.3 s, min off 0.5 s). Real LibriSpeech speakers only (31 speakers, about
19 min of unique speech). Mixes are built by alternating turns; windows of a speaker's speech are
reused across mixes. `results.csv` has one row per mix x config (817 rows), `splits.csv` the
cluster-size analysis. Method and caveats are in `README.md`.

## Headline

- Speaker count is the weak point, and the failure is almost always over-splitting. At the app
  settings, across 208 multi-speaker mixes: right count 138 (66%), too many speakers 63, too few 7.
  One speaker is almost never split: 1 false split in 32 single-speaker mixes (the one was white noise at 10 dB; clean and pink were 0/24).
- Segment labels (what the user sees) are better than the count suggests, because an extra small
  cluster usually steals only a few segments: 90% mean accuracy on the core multi-speaker set
  versus 100% for an oracle given the same sentence-sized segments.
- It works: 2 to 3 speakers with long turns (long-turn 2 speaker 8/8, 100%), mixed-gender pairs
  (8/8, 100%), 20% overlap (8/8, 95%), moderate noise on 3 speakers.
- It breaks: rapid turn-taking (1 to 3 s turns: 38 to 42% DER, 70 to 76% segment accuracy),
  50% overlap (60 to 78%), 5 or more speakers (count right 3/8 at six), and long recordings (below).
- Noise did not behave monotonically. SNR 5 dB was not worse than 20 dB at 8 mixes each, so the
  data does not show a clear noise cliff; white noise is somewhat worse than pink.

## Long recording (proxy only)

One run, 32.7 min built from 4 speakers, 2 threads, Mac under heavy load (load average about 12
from other sessions): 237 s wall time (8.3x realtime), 581 MB peak RSS (517 MB of that was the
test corpus before diarizing). Accuracy: **10 speakers found for 4 true, DER 38%** (confusion 34%).
This is a single run, but it matches the clustering behaviour: more audio means more chances for
spurious clusters. Phone numbers will differ (slower CPU, memory pressure, thermal limits);
treat 8x realtime as an upper bound for a phone, not an estimate.

## Threshold decision: unchanged at 0.85

Pooled over 13 multi-speaker scenarios (104 mixes per threshold, `Threshold sweep` below):

| threshold | right count | over | under | DER % | segment acc % |
|---|---|---|---|---|---|
| 0.80 | 58/104 | 44 | 2 | 18.4 | 88.5 |
| 0.85 (app) | 67/104 | 32 | 5 | 17.3 | 89.9 |
| 0.90 | 76/104 | 21 | 7 | 16.8 | 90.0 |
| 0.95 | 81/104 | 14 | 9 | 16.8 | 89.3 |

Higher thresholds trade over-splits for under-splits (merging real people), and segment accuracy,
the user-visible metric, is flat from 0.85 to 0.95 (89.9, 90.0, 89.3). With 8 mixes per scenario one
mix is 12.5 points, and this corpus is read speech with distinct voices; real calls with similar
voices will merge more at 0.9+. A 0.1 point segment gain does not justify moving a constant that
shipped to 100% of users. **No Kotlin was changed.** If a later change is made, 0.9 is the only
candidate, and it should be validated on real conversational audio first.
Min-duration settings (on 0.1 to 0.5 s, off 0.3 to 1.0 s) changed little; off 1.0 s hurts short turns
(DER 38 to 45%).

## Small-cluster filter: helps the count, deliberately NOT shipped

Discarding clusters with under 5 s of speech lifts the right-count from 73/104 to 84/104 at 0.85
(`analyze_splits.py`), with unchanged segment accuracy (92.2%) and DER, and no single-speaker
change. But every simulated speaker here talks for 15 s or more, so the test cannot show the cost:
a real third person who says a few sentences would be erased and relabelled as someone else. That is
a plausible meeting case, so the filter trades a cosmetic count for silently wrong attribution.
It is not in the app.

## What the app should tell users

- Speaker labels are a best guess, strongest for 2 to 3 people who take longer turns.
- Expect extra speakers ("Speaker 4" that is really Speaker 2), especially for 5 or more people,
  long recordings (30 minutes or more), fast back-and-forth, overlapping talk, and similar voices.
- Individual sentences can carry the wrong label where people interrupt each other.
- Concrete recommendation: label the feature "beta / may split one person into several"; show
  a caution when the result has more than 5 speakers or the recording is over about 30 minutes; let
  users rename and merge speakers (the over-split failure mode is fixed by a merge, so a merge
  action is the highest-value follow-up); do not disable it for noise, the data shows no cliff.

## Not verified

- No real conversational audio, phone microphone audio, accents or children; read speech only.
- Overlap is synthetic summing of separate recordings, not natural interruption.
- Gender was estimated from pitch, not metadata.
- 8 mixes per scenario (the first plan of 12 plus a larger sweep was too slow on the loaded Mac), so
  per-scenario figures have wide error bars; only pooled numbers are meaningful.
- Long-run accuracy and timing are one run each. Phone performance was not measured.
- Segment accuracy uses synthetic sentence cuts (ground-truth turns shifted by +/- 0.5 s), not
  Whisper's actual segments.

## Tables

All numbers are means over 8 mixes per scenario. "seg acc" is segment label accuracy under the
app's dominant-overlap rule, with the oracle (ground-truth spans, same segments) in brackets.


### App configuration (threshold 0.85, min on 0.3 s, min off 0.5 s)

| scenario | mixes | right count | over / under | DER % (miss / FA / conf) | seg acc, GT+-0.5s: app (oracle) | seg acc, sub-cuts+-0.5s: app (oracle) |
|---|---|---|---|---|---|---|
| a_2spk_clean | 8 | 5.0/8 | 2 / 1 | 18.7 (5.8 / 2.2 / 10.8) | 89.1 (100.0) | 87.6 (100.0) |
| a_3spk_clean | 8 | 7.0/8 | 1 / 0 | 11.7 (5.2 / 1.9 / 4.6) | 95.8 (100.0) | 95.3 (100.0) |
| b_4spk_clean | 8 | 5.0/8 | 2 / 1 | 13.8 (5.4 / 2.5 / 5.9) | 93.8 (100.0) | 91.3 (100.0) |
| b_5spk_clean | 8 | 6.0/8 | 2 / 0 | 10.7 (4.4 / 2.5 / 3.7) | 98.3 (100.0) | 95.1 (100.0) |
| b_6spk_clean | 8 | 3.0/8 | 5 / 0 | 11.9 (4.1 / 2.3 / 5.4) | 95.8 (100.0) | 94.0 (100.0) |
| c_pink_snr10_2spk | 8 | 4.0/8 | 4 / 0 | 12.9 (3.6 / 2.6 / 6.8) | 93.8 (100.0) | 90.2 (100.0) |
| c_pink_snr10_3spk | 8 | 5.0/8 | 3 / 0 | 14.4 (4.1 / 2.2 / 8.1) | 94.4 (100.0) | 90.6 (100.0) |
| c_pink_snr20_2spk | 8 | 5.0/8 | 2 / 1 | 16.7 (3.0 / 2.3 / 11.4) | 87.5 (100.0) | 87.7 (100.0) |
| c_pink_snr20_3spk | 8 | 7.0/8 | 1 / 0 | 9.4 (4.1 / 2.1 / 3.2) | 98.6 (100.0) | 95.4 (100.0) |
| c_pink_snr5_2spk | 8 | 7.0/8 | 1 / 0 | 10.1 (3.6 / 2.7 / 3.7) | 96.9 (100.0) | 95.3 (100.0) |
| c_pink_snr5_3spk | 8 | 7.0/8 | 1 / 0 | 9.1 (4.3 / 2.1 / 2.8) | 97.2 (100.0) | 96.4 (100.0) |
| c_white_snr10_2spk | 8 | 4.0/8 | 4 / 0 | 12.0 (5.0 / 3.0 / 4.1) | 95.3 (100.0) | 94.3 (100.0) |
| c_white_snr10_3spk | 8 | 5.0/8 | 3 / 0 | 17.9 (3.8 / 3.8 / 10.2) | 90.3 (100.0) | 85.8 (100.0) |
| c_white_snr20_2spk | 8 | 6.0/8 | 2 / 0 | 21.3 (3.1 / 3.6 / 14.6) | 82.8 (100.0) | 82.7 (100.0) |
| c_white_snr20_3spk | 8 | 4.0/8 | 4 / 0 | 17.3 (5.3 / 2.4 / 9.6) | 91.7 (100.0) | 87.0 (100.0) |
| c_white_snr5_2spk | 8 | 5.0/8 | 3 / 0 | 16.8 (5.3 / 2.1 / 9.4) | 90.6 (100.0) | 90.2 (100.0) |
| c_white_snr5_3spk | 8 | 3.0/8 | 5 / 0 | 16.8 (5.3 / 3.5 / 8.0) | 93.1 (100.0) | 89.9 (100.0) |
| d_long_turns_2spk | 8 | 8.0/8 | 0 / 0 | 4.2 (3.0 / 0.9 / 0.3) | 100.0 (100.0) | 98.8 (100.0) |
| d_short_turns_2spk | 8 | 5.0/8 | 2 / 1 | 38.0 (8.0 / 10.1 / 19.9) | 75.5 (100.0) | 76.8 (100.0) |
| d_short_turns_3spk | 8 | 2.0/8 | 4 / 2 | 42.1 (6.2 / 8.7 / 27.2) | 70.4 (100.0) | 70.6 (100.0) |
| e_overlap20_2spk | 8 | 8.0/8 | 0 / 0 | 8.6 (5.9 / 1.6 / 1.0) | 95.3 (100.0) | 87.5 (100.0) |
| e_overlap20_3spk | 8 | 7.0/8 | 1 / 0 | 7.1 (3.8 / 1.3 / 1.9) | 98.6 (100.0) | 94.5 (100.0) |
| e_overlap50_2spk | 8 | 4.0/8 | 4 / 0 | 18.3 (7.8 / 1.5 / 9.1) | 78.1 (100.0) | 71.4 (99.3) |
| e_overlap50_3spk | 8 | 2.0/8 | 5 / 1 | 35.3 (7.7 / 2.1 / 25.5) | 59.7 (100.0) | 57.6 (100.0) |
| f_mixed_gender_2spk | 8 | 8.0/8 | 0 / 0 | 6.9 (3.7 / 2.3 / 0.9) | 100.0 (100.0) | 100.0 (100.0) |
| f_same_gender_2spk | 8 | 6.0/8 | 2 / 0 | 9.7 (3.5 / 2.3 / 3.9) | 95.3 (100.0) | 96.2 (100.0) |
| h_single_pink_snr10 | 8 | 8.0/8 | 0 / 0 | 8.6 (4.1 / 4.5 / 0.0) | 100.0 (100.0) | 100.0 (100.0) |
| h_single_speaker | 8 | 8.0/8 | 0 / 0 | 7.9 (4.4 / 3.5 / 0.0) | 100.0 (100.0) | 100.0 (100.0) |
| h_single_speaker_short | 8 | 8.0/8 | 0 / 0 | 24.1 (4.3 / 19.8 / 0.0) | 100.0 (100.0) | 100.0 (100.0) |
| h_single_white_snr10 | 8 | 7.0/8 | 1 / 0 | 7.6 (2.9 / 4.7 / 0.0) | 100.0 (100.0) | 100.0 (100.0) |

### Threshold sweep (min on 0.3 / off 0.5): right-count / mean DER % / seg acc GT+-0.5 s (app)

| scenario | 0.6 | 0.7 | 0.8 | 0.85 | 0.9 | 0.95 |
|---|---|---|---|---|---|---|
| a_2spk_clean | 3.0/8 / 18.5 / 91 | 5.0/8 / 17.6 / 91 | 6.0/8 / 14.7 / 94 | 5.0/8 / 18.7 / 89 | 5.0/8 / 18.7 / 89 | 5.0/8 / 17.5 / 91 |
| a_3spk_clean | - | - | 7.0/8 / 11.7 / 96 | 7.0/8 / 11.7 / 96 | 7.0/8 / 11.7 / 96 | 7.0/8 / 11.7 / 96 |
| b_4spk_clean | 3.0/8 / 17.5 / 89 | 3.0/8 / 16.6 / 90 | 2.0/8 / 15.7 / 91 | 5.0/8 / 13.8 / 94 | 5.0/8 / 14.0 / 94 | 6.0/8 / 13.8 / 94 |
| b_5spk_clean | - | - | 6.0/8 / 12.0 / 97 | 6.0/8 / 10.7 / 98 | 6.0/8 / 10.0 / 99 | 7.0/8 / 9.6 / 99 |
| b_6spk_clean | - | - | 2.0/8 / 14.4 / 93 | 3.0/8 / 11.9 / 96 | 5.0/8 / 11.4 / 97 | 3.0/8 / 12.7 / 95 |
| c_pink_snr10_2spk | 2.0/8 / 26.6 / 78 | 2.0/8 / 19.9 / 86 | 3.0/8 / 16.8 / 89 | 4.0/8 / 12.9 / 94 | 5.0/8 / 11.8 / 95 | 6.0/8 / 11.5 / 95 |
| c_pink_snr5_2spk | - | - | 4.0/8 / 13.2 / 94 | 7.0/8 / 10.1 / 97 | 8.0/8 / 9.1 / 97 | 8.0/8 / 9.1 / 97 |
| c_white_snr10_3spk | - | - | 5.0/8 / 17.9 / 90 | 5.0/8 / 17.9 / 90 | 6.0/8 / 22.4 / 83 | 7.0/8 / 21.4 / 83 |
| d_short_turns_2spk | 4.0/8 / 38.8 / 74 | 4.0/8 / 38.0 / 76 | 5.0/8 / 38.0 / 76 | 5.0/8 / 38.0 / 76 | 7.0/8 / 34.5 / 79 | 7.0/8 / 34.5 / 79 |
| d_short_turns_3spk | - | - | 3.0/8 / 42.9 / 69 | 2.0/8 / 42.1 / 70 | 3.0/8 / 40.8 / 72 | 4.0/8 / 40.5 / 72 |
| e_overlap20_2spk | 3.0/8 / 15.2 / 88 | 6.0/8 / 10.4 / 92 | 8.0/8 / 8.6 / 95 | 8.0/8 / 8.6 / 95 | 8.0/8 / 8.6 / 95 | 8.0/8 / 8.6 / 95 |
| e_overlap50_2spk | - | - | 3.0/8 / 20.9 / 75 | 4.0/8 / 18.3 / 78 | 4.0/8 / 16.4 / 78 | 5.0/8 / 15.0 / 73 |
| f_same_gender_2spk | - | - | 4.0/8 / 13.0 / 92 | 6.0/8 / 9.7 / 95 | 7.0/8 / 9.6 / 95 | 8.0/8 / 12.5 / 91 |
| h_single_speaker | 8.0/8 / 7.9 / 100 | 8.0/8 / 7.9 / 100 | 8.0/8 / 7.9 / 100 | 8.0/8 / 7.9 / 100 | 8.0/8 / 7.9 / 100 | 8.0/8 / 7.9 / 100 |

### Min-duration sweep (threshold 0.85): right-count / mean DER % / seg acc GT+-0.5 s (app)

| scenario | on 0.1 / off 0.3 | on 0.3 / off 0.5 | on 0.3 / off 1 | on 0.5 / off 0.5 |
|---|---|---|---|---|
| a_2spk_clean | 5.0/8 / 19.7 / 89 | 5.0/8 / 18.7 / 89 | 5.0/8 / 17.1 / 89 | 5.0/8 / 18.7 / 89 |
| a_3spk_clean | - | 7.0/8 / 11.7 / 96 | - | - |
| b_4spk_clean | 5.0/8 / 15.1 / 94 | 5.0/8 / 13.8 / 94 | 5.0/8 / 12.2 / 94 | 6.0/8 / 13.7 / 94 |
| b_5spk_clean | - | 6.0/8 / 10.7 / 98 | - | - |
| b_6spk_clean | - | 3.0/8 / 11.9 / 96 | - | - |
| c_pink_snr10_2spk | 4.0/8 / 14.7 / 94 | 4.0/8 / 12.9 / 94 | 4.0/8 / 11.5 / 94 | 4.0/8 / 12.9 / 94 |
| c_pink_snr5_2spk | - | 7.0/8 / 10.1 / 97 | - | - |
| c_white_snr10_3spk | - | 5.0/8 / 17.9 / 90 | - | - |
| d_short_turns_2spk | 5.0/8 / 36.4 / 76 | 5.0/8 / 38.0 / 76 | 5.0/8 / 45.2 / 74 | 5.0/8 / 37.9 / 76 |
| d_short_turns_3spk | - | 2.0/8 / 42.1 / 70 | - | - |
| e_overlap20_2spk | 8.0/8 / 9.6 / 95 | 8.0/8 / 8.6 / 95 | 8.0/8 / 7.2 / 97 | 8.0/8 / 8.5 / 95 |
| e_overlap50_2spk | - | 4.0/8 / 18.3 / 78 | - | - |
| f_same_gender_2spk | - | 6.0/8 / 9.7 / 95 | - | - |
| h_single_speaker | 8.0/8 / 8.6 / 100 | 8.0/8 / 7.9 / 100 | 8.0/8 / 8.5 / 100 | 8.0/8 / 8.0 / 100 |

### Pooled threshold table

scenarios pooled: a_2spk_clean, a_3spk_clean, b_4spk_clean, b_5spk_clean, b_6spk_clean, c_pink_snr10_2spk, c_pink_snr5_2spk, c_white_snr10_3spk, d_short_turns_2spk, d_short_turns_3spk, e_overlap20_2spk, e_overlap50_2spk, f_same_gender_2spk

| threshold | right count | over | under | mean DER % | seg acc GT+-0.5 s |
|---|---|---|---|---|---|
| 0.8 | 58.0/104 | 44 | 2 | 18.4 | 88.5 |
| 0.85 | 67.0/104 | 32 | 5 | 17.3 | 89.9 |
| 0.9 | 76.0/104 | 21 | 7 | 16.8 | 90.0 |
| 0.95 | 81.0/104 | 14 | 9 | 16.8 | 89.3 |

Single speaker false-split (est > 1) by threshold:

| threshold | h_single_speaker | h_single_pink_snr10 |
|---|---|---|
| 0.6 | 0/8 | 0/0 |
| 0.7 | 0/8 | 0/0 |
| 0.8 | 0/8 | 0/0 |
| 0.85 | 0/8 | 0/8 |
| 0.9 | 0/8 | 0/0 |
| 0.95 | 0/8 | 0/0 |
