# Vinayaka Finance: read this first

Any AI agent (or new developer) working in this repo should read this file before touching anything. It records what was built, why,
the decisions the owner made, and the traps already hit. This repo is **public**: never put secrets, real names or phone numbers in it.

## What this is
A committee runs a yearly Ganesh Chaturthi (Vinayaka) festival and tracks donations, expenditure, other income and a phone contact list.
It used to be a web app (`frontend/`) on a Flask + DynamoDB server (`backend/`). The servers became chargeable and were stopped, so the owner
asked for an **Android app with no server and nothing to pay for**: data is kept on the phone, and a **Google Sheet is the source of truth**.

**Only `android/` is the live project.** `frontend/`, `backend/` and `mobile/` (an abandoned React Native scaffold) are kept for reference. Do not extend them.

## How the owner wants us to work (decisions, not suggestions)
* **Docker for every build tool.** Nothing is installed on the machine (no JDK, Android SDK, Gradle, gh). Use `docker compose run --rm android ...` from `android/`.
  On this Windows host first run: `export PATH="$PATH:/c/Program Files/Docker/Docker/resources/bin"`. Stop only your own `android-android-run-*` container; the host runs unrelated ones.
* **Start from scratch with current tools** was the instruction: new Kotlin / Compose project, the old `mobile/` code was deliberately not reused.
* **The Google Sheet link is fixed at build time** (`SPREADSHEET_URL` in git-ignored `android/secrets.properties`). The app never asks the user for it.
* **Every screen opens on the current year** and has its own year picker (in 2027 it opens on 2027). There is no global year.
* **Manual sync**: nothing is sent until the user taps Sync (or confirms on exit / sign out). "Nothing to sync" when phone and Sheet match.
* **All calculations happen in the app.** The Sheet holds plain rows only.
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

## Traps already hit
* Gradle Kotlin DSL: `java.security...` resolves to the Java plugin extension; import `java.security.MessageDigest` instead.
* `secrets.getProperty("X", default)` returns `""` when the key exists but is empty, so env fallbacks never ran; use `.trim().ifEmpty { ... }`.
* `keytool -printcert -jarfile` prints nothing for v2-signed APKs; verify with `apksigner verify --print-certs`.
* Do not pipe a long background Docker build through `grep`/`tail` and poll it; wait for the completion notification.
* Compose `Icons.AutoMirrored` variants are needed for `ReceiptLong`, `Logout`, `ExitToApp`, `OpenInNew`.
* Windows git warns "LF will be replaced by CRLF"; harmless, the repo stores LF.
* Tests must not use real names or phone numbers; the import test builds a fake workbook in memory.

## Conventions
* Kotlin, Compose, no XML layouts. Money is `Long` paise; format with `formatRupees`.
* New sync-able record types implement `SyncRecord<T>` and get a gateway read/write pair; keep `Merger` pure and tested.
* Keep `README.md` (repo root) and `android/README.md` in step: the root one is a copy plus a repo-layout table.
* Add or update a unit test with each behaviour change; run them in Docker.
* Commit messages end with the co-author trailer given by the session; do not push without the owner's go-ahead.
