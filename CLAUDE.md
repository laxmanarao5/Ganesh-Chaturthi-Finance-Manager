# Vinayaka Finance: read this first

Any AI agent (or new developer) working in this repo should read this file before touching anything. It records what was built, why,
the decisions the owner made, and the traps already hit. This repo is **public**: never put secrets, real names or phone numbers in it.

## What this is
A committee runs a yearly Ganesh Chaturthi (Vinayaka) festival and tracks donations, expenditure, other income and a phone contact list.
It used to be a web app (`frontend/`) on a Flask + DynamoDB server (`backend/`). The servers became chargeable and were stopped, so the owner
asked for an **Android app with no server and nothing to pay for**: data is kept on the phone, and a **Google Sheet is the source of truth**.
The owner later asked for a **web portal with the same UI and the same single Google sign-in, but no sync: it writes straight to the Sheet** (`frontend/`, a static site, so still nothing to pay for).

**`android/` (the app) and `frontend/` (the web portal) are the live projects.** `backend/` (the stopped Flask server) and `mobile/` (an abandoned React Native scaffold) are kept for reference. Do not extend them.
The old `frontend/` (React app talking to the stopped AWS API) was replaced in full by the portal; it is still in git history (before the "web portal" commit) if ever needed.

## How the owner wants us to work (decisions, not suggestions)
* **Docker for every build tool.** Nothing is installed on the machine (no JDK, Android SDK, Gradle, gh). Use `docker compose run --rm android ...` from `android/`.
  On this Windows host first run: `export PATH="$PATH:/c/Program Files/Docker/Docker/resources/bin"`. Stop only your own `android-android-run-*` container; the host runs unrelated ones.
* **Start from scratch with current tools** was the instruction: new Kotlin / Compose project, the old `mobile/` code was deliberately not reused.
* **The Google Sheet link is fixed at build time** (`SPREADSHEET_URL` in git-ignored `android/secrets.properties`). The app never asks the user for it.
* **Every screen opens on the current year** and has its own year picker (in 2027 it opens on 2027). There is no global year.
* **Manual sync**: nothing is sent until the user taps Sync (or confirms on exit / sign out). "Nothing to sync" when phone and Sheet match.
* **All calculations happen in the app (and the portal).** The Sheet holds plain rows only.
* **A gift in kind (amount 0) is never "not collected"**: `isPending` = income AND no collector AND amount > 0. In-kind rows show "In kind" and never ask for a collector. "Still to collect" ignores them.
* **No swipe to delete.** Deleting is a button in the edit sheet behind a confirmation (with Undo after). Deleted rows stay in the Sheet as tombstones.
* **Ask before anything outward-facing**: pushing, publishing, sharing the Sheet. Commit/push only when the owner says so. The repo is public.
* Deferred by the owner (not built): T-shirts section, cash check. "Remaining I will review and tell."

## What was built, in order
1. **Plan and stack choice.** Compared Google Sheets / Drive / others for a free shared store; chose direct Google Sheets REST API with Google Sign-In (free; Cloud project in Testing mode allows 100 test users).
2. **Toolchain proof** in Docker (Kotlin 2.4, AGP 9.5 with built-in Kotlin, Gradle 9.9, compileSdk 37), then the full app.
3. **Offline-first core**: Room database, generic `SyncRecord` + pure `Merger` + `SyncEngine`; per-record `rev` / `baseRev` / `dirty`, content `hash` to detect hand edits in the Sheet, soft-delete tombstones, newest edit wins on conflict.
4. **Sheet layout**: one ledger tab per year, a single `Contacts` tab with a Year column (a new year shows everyone un-called), a `Users` tab. Amounts are stored in paise in the app, rupees in the Sheet.
5. **Screens** (Compose, Material 3, saffron/maroon): Home, Donations, Spent, People (collected vs spent per person), More (Other income, Contacts, Reports, Sync & data, Import, Users, version/update).
   Categories: Donations = Youth, Villagers, Children, Other villagers, Annadhanam; Expenditure = Pooja, Annadhanam (editable date); Other income = name, description, amount.
   Every record has a collector/payer; blank collector on income = "not collected". Filters by collector / "Not collected" with the total following the filter.
