# Vinayaka Finance: web portal

**Live at https://laxmanarao5.github.io/Ganesh-Chaturthi-Finance-Manager/** (sign in with your committee Google account).

The Android app's screens and rules in a browser, for phones and desktops. Sign in once with Google; **there is no sync**: every change you save is
written straight to the committee's Google Sheet, and the Sheet is read fresh when you open a screen. It is a static site (React, Vite, TypeScript), so there is no server and nothing to pay for.

| Screen | What it does |
|---|---|
| Home | Cash in hand, collected / spent / still to collect, where the money came from and went, latest records |
| Donations, Spent, Other income | Add, edit, delete (with confirmation and Undo), search, filter by month, category and collector, "Not collected" |
| People | Collected vs spent per person |
| Contacts | The people to call, a "called" tick for each year, call link |
| Reports | Day-wise totals, CSV download (records, day-wise, by person) |
| Import | CSV files (the committee's lists, or a file exported from the app or portal); importing twice adds nothing |
| Users (admins) | Who may use it (Viewer / Editor / Admin) and who the Sheet is shared with, through the Drive API |

Only emails on the Sheet's `Users` tab get in, with the same roles as the app. **Gifts in kind (amount 0) are never "not collected".**

## How it stays in step with the app
Both read and write the same Sheet in the same layout. The portal adds a row, or rewrites one row with `rev + 1`, a new hash and `updated_by`, and deleting keeps the row as a
tombstone, so phones pick the change up on their next Sync exactly as if another phone had made it. Before changing a record the portal re-reads it and refuses if someone changed it meanwhile.

## Build, test, run (Docker only, nothing is installed on the machine)
Run from this folder. On this Windows host first: `export PATH="$PATH:/c/Program Files/Docker/Docker/resources/bin"`.
```bash
docker compose build                                            # once
docker compose run --rm portal npm install                      # once
docker compose run --rm portal npm test                         # 64 tests
docker compose run --rm portal npm run build                    # writes dist/
docker compose run --rm --service-ports portal npm run dev      # http://localhost:5173
```
The Sheet link and Google client id come from `../android/secrets.properties` (`SPREADSHEET_URL`, `GOOGLE_WEB_CLIENT_ID`), the same file the app uses, or from the environment. Neither is a secret key,
but the build does contain the Sheet id, which is fine only while the Sheet's general access is Restricted.

## Publishing (GitHub Pages)
The build is committed in `../docs/` and served by GitHub Pages (repository Settings, Pages, "Deploy from branch", `master` and `/docs`). Use the `publish-portal` skill, which builds in Docker, copies `dist/` to `docs/`
and asks before pushing. The site is then at `https://laxmanarao5.github.io/Ganesh-Chaturthi-Finance-Manager/`.

## One-time Google Cloud setup
On the existing **Web** OAuth client (APIs & Services, Credentials) add under **Authorized JavaScript origins**:
`https://laxmanarao5.github.io` and, for local testing, `http://localhost:5173`. No client secret is used or needed in the browser. Everyone who signs in must still be a Test user of the consent screen
(same as the app), and the Google Sheets API and Google Drive API must be enabled.

## Layout
`src/core/core.ts` all the logic with no React (state, access, loading, writes, import, users and sharing), `src/lib/` the Sheet and Drive clients plus the pure rules (ports of the app's Kotlin), `src/ui/` the React screens,
`src/tests/` the tests (ported rules, the Google clients against a scripted fake, the whole core against an in-memory Sheet).
