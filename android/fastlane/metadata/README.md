# Play listing mirror

Mirror of the live Play listing (title, short description, full description for every
live locale), edited via API and the listing-experiment harness (variant B titles for
en-US/de-DE/ja-JP/pt-BR are scheduled for 2026-11-15); never upload metadata from here.

- The fastlane lanes in `../Fastfile` skip metadata, image and screenshot uploads. Only
  release notes (`android/<locale>/changelogs/<versionCode>.txt`) are uploaded.
- Do not run `fastlane supply` with metadata enabled, and do not edit these files expecting
  the Play listing to change.
- Mirrored as of 2026-11-12. Promo video URLs are not mirrored.