6. **Excel import** of the committee's 2026 workbook (recognised by sheet names; custom xlsx reader; dedupes so importing twice adds nothing). The workbook itself holds real names and numbers and is **not** in the repo.
7. **Access control**: only emails on the Sheet's `Users` tab can use the app. Roles Viewer (look), Editor (edit, no Users page), Admin (Editor + Users page). `lakshmana5296@gmail.com` is the hard-coded default Admin so a blank Sheet can bootstrap.
8. **Sheet sharing through the Drive API**: saving the Users list also shares/changes/removes the Sheet's own Google permissions (Viewer → reader, Editor/Admin → writer). Owner, default admin and the signed-in admin are never touched. Needs Drive API enabled and the restricted `drive` scope, asked only from an admin at first save. Google "Test users" cannot be managed by any API.
9. **Exit and sign-out behaviour**: Back on a main screen asks "Are you sure you want to exit?"; Yes syncs waiting changes, then closes. Sign out also syncs first and wipes the local copy only after success or "Sign out anyway".
10. **Repo publishing**: the debug APK is committed at `android/releases/` and linked from the README's Download badge. **In-app updates** read `releases/version.json` from the public repo (see below).
11. **Skills** in `.claude/skills/` for building and releasing (see "Build and release") and `import-workbook`, which loads the Excel workbook into the Google Sheet:
    it runs the app's own importer + sync engine in Docker (test class `app/src/test/.../tools/ImportWorkbookTool.kt`), dry run first, then a push after a one-time browser
    sign-in using either the existing Web client (add `GOOGLE_WEB_CLIENT_SECRET` to `secrets.properties` and register `http://localhost:8765` as a redirect URI) or a Google "Desktop app" client (`DESKTOP_CLIENT_ID` / `DESKTOP_CLIENT_SECRET`). The workbook and its output stay in git-ignored `android/import-in/` and `import-out/`.
    `IMPORT_MODE=csv` instead writes `Ledger <year>.csv` + `Contacts.csv` that the app's (or the portal's) Import screen reads, with no Google sign-in. Keep the file names: the file name is the sheet name.
12. **Web portal** (`frontend/`): see "Web portal" below. Replaced the old React app.

## Architecture (package `com.vinayaka.finance`, under `android/app/src/main/java/`)
| Area | Where | Notes |
|---|---|---|
| Models | `data/model/Models.kt` | `Entry`, `Contact` (Room entities), `Role`, `AppUser`, `SyncRecord<T>` |
| Local DB | `data/local/Database.kt` | Room v2. **`fallbackToDestructiveMigration(true)` wipes local data on a schema change: write a real migration before changing entities, or unsynced data is lost.** |
| Sync | `data/sync/Merger.kt`, `SyncEngine.kt` | `SheetGateway` interface; fake in-memory gateway for tests and demo |
| Sheets | `data/sheets/` | `GoogleHttp` (OkHttp, token refresh, retries), `GoogleSheetsGateway`, `RowMapper`, `FakeSheetGateway` |
| Access | `data/Access.kt` | `DEFAULT_ADMIN_EMAIL`, `resolve`, `normalized`, `parseRole` |
| Sharing | `data/sharing/` | planner + `DriveSharing` + `FakeSharing` |
| Auth | `data/auth/GoogleAuth.kt` | Credential Manager sign-in; Authorization API tokens (Sheets, and Drive for admins) |
| Updates | `data/update/Updater.kt` | version.json check, SHA-256 verified download, installer intent |
| Settings | `data/Settings.kt` | DataStore `Prefs` (role, theme, my name, last sync, ...) |
| UI | `ui/AppRoot.kt`, `ui/AppViewModel.kt`, `ui/screens/`, `ui/components/`, `ui/theme/` | gate order: sign-in, access check, then `MainScaffold` |
| Logic | `domain/Stats.kt`, `util/Format.kt`, `importer/`, `export/` | totals, rupee formatting, Excel/CSV import, CSV export |

Sheet tabs: ledger tab per year `Date | Type | Category | Name | Description | Amount | Collected / Paid by | Created by` + grey system columns (`id | rev | hash | created_at | updated_by | updated_at | deleted_at`);
`Contacts`: `Name | Number | Who calls | Year | Called | Remarks` + system columns, one row per contact per year; `Users`: `Name | Email | Role`.

