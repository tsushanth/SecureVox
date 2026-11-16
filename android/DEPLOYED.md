# Deployed to Google Play

| Date | Version | versionCode | Track state | main SHA | AAB sha256 |
|---|---|---|---|---|---|
| 2026-11-16 | 1.6.0 | 42 | production inProgress 20% (1.5.6 / 41 stays completed) | b2620e2 (tag play/1.6.0-42) | 88b0b0dea9f656f0ed43f879d50bfd1f6515e605c2c44edbd442a0177f3827a8 |

1.6.0: 26 app languages, translated FAQ/Read Aloud/settings, Custom Dictionary terms written as typed (and passed to the speech model as a hint), locale-safe subtitle exports, RTL arrows.
Built from main with `bundleRelease`; unit tests pass (368); lint could not complete (the Compose lint checks crash the Kotlin analysis in MainActivity.kt, which this release did not change). Rollback: halt the rollout, or re-upload versionCode 41 as the completed release.
