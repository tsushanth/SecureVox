# Hindi and Hinglish, 2026-11-28

Same setup as `results.md`: SecureVox's shipped ggml Tiny / Base / Small, whisper.cpp v1.7.2 CLI with the app's
decoder settings (greedy, 4 threads, language `hi`), the same 25 FLEURS `hi_in` test clips (318 s). The only thing that
changes is the initial prompt. Scripts: `hindi_prompts.py` (run), `hindi_phonetic_cer.py` (score), every hypothesis in
`hindi_prompts_raw.json`. Mac only, nothing here touches a phone.

Prompts: none; Devanagari ("नमस्ते, आप कैसे हैं? मैं ठीक हूँ। यह एक हिंदी वाक्य है।"); Roman ("Namaste, aap kaise hain?
Main theek hoon. Yeh ek Hindi vaakya hai.").

| model | prompt | WER vs Devanagari reference | clips written in Devanagari | phonetic CER (Roman-folded) |
|---|---|---|---|---|
| Tiny | none | 106.4 | 0 / 25 | 41.0 |
| Tiny | Devanagari | 113.9 | 8 / 25 | 71.9 |
| Tiny | Roman | 105.6 | 0 / 25 | 35.4 |
| Base | none | 102.7 | 1 / 25 | 39.5 |
| Base | Devanagari | 89.9 | 23 / 25 | 54.5 |
| Base | Roman | 103.8 | 0 / 25 | **21.3** |
| Small | none | 54.1 | 25 / 25 | 19.9 |
| Small | Devanagari | 58.2 | 25 / 25 | 19.0 |
| Small | Roman | 79.6 | 13 / 25 | **16.6** |

WER uses the `score.py` normaliser (NFKC, lower case, punctuation to space). The phonetic CER romanises the reference
and the hypothesis (reference and any Devanagari in a hypothesis go through the app's `RomanHindi` rules, ported to
Python; Roman hypotheses are left as written), folds spelling variants (aa/a, ee/i, ph/f, w/v, aspirates, doubled
letters) and takes the character error rate. It is not a standard metric and is more forgiving than WER; use it to rank
the rows against each other, not to compare with published WER.

## Reading

* Devanagari Hindi stays POOR on every shipped model. Small is the only one that is usable at all (54 WER here, 38 in
  the Whisper paper, appendix Table 13). A Devanagari prompt fixes Base's script (Urdu script to Devanagari) but not
  its accuracy, and does nothing for Small, so the app does not use one.
* Tiny and Base already write Roman Hindi when the prompt is Roman. Base with a Roman prompt reads about as well as
  Small does without it (21.3 against 19.9) at 148 MB instead of 488 MB. Small with the Roman prompt mixes scripts
  (13 of 25 clips in Devanagari); transliterating the Devanagari part gives the best number (16.6).
* Tiny with a Roman prompt drifts into English-like output on some clips ("Greenland was a big game ..."), so Hinglish
  on Tiny stays POOR.
* The app's Hinglish mode therefore decodes with `-l hi` and the Roman prompt, then writes any Devanagari that comes out
  in Roman letters (`RomanHindi`). Tiers in `WhisperLanguage`: Hinglish Tiny POOR, Base FAIR, Small FAIR.
* Caveats: 25 clips of clean read speech (FLEURS), one pass, one speaker set; no phone recordings, no code-mixed
  English-heavy speech, which is what "Hinglish" users record. Treat as indicative.

## Other languages added to the picker

Not re-measured. Tiers for ur, bn, ta, te, mr, id, ms, th, tr, vi, tl, uk, cs, el, fi, hu, ro, sv come from the Whisper
paper (Radford et al. 2022, appendix Table 13, FLEURS WER of Whisper tiny / base / small, thresholds as in
`WhisperModel.kt`): Indonesian 52 / 33 / 16, Malay 51 / 35 / 19, Turkish 43 / 28 / 16, Thai 59 / 38 / 22, Vietnamese 60 /
41 / 21, Ukrainian 51 / 38 / 19, Swedish 53 / 37 / 21, Finnish 60 / 43 / 24, Filipino (Tagalog) 66 / 46 / 28, Romanian
75 / 56 / 30, Greek 67 / 54 / 31, Czech 83 / 66 / 38, Hungarian 84 / 65 / 39, Urdu 65 / 52 / 37, Tamil 100 / 59 / 35,
Marathi 100 / 100 / 60, Bengali 102 / 101 / 104, Telugu 105 / 105 / 98. The paper used beam search with temperature
fallback; the app decodes greedily, and our own Small Hindi run (54) was worse than the paper's (38), so these are
likely a little optimistic.
