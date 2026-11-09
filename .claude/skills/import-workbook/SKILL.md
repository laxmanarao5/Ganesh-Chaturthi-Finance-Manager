---
name: import-workbook
description: Import the committee's Excel workbook (donations, expenditure, other income, contacts) into the Google Sheet so the app shows it under that year (e.g. 2026). Runs the app's own importer in Docker, does a dry run first, then pushes to the Sheet after the owner approves a one-time Google sign-in. Use when the user wants to load, import or push an Excel workbook or a year's data into the Sheet or the app.
---

# Import the Excel workbook into the Google Sheet

Read `CLAUDE.md` at the repo root first if you have not. Everything runs in Docker from `android/`; install nothing on the machine.

## Privacy rules (the repo is public, the workbook has real names and phone numbers)
* The workbook is copied only to `android/import-in/` and output goes to `android/import-out/`. Both are git-ignored. Never copy it anywhere else, never commit it.
* Never print or paste donor names, phone numbers or amounts per person into the chat or a file. The tool's `summary.txt` has only counts and totals; show only that.
* Do not read the workbook cell by cell yourself. The tool reads it.
* Delete `android/import-in/` and `android/import-out/` when finished (see the end).

## What the tool does
`app/src/test/java/com/vinayaka/finance/tools/ImportWorkbookTool.kt` (a JUnit test that only runs when `WORKBOOK` is set) reads the workbook with the app's own `ImportMapper`,
then runs the same steps as the app's Import + Sync through the real `SyncEngine` and `GoogleSheetsGateway`: it first reads what the Sheet already holds, skips rows that are already
there (so running it twice adds nothing), and writes the rest. The year tab (`2026`) and the `Contacts` tab are created and formatted by the same code the app uses, so the app
shows the data after its next Sync. Recognised sheets: Youth / Villagers / Children / Outsiders (becomes Other villagers) / Annadhanam donations, Pooja and Annadhanam expenditure,
Other inputs and Dhakshina (Other income), contacts. Rows with no date get the earliest dated row's date (or `UNDATED_DATE`); the contacts get that date's year.

## 0. Setup
```bash
export PATH="$PATH:/c/Program Files/Docker/Docker/resources/bin"        # Windows host: makes `docker` available
cd <repo>/android
docker image inspect vinayaka-android-build >/dev/null 2>&1 || docker compose build
```
Run long Docker commands with `run_in_background` and wait for the completion notification. Stop only your own `android-android-run-*` container if you must cancel.