## Build and release (everything in Docker, from `android/`)
```bash
export PATH="$PATH:/c/Program Files/Docker/Docker/resources/bin"       # Windows host only
docker compose build                                                    # once: JDK 21 + Android SDK + Gradle image
docker compose run --rm android ./gradlew testDebugUnitTest assembleDebug
docker compose run --rm android sh bump-version.sh minor                # or major / patch; edits version.properties
docker compose run --rm android ./gradlew testDebugUnitTest publishApk  # writes releases/vinayaka-finance.apk + releases/version.json
```
* **Use the skills**: `release-build` (asks major / minor / patch, bumps the version, builds, publishes), `build-debug` (tests + APK, no version change) and `import-workbook` (Excel to Google Sheet). They are in `.claude/skills/`.
* The version lives in `android/version.properties`. `versionCode` is what Android and the updater compare; `versionName` is for people.
* **In-app update**: on launch the app reads `releases/version.json` from the public GitHub repo; if its `versionCode` is higher it offers the update, downloads the APK, checks the SHA-256 and opens Android's installer. Android accepts it only if signed with the **same key**.
* GitHub raw files are cached about 5 minutes; a download that does not match the checksum is reported as "try again in a few minutes".

## Web portal (`frontend/`: React + Vite + TypeScript, static, no server)
Same screens and rules as the app, for phones and desktops (bottom bar on phones, side menu on desktops). **No local copy and no Sync**: every add/edit/delete is written straight to the Google Sheet.
* **Sign-in**: Google Identity Services token flow in the browser, with the same Web client id as the Android app (`GOOGLE_WEB_CLIENT_ID`). Scopes: Sheets (+ `drive`, asked only when an admin saves the Users list). Tokens live in memory only (about an hour, renewed quietly; if that fails a banner says "Sign in again").
* **Same data rules as the app, byte for byte**: `src/lib/rows.ts` is the port of `RowMapper.kt` (columns, serial dates, paise, rev, tombstones), `model.ts` has the FNV-1a hash (tested against the reference vectors; if it differs, phones think portal rows were edited by hand), `stats.ts`, `access.ts`, `sharing.ts`, `importer.ts` and `csv.ts` are ports of their Kotlin twins. **Change a rule in both places, with a test in each.**
* **Conflicts**: before changing a record the portal re-reads it and refuses if its rev or content changed meanwhile ("Someone else changed this record"). Phones merge portal edits on their next Sync by `rev` / newest edit, exactly like edits from another phone.
* **Layout**: `src/core/core.ts` is all logic with no React (state, access, loading, every write, import, users + sharing); `src/lib/` is the Sheet/Drive clients and pure rules; `src/ui/` is React (`screens/`, `components.tsx`, `styles.css`). Demo mode (`core/demo.ts`) runs the whole portal on an in-memory Sheet (`lib/fakeSheets.ts`) as a separate Core, so it can never touch the real Sheet.
* **Config is fixed at build time** (like the APK): `vite.config.ts` reads `SPREADSHEET_URL` and `GOOGLE_WEB_CLIENT_ID` from `android/secrets.properties` (or the environment). The bundle therefore contains the Sheet id; acceptable only because the Sheet's general access is Restricted.
* **Build, test and run in Docker** (from `frontend/`, Windows: set PATH first as above): `docker compose run --rm portal npm install` (once), `... npm test`, `... npm run build` (output `frontend/dist/`), `docker compose run --rm --service-ports portal npm run dev` (http://localhost:5173; "Try demo" works without Google).
* **Hosting**: GitHub Pages, serving the committed build in `docs/` (Settings, Pages, Deploy from branch, `master` / `/docs`). Use the `publish-portal` skill. Pages is outward-facing: ask the owner before pushing.
* **One-time Google Cloud step (the owner's)**: on the existing Web client add **Authorized JavaScript origins**: `https://laxmanarao5.github.io` and `http://localhost:5173`. No secret is needed in the browser. Test users and the Sheets/Drive APIs are the same as for the app.
* Excel (.xlsx) import is **not** in the portal (CSV only); use the app or the `import-workbook` skill for workbooks.

## Secrets and signing (never commit)
* `android/secrets.properties` (git-ignored): `GOOGLE_WEB_CLIENT_ID`, `SPREADSHEET_URL`. Template: `secrets.properties.sample`. `SPREADSHEET_URL` may also come from the environment.
* `android/keystore/` (git-ignored): stable signing key made by `make-keystore.sh`. Google Sign-In checks its SHA-1 and Android checks it for updates. **Losing it breaks sign-in and in-place updates**; it must be backed up by the owner.
* The published APK **contains the Sheet id** (build config). That is acceptable only because the Sheet's general access is Restricted (the owner confirmed it). Re-check that before publishing if anything changes.
* Google Cloud (set up by the owner): Sheets API + Drive API enabled, consent screen External/Testing with the committee as Test users, an Android OAuth client (package `com.vinayaka.finance` + the keystore SHA-1) and a Web client.

## State and open items
* **Verified**: compiles in Docker; unit tests pass (`SyncTest`, `RowMapperTest`, `StatsTest`, `ImportTest`, `AccessTest`, `SharingTest`, `UpdateTest`).
* **Not verified**: nothing has been run on a real phone or against real Google yet (sign-in, live Sheets/Drive calls, the install flow of an update). Treat the first on-device test as the next milestone and expect small fixes.
* The published build is the **debug** variant (signed with the real key). A minified release variant exists in Gradle but is untested.
* Releases are files in the repo, not GitHub Releases (no `gh` and no token here). Each APK adds about 23 MB to git history.
* Phones still on 1.0 have no updater; they need one manual install.
* **Portal verified**: `npm test` (64 tests: ported rules, the Google/Drive clients against a scripted fake, the whole core against an in-memory Sheet), type-check, production build, and a click-through of demo mode in a browser (add, delete with confirm and Undo, every screen, phone and desktop layouts).
  **Portal not verified**: real Google sign-in, real Sheets/Drive calls from a browser, and the phone-versus-portal merge on a real Sheet. The Authorized JavaScript origins must be added first.
* Phone scanners (e.g. Realme) may warn about the APK because of the install permission used by the in-app updater; it is a false positive. Version history: 1.1.0 updater, 1.2.0 no swipe-to-delete, 1.2.1 gifts in kind are never "not collected".

## Traps already hit
* Gradle Kotlin DSL: `java.security...` resolves to the Java plugin extension; import `java.security.MessageDigest` instead.
* `secrets.getProperty("X", default)` returns `""` when the key exists but is empty, so env fallbacks never ran; use `.trim().ifEmpty { ... }`.
* `keytool -printcert -jarfile` prints nothing for v2-signed APKs; verify with `apksigner verify --print-certs`.
* Do not pipe a long background Docker build through `grep`/`tail` and poll it; wait for the completion notification.
* Compose `Icons.AutoMirrored` variants are needed for `ReceiptLong`, `Logout`, `ExitToApp`, `OpenInNew`.
* Windows git warns "LF will be replaced by CRLF"; harmless, the repo stores LF.
* Tests must not use real names or phone numbers; the import test builds a fake workbook in memory.
* Browser pane screenshots lag or come out blank while the pane is hidden; show it (`show_pane preview`) and prefer DOM checks (`javascript_tool`, `get_page_text`). Emulated viewports apply media queries correctly even when an early screenshot looks wrong.
* `git rm -r frontend` stages deletions: unstage (`git reset HEAD -- frontend`) before committing an unrelated release, and commit with explicit paths (`git commit -- <paths>`) so portal work is not swept into an APK release.
* Google Identity Services pop-ups are blocked unless started from a click: ask for the Drive scope first in the Save handler, before any network call.

## Conventions
* Kotlin, Compose, no XML layouts. Money is `Long` paise; format with `formatRupees`.
* New sync-able record types implement `SyncRecord<T>` and get a gateway read/write pair; keep `Merger` pure and tested.
* Keep `README.md` (repo root) and `android/README.md` in step: the root one is a copy plus a repo-layout table.
* Add or update a unit test with each behaviour change; run them in Docker. A rule change in the app is a rule change in the portal too (see "Web portal").
* Commit messages end with the co-author trailer given by the session; do not push without the owner's go-ahead.
