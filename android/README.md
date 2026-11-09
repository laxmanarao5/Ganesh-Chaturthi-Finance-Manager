<div align="center">

# 🪔 Vinayaka Finance

**Festival accounts for the committee, on your phone. Works offline, syncs with one Google Sheet. Free, no server.**

[![Download APK](https://img.shields.io/badge/Download-APK-3DDC84?style=for-the-badge&logo=android&logoColor=white)](https://github.com/laxmanarao5/Ganesh-Chaturthi-Finance-Manager/raw/master/android/releases/vinayaka-finance.apk)
[![Open web portal](https://img.shields.io/badge/Open-Web%20portal-B54708?style=for-the-badge&logo=googlechrome&logoColor=white)](https://laxmanarao5.github.io/Ganesh-Chaturthi-Finance-Manager/)
![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B-3DDC84?style=flat-square&logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-Compose-7F52FF?style=flat-square&logo=kotlin&logoColor=white)

</div>

**Web portal:** open https://laxmanarao5.github.io/Ganesh-Chaturthi-Finance-Manager/ on any phone or computer and sign in with the same Google account (no install).

**Install:** on your phone tap [**Download APK**](https://github.com/laxmanarao5/Ganesh-Chaturthi-Finance-Manager/raw/master/android/releases/vinayaka-finance.apk)
(about 23 MB), open the file and allow "install from this source" when Android asks. Then sign in with your Google account; you can use the app
only if an admin has added your email (see [Who can use the app](#who-can-use-the-app-users)).
The APK in `android/releases/` is replaced each time a new version is built.

---

Offline-first festival accounts for the committee. Records are saved on the phone first and synced, on request,
with one fixed Google Sheet that acts as the source of truth. Several people can use it, each signing in with their
own Google account. No server and nothing to pay for.

Kotlin, Jetpack Compose (Material 3), Room, Google Sign-In (Credential Manager + Authorization API), Sheets REST API.

## What it tracks

* **Donations**: categories Youth, Villagers, Children, Other villagers, Annadhanam. Donor name, details (village, parent...),
  amount, **collected by**, date. A donation with no collector is "not collected yet". Gifts in kind can have amount 0.
* **Expenditure**: categories Pooja, Annadhanam. Item, quantity/notes, amount, **paid by**, date (editable).
* **Other income**: name, description, amount, collected by.
* **People** screen: how much each person collected, how much they spent, and the difference, plus what is still to collect.
* **Filters**: on every list, filter by who collected (or paid), "Not collected", category, month and text. The total at the top
  follows the filter, so choosing your name shows what you collected.
* **Contacts**: people to phone, with a *called* tick **per year**. Opening a new year shows everyone un-called.
* **Every screen opens on the current year** (so in 2027 it opens on 2027) and has its own year picker.
* All totals are calculated in the app. The Sheet only holds plain rows.
* **Back button asks before leaving.** On a main screen, Back shows "Are you sure you want to exit?" instead of dropping to Home. Choosing
  Yes sends any changes that are waiting to the Sheet first (if you are online and may edit), then closes the app. If that sync fails you can
  stay or exit anyway; the changes stay safe on the phone.
* **Sign out syncs first.** Signing out removes this phone's copy, so any waiting changes are sent to the Sheet before anything is deleted.
  If that sync fails, nothing is deleted and you can stay signed in or sign out anyway.
* **No swipe to delete.** A stray swipe cannot remove a record. To delete one, open it, tap **Delete this record** and confirm (an Undo shows right after).

## Build (everything runs in Docker, nothing is installed on your machine)

```bash
cd android
docker compose build                                          # once: JDK 21 + Android SDK + Gradle inside an image
docker compose run --rm android ./gradlew testDebugUnitTest   # unit tests
docker compose run --rm android ./gradlew assembleDebug       # app/build/outputs/apk/debug/app-debug.apk
```

The Gradle cache and Android SDK packages live in Docker volumes, so later builds are fast.

### Build-time settings (`secrets.properties`, git-ignored, see `secrets.properties.sample`)

| Key | Meaning |
|---|---|
| `GOOGLE_WEB_CLIENT_ID` | Web OAuth client ID from Google Cloud |
| `SPREADSHEET_URL` | The committee's Google Sheet link (or just its id). **Fixed in the build; the app never asks for it.** May also be given as the `SPREADSHEET_URL` environment variable. |

### Signing key

Google Sign-In checks the app's signing certificate, so the key must stay the same between builds.

```bash
docker compose run --rm android sh make-keystore.sh    # creates keystore/ (git-ignored) and prints the SHA-1
```

Back up the `keystore/` folder. If you lose it, you must register a new SHA-1 in Google Cloud.

## Updating the app (from this public GitHub repo)

The app updates itself from `android/releases/` in this repo, with no store and no server:

* On launch it quietly reads `releases/version.json` and compares the `versionCode` with its own. If GitHub has a **higher** one, a dialog
  offers **Update now** (with the release notes). **More > App version** checks on demand and says "You're up to date" when it is.
* **Update now** downloads `vinayaka-finance.apk`, checks it against the SHA-256 in `version.json`, and opens Android's installer. The first
  time, Android asks you to allow installs from this app. Your data stays on the phone.
* Android installs it over the current app **only if it is signed with the same key**, so a file from anywhere else cannot replace the app.
  That is why the `keystore/` folder must be kept.
* Phones on version 1.0 do not have this feature: install the new APK by hand once; later updates are in the app.

### Publishing a new version

With Claude Code, run the **`release-build`** skill (in `.claude/skills/`): it asks whether this is a **major**, **minor** or **patch** update,
bumps the version, builds in Docker and publishes. By hand, everything runs in Docker from this folder:

1. `docker compose run --rm android sh bump-version.sh minor` (or `major` / `patch`) edits `version.properties`: `versionCode` always +1,
   `versionName` 1.4.2 becomes 2.0.0 / 1.5.0 / 1.4.3. Edit `releases/notes.txt` (shown as "What's new").
2. `docker compose run --rm android ./gradlew testDebugUnitTest publishApk` runs the tests, builds, and writes
   `releases/vinayaka-finance.apk` and `releases/version.json`.
3. Commit and push `android/version.properties` and `android/releases/`. GitHub's raw files are cached for about 5 minutes, so phones may see it a little later.

The skill **`build-debug`** only runs the tests and builds an APK, without changing the version.
`CLAUDE.md` at the repo root explains the whole project to any new agent or developer.

## Try it without Google

On the first screen choose **Try demo**. It fills the app with sample records and contacts and uses an in-memory "sheet".

## Connect Google (one-time, free)

1. [Google Cloud Console](https://console.cloud.google.com/): create a project (no billing needed) and enable **Google Sheets API**
   and **Google Drive API** (Drive is used only so admins can share the Sheet from inside the app).
2. **Google Auth Platform**: External, add the scopes `.../auth/spreadsheets` and `.../auth/drive`, add the committee's Google accounts as **Test users**.
3. **Clients**: create an **Android** client (package `com.vinayaka.finance`, SHA-1 from `make-keystore.sh`) and a **Web** client; put the
   Web client ID in `secrets.properties`.
4. Put the Sheet's link in `secrets.properties` as `SPREADSHEET_URL` and rebuild.
5. Sharing the Google Sheet with each person is done by the app (see below). Sign in once as the default admin, who should be the Sheet's owner.
6. On the phone: sign in with Google. If the email is allowed (see below), the first sync downloads everything automatically.

## Who can use the app (Users)

Only emails listed on the Sheet's **`Users`** tab (`Name | Email | Role`) can get past the sign-in screen. Roles:

| Role | Can do |
|---|---|
| **Viewer** | Look at everything; cannot add, change or delete anything |
| **Editor** | Everything a Viewer can, plus add and edit records and contacts, and import. Cannot see the Users page |
| **Admin** | Everything an Editor can, plus the **Users** page (More → Users) to add, re-role and remove people |

* `lakshmana5296@gmail.com` is **hard coded as the default Admin** (`DEFAULT_ADMIN_EMAIL` in `data/Access.kt`). It works on a blank Sheet,
  so the first admin can sign in and add everyone else, and it cannot be removed or demoted.
* People can be managed in the app (Admin → More → Users, then **Save to the Sheet**) or by typing rows into the `Users` tab by hand.
  The app creates the tab with a Viewer/Editor/Admin dropdown on first save.
* An email not on the list sees a "No access" screen and keeps no data on the phone. Someone removed from the list is locked out at their next
  sync or access check, and their local copy is wiped.
* **The app also manages the Google Sheet's sharing** (Google Drive API). When an admin saves the list:
  * a new user is shared the Sheet as **Editor** (Editor, Admin roles) or **Viewer** (Viewer role); Google emails them the invite;
  * a changed role updates their Sheet access to match, so nobody keeps edit rights in the Sheet that their app role does not give;
  * a removed user loses access to the Sheet as well as to the app;
  * the owner, the default admin and the signed-in admin are never removed or changed.
  The list is always saved first; if the sharing step fails (for example the account is not allowed to share), a dialog says why and
  **Update the Sheet's sharing for everyone** retries it. The same dialog lists anyone who can open the Sheet but is not on the list, and warns
  if "anyone with the link" access is on (the app never changes those, turn them off in Google Sheets > Share).
* Changing sharing needs Google Drive access, which the app asks for **only from an admin, and only the first time** they save the list.
  Only the Sheet's **owner** (or an editor, if the owner allows editors to share) can share it, so the default admin should own the Sheet.
* Test users in Google Cloud (while the app is in Testing mode) cannot be managed through any API; add each new person there by hand.
  An account that is not a Google account cannot be shared with.
* The Drive scope is a Google "restricted" scope. It works for up to 100 test users. If the app ever goes to production (public),
  Google requires a verification and security review for it.
* Google's sharing is still the real lock on the data: anyone with Editor access to the Sheet could edit it directly.

## How the Sheet is laid out

* **One ledger tab per year** (`2026`, `2027`, ...), created automatically. Columns:
  `Date | Type | Category | Name | Description | Amount | Collected / Paid by | Created by`, then grey system columns
  `id | rev | hash | created_at | updated_by | updated_at | deleted_at` (grouped, collapsible).
  `Type` is `Donation`, `Expenditure` or `Other`. Amounts are in rupees (decimals allowed).
* **`Contacts`** tab: one tab for all years, one row per contact per year:
  `Name | Number | Who calls | Year | Called | Remarks`, then system columns. A year with no row for a contact means not called.
* **`Users`** tab (`Name | Email | Role`, role is `Viewer`, `Editor` or `Admin`): who may use the app. The names also fill the
  collector pickers. People who only collect money and never use the app can simply be typed into a record with "+ New".
* Deleted records stay in their tab, greyed and struck through.
* You may edit or add rows by hand in the Sheet; the next sync notices (via the `hash` column) and adopts them.

## How sync works

Tap the sync button. The app reads each year tab and the Contacts tab and compares them with the phone, record by record:

| Situation | Result |
|---|---|
| Identical everywhere | "Nothing to sync" |
| New or edited on the phone only | sent to the Sheet |
| New or edited in the Sheet only | downloaded |
| Changed in both places | the newest edit wins, listed in the result |
| Deleted on a phone | marked deleted in the Sheet, then on other phones |
| Row removed by hand in the Sheet | removed on the phone |
| Whole tab emptied | the phone puts its rows back (safety net) |

Before writing, the app re-reads the tab; if someone else wrote in between, it recomputes.
"Sync & data > Replace this phone's data with the Sheet" resets a phone.

## Importing the Excel workbook

**More > Import from Excel or CSV.** The committee's workbook is recognised by its sheet names: Youth Donations, Villagers Donations,
Children Donations, Outsiders Donations (becomes *Other villagers*), Annadhanam donations, Pooja Expenditure, Annadhanam Expenditure,
Other inputs, Dhakshina (becomes *Other income*) and contacts. Only each sheet's main table is read, so Total rows and the little
summary tables beside the data are skipped. Rows without a date (the donor lists) get one date you choose. Summary, T-Shirts and
any other sheets are ignored. The preview lists each sheet's row count and total so you can compare with Excel. Importing the same
file twice adds nothing. A CSV exported from the app (Reports) can be imported back.

## Limits

* A record's year is fixed by its date: change the date freely within the year; to move a record to another year, delete it and add it again.
* Sheets API quota is 60 reads and 60 writes per user per minute; a sync uses a handful of calls.
* Identity is the Google account; there are no app passwords.