## 1. Ask what to import
Use AskUserQuestion if not already clear: the workbook path, the year (default: the current year; the app shows it as that year's tab), and **the date for rows that have no date**
(donor lists have none). The tool's default is the earliest dated row, which is often an early shopping date and not the festival day, so ask. Pass the answer as `-e UNDATED_DATE=YYYY-MM-DD`
in every command below (for the 2026 import the owner chose 2026-09-14). Then:
```bash
mkdir -p import-in && cp "<workbook path>" import-in/workbook.xlsx
```

## 2. Dry run (writes nothing to Google)
```bash
MSYS_NO_PATHCONV=1 docker compose run --rm -e WORKBOOK=/workspace/import-in/workbook.xlsx -e IMPORT_YEAR=<year> -e IMPORT_MODE=dry \
  android ./gradlew testDebugUnitTest --rerun --tests "*ImportWorkbookTool" --console=plain -q
cat import-out/summary.txt
```
Show the user the summary (rows and totals per sheet and category, rows without a date, skipped rows, contacts). Ask them to compare the totals with the Summary sheet in their Excel.
If a sheet they expect is missing or a total differs, stop and investigate `ImportMapper` (do not push wrong data). Rows are "skipped" when they have no valid amount.

## 2b. Alternative: CSV files for the app's own Import screen (no Google sign-in)
Use this when the owner wants to import on the phone, or has no OAuth client. Same command as the dry run with `-e IMPORT_MODE=csv -e UNDATED_DATE=YYYY-MM-DD`; it writes
`import-out/Ledger <year>.csv` (columns Date, Type, Category, Name, Description, Amount, Collected / Paid by; every row dated) and `import-out/Contacts.csv`
(Name, Number, Who calls, Called, Remarks), after reading both back through the app's own CSV reader and mapper and checking counts and totals.
**Keep the file names**: the importer treats the file name as the sheet name (`Contacts` = contacts; any other name = the Type-column layout).
On the phone: More, Import, Choose file, pick `Ledger <year>.csv`, then import again with `Contacts.csv` (the contacts' year follows the date picked on that screen), then Sync.
These files hold real names and numbers: they stay in git-ignored `import-out/`; tell the owner to delete them from the phone and PC afterwards.

## 3. Push to the Google Sheet
Pushing sends the donors' data to the owner's Google Sheet, so first ask: "Write these N rows into the Sheet (tab <year>)?" and wait for a yes.

One-time prerequisite: `android/secrets.properties` (git-ignored) must have `SPREADSHEET_URL` and ONE of these OAuth setups (these keys are used only by this tool and are never built into the APK):
* **Existing Web client (fewest steps):** `GOOGLE_WEB_CLIENT_SECRET=...` (the secret of the client whose ID is already `GOOGLE_WEB_CLIENT_ID`), and in Google Cloud Console, APIs & Services,
  Credentials, that Web client, add `http://localhost:8765` under **Authorized redirect URIs** (use the port you pass as `OAUTH_PORT`). The ID alone is not enough: the secret and the redirect URI are required.
* **Desktop client:** `DESKTOP_CLIENT_ID` and `DESKTOP_CLIENT_SECRET` from a client of type **Desktop app** (same project: APIs & Services, Credentials, Create credentials, OAuth client ID). No redirect setup.
The Google account they approve with must be a Test user of the app and able to edit the Sheet (the default admin, who owns it, is the default).
Never ask the owner to paste the secret into the chat; have them add it to `secrets.properties` themselves.

```bash
rm -f import-out/auth-url.txt
MSYS_NO_PATHCONV=1 docker compose run --rm -p 8765:8765 -e WORKBOOK=/workspace/import-in/workbook.xlsx -e IMPORT_YEAR=<year> -e IMPORT_MODE=push \
  android ./gradlew testDebugUnitTest --rerun --tests "*ImportWorkbookTool" --console=plain -q    # run in the background
```
Wait for `import-out/auth-url.txt` to appear (`until [ -f import-out/auth-url.txt ]; do sleep 3; done` with a timeout of about 3 minutes; the first build may take a while),
give the URL in it to the owner, and ask them to open it in their browser, choose the account and approve. The page then says "Signed in"; the tool carries on by itself and the
background command finishes. Then `cat import-out/summary.txt` and report: rows written, rows already there, and what each tab now holds.

Add `-e GOOGLE_LOGIN_HINT=<email>` to pre-select an account, `-e WHO="<name>"` to change the "Created by" text, `-e UNDATED_DATE=YYYY-MM-DD` to choose the date for undated rows,
`-e OAUTH_PORT=<n>` (and `-p <n>:<n>`) if 8765 is busy.

## 4. Verify
* The summary's "What the Sheet holds now" must show the expected rows and totals per category and the contacts count.
* Ask the owner to open the app and tap Sync: the year should appear with the same totals (Home / Donations / Spent).
* Running step 3 again must report all rows as "Already there and skipped" (nothing new written).

## 5. Clean up
Tell the owner, then remove the personal data: `rm -rf import-in import-out`. Do not commit anything from this skill except changes to the tool itself.

## Troubleshooting
* `Add DESKTOP_CLIENT_ID ...`: the Desktop OAuth client is not in `secrets.properties` yet.
* Google page says `redirect_uri_mismatch` or `invalid_client`: the client is not of type Desktop app, or the ID/secret is wrong.
* "Access blocked: app not verified": the account is not listed as a Test user in Google Cloud.
* HTTP 403 from Sheets: the approving account cannot edit the Sheet, or the Sheets API is not enabled in the project.
* Nothing opens at `localhost:8765`: the Docker command was run without `-p 8765:8765`, or another program uses the port.
