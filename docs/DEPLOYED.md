# What is deployed

securevox.app is a Cloudflare Pages project `securevox` (direct upload, not Git-connected). Deploy = `wrangler pages deploy <dir> --project-name securevox --branch main` from a clean checkout of `origin/main`.

Do not deploy `docs/` as is: it also holds internal working files (`play-data-safety*.csv`, `consent-copy.md`, this file) that are NOT on the live site (securevox.app/play-data-safety.csv is 404). Stage a copy of only the site files first:
`tar cf - --exclude='*.csv' --exclude='*.md' -C docs . | tar xf - -C <stage dir>`.

| Component | Project | Deployment | Built from | Date |
|---|---|---|---|---|
| Site (`docs/` minus the `.csv` and `.md` working files) | Cloudflare Pages `securevox` | `32771027` (https://32771027.securevox.pages.dev) | `origin/main` at `5b666cb` (merge of #25) | 2026-11-18 (UTC) |

## 2026-11-17 Site: install-referrer tag on the Play link (#25)
- `docs/index.html`: Play link carries `referrer=utm_source=securevox_web&utm_medium=web&utm_campaign=download`.
- Only `index.html` changed in the upload; the other five files were already identical. Verified live: tagged link served at securevox.app; tagged URL returns HTTP 200 on play.google.com.
