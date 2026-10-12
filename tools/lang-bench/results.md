# Results, 2026-10-12

SecureVox's shipped ggml Tiny / Base / Small, FLEURS test split, 25 utterances (4-25 s, 3.9-5.7 minutes) per
language, language forced to the code, greedy, 4 threads. Error rate % (WER; CER for zh, ja, ko), lower is better,
95% bootstrap interval in brackets. `zh*` = same output after Traditional to Simplified conversion (references are
Simplified; Whisper often writes Traditional, which is not a real recognition error for a Chinese reader).

| lang | metric | Tiny (default) | Base | Small | words/chars | Tiers T / B / S |
|---|---|---|---|---|---|---|
| en | WER | 14.0 [10-19] | 12.0 [8-17] | 7.4 [5-10] | 542 | G / G / G |
| es | WER | 18.2 [14-23] | 10.4 [8-13] | 5.6 [4-8] | 626 | F / G / G |
| fr | WER | 42.7 [33-53] | 31.1 [25-38] | 13.1 [10-17] | 689 | P / P / G |
| de | WER | 29.6 [24-36] | 20.3 [16-26] | 9.7 [7-13] | 517 | F / F / G |
| it | WER | 27.4 [22-33] | 20.0 [16-25] | 9.8 [6-14] | 624 | F / F / G |
| pt | WER | 24.8 [19-30] | 15.8 [11-21] | 5.9 [4-8] | 665 | F / F / G |
| nl | WER | 61.9 [55-70] | 42.1 [36-48] | 19.8 [15-26] | 530 | P / P / F |
| pl | WER | 54.0 [45-63] | 40.8 [32-51] | 16.4 [12-21] | 409 | P / P / F |
| ru | WER | 37.4 [30-44] | 23.5 [19-29] | 13.6 [10-18] | 455 | P / F / G |
| zh | CER | 39.8 [34-45] | 34.5 [28-41] | 20.7 [15-28] | 827 | P / P / F |
| zh* | CER | 28.9 [24-34] | 22.1 [17-27] | 16.4 [12-22] | 827 | |
| ja | CER | 38.8 [32-45] | 23.1 [20-27] | 12.3 [9-16] | 1232 | P / F / G |
| ko | CER | 21.6 [18-26] | 10.6 [8-13] | 5.5 [4-8] | 1099 | F / G / G |
| ar | WER | 63.4 [57-70] | 49.0 [42-56] | 28.6 [22-35] | 500 | P / P / F |
| hi | WER | 106.4 [103-110] | 102.7 [98-107] | 54.3 [49-62] | 691 | P / P / P |

Tiers (thresholds on the point estimate, used in code): GOOD (G) <= 15, FAIR (F) <= 30, POOR (P) > 30. These are
judgment calls, not standards. Intervals are wide at n=25 (typically +/-4 to 10 points), so neighbouring cells
(for example pt base 15.8 vs the 15 line) are not distinguishable; the large gaps are.

## Language auto-detection (first 10 utterances per language, `-l auto`)

Tiny: wrong on nl 2/10 (as German), pl 1/10 (as Hungarian), ar 1/10 (as Armenian), hi 1/10 (as Sinhala); correct on all
others. Base: 140/140. Small: 139/140 (one Hindi clip as Urdu). Small sample, but Tiny's auto mode is the weak spot.

## Real-time factor (Mac M2 Pro, 4 threads, median utterance; a rough proxy only)

Tiny 0.04-0.08, Base 0.08-0.16, Small 0.19-0.59. The machine was heavily loaded by other jobs during the Small run (load
average 50-65 on 10 cores) and a few utterances stalled by 100x, so these are noisy; per-model ratio Small/Base is roughly
3x, matching ClearVoice's note. A phone is several times slower than this Mac. Not measured on a phone.

## Reading

* Usable (GOOD) with Tiny: English only (14%). Spanish and Korean are FAIR on Tiny and GOOD on Base.
* Tiny is poor or marginal for almost everything else: fr 43, nl 62, pl 54, ru 37, zh/ja 39, ar 63, hi 106. The default
  model the app starts with is therefore unsuitable for most of the 14 non-English languages it lists.
* Base is not enough for fr (31), nl (42), pl (41), zh (35), ar (49), and only FAIR for de, it, pt, ru, ja (16-24).
  Base is GOOD for en, es, ko.
* Small is GOOD for en, es, fr, de, it, pt, ru, ja, ko (5-14%), FAIR for nl, pl, zh, ar (16-29%).
* Hindi is unusable on every model. Tiny emits romanized Hindi or English translation-like text, Base writes Urdu
  script (a real script mismatch; auto-detect on Small even labelled one clip Urdu), and Small gets the right script but
  still 54% WER with garbled words. Do not promise Hindi.
* Chinese: Base/Small often output Traditional characters, hurting the CER by 6-12 points against Simplified
  references (zh vs zh*). Tiny Chinese is poor either way.
* Caveat: FLEURS is clean read speech, and Whisper's training may include some of it. Real phone recordings with noise
  and overlapping speech will be worse. Those numbers are an optimistic bound. n=25 per language, one pass.

## Suggested store wording (not applied; the store-listing agent owns fastlane/metadata)

"Works fully offline. Best accuracy in English, Spanish, French, German, Italian, Portuguese, Russian, Japanese and
Korean with the Small model (download once, about 488 MB). Dutch, Polish, Chinese and Arabic are supported with
reduced accuracy. Hindi is experimental." Avoid claiming "99 languages" or equal quality across the 15 listed.
