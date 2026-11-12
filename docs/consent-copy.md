# Consent copy (English source strings)

Two cards. Both follow the same rules:

- Off until the user says Yes. Not answering is the same as No.
- Two buttons of equal weight: same Compose component, same size, same text style, same colors, side by side, each taking half the width. No filled-versus-text difference, no preselection, no default focus on either, no countdown, no "are you sure" on No.
- No close icon is needed: either button closes the card. Android Back dismisses the card as No for this showing.
- The card says what is sent in one sentence, says audio, text and file names are never sent, and says where to change it.
- Strings are plain text, ASCII only (straight quotes), no vendor names. Escape apostrophes for XML (`\'`).

## 1. Crash-report card (exists since 1.5.4)

Where: Recordings screen, once, after the first finished transcription (unchanged). Setting: Settings, "Anonymous crash reports" switch (unchanged).

Change needed in code (`RecordingsScreen.kt`, `ReportsPromptCard`): replace the filled `Button` ("Turn on") and the `TextButton` ("No thanks") with two `OutlinedButton`s (or two `FilledTonalButton`s) with `Modifier.weight(1f)`, same height. Same change for any other place that renders the pair.

Existing keys keep their names so the 19 translations still apply. Only the body changes (it now says where to change the choice), so only `reports_prompt_body` needs re-translating.

```xml
<string name="reports_prompt_title">Help fix crashes?</string>
<string name="reports_prompt_body">If something breaks, SecureVox can send a short anonymous report: the app version, Android version, phone model, free storage, which feature failed and the type of error. It never sends your audio, text, titles or file names. It is off unless you turn it on, and you can change it any time in Settings.</string>
<string name="reports_prompt_accept">Turn on</string>
<string name="reports_prompt_decline">No thanks</string>
```

Unchanged, for reference (Settings switch):

```xml
<string name="diagnostic_reports">Anonymous crash reports</string>
<string name="diagnostic_reports_subtitle">Off unless you turn it on. When something breaks, sends the app version, phone model and the type of error. Never your recordings, transcripts or file names.</string>
```

## 2. Aggregate step-counter card (future, Tier B1; not built, do not ship before the Worker endpoint and the Data safety row exist)

Where: the setup screen while a speech model is downloading (the user is waiting, the Download button has already been tapped). Separate question and separate stored choice from the crash reports: a Yes to crash reports is not a Yes here. Devices that already finished setup see it once, after their next finished transcription, chained after the crash-report card so at most one card shows.

Shown once. If the download ends or the user leaves without answering, the choice stays Off and the card is not shown again during setup. Settings: new switch "Anonymous usage counts", default Off, next to "Anonymous crash reports". Turning it off clears anything not yet sent.

One sentence of what is counted is in the body: which setup steps are reached. It must stay true to the implementation: only a count per step, the app version and a rough memory size, no install id, no timestamps finer than a day.

```xml
<string name="usage_prompt_title">Help improve SecureVox?</string>
<string name="usage_prompt_body">SecureVox can send anonymous counts of which setup steps people reach, for example "model download finished", including steps you have already done. It never sends your audio, text or file names, and nothing that identifies you or your phone. It is off unless you choose Yes, and you can change it any time in Settings.</string>
<string name="usage_prompt_accept">Yes, share counts</string>
<string name="usage_prompt_decline">No, do not share</string>

<string name="usage_counts">Anonymous usage counts</string>
<string name="usage_counts_subtitle">Off unless you turn it on. Sends counts of which setup steps are reached. Never your audio, text or file names.</string>
```

## 3. Translation

20 locales (English source plus the 19 existing `values-*` folders). Translate later, as one batch. Keep both buttons of a pair the same length in every language so neither looks bigger. Do not translate the quoted example "model download finished" into a longer phrase than the source. Until a locale has the new keys, Android falls back to English, so ship English first only if the owner accepts that for the later step; the crash-report card already exists in all 20 locales.
