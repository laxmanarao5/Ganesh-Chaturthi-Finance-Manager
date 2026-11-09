---
name: release-build
description: Build and publish a new version of the Android app (APK + version.json for the in-app updater). Asks whether this is a major, minor or patch update, bumps the version accordingly, and runs every step in Docker. Use when the user asks to release, publish, ship, build a new version, or bump the app version.
---

# Release a new version of Vinayaka Finance

Read `CLAUDE.md` at the repo root first if you have not. Everything runs in Docker from the `android/` folder; install nothing on the machine.
The repo is **public**, so pushing is outward-facing: do the local steps freely, but ask before `git push`.

## 0. Setup (every session)
```bash
export PATH="$PATH:/c/Program Files/Docker/Docker/resources/bin"   # Windows host: makes `docker` available
cd <repo>/android
docker image inspect vinayaka-android-build >/dev/null 2>&1 || docker compose build
```
Run builds with `run_in_background` and wait for the completion notification; do not poll. Stop only your own `android-android-run-*` container if you must cancel.

## 1. Ask what kind of update this is
Use AskUserQuestion with one question, header "Update type", three options:
* **Minor (Recommended)**: new features or visible improvements, e.g. 1.1.0 -> 1.2.0
* **Major**: big or breaking change, e.g. 1.4.2 -> 2.0.0
* **Patch**: bug fixes only, e.g. 1.4.2 -> 1.4.3

Read `android/version.properties` first and show the current version and what each choice would produce.
Also ask (or draft from `git log` since the last release and confirm) 2 to 5 plain-language "What's new" lines.

## 2. Check the tree
* `git status --short`: tell the user about uncommitted source changes; they will be part of this release.
* Make sure `android/secrets.properties` and `android/keystore/` exist (they are git-ignored). If the keystore is missing, **stop**: a new key would break Google Sign-In and in-place updates. Ask the owner.

## 3. Bump the version (in Docker)
```bash
docker compose run --rm android sh bump-version.sh <major|minor|patch>
```
It rewrites `android/version.properties` (`versionCode` +1 always, `versionName` per the kind) and prints `Version old (code) -> new (code)`.
Write the confirmed "What's new" lines into `android/releases/notes.txt` (one line per item, replacing the old text).

## 4. Test and build (in Docker)
```bash
docker compose run --rm android ./gradlew testDebugUnitTest publishApk --console=plain -q
```
This runs all unit tests, builds the debug APK signed with the stable key, and writes `android/releases/vinayaka-finance.apk` and `android/releases/version.json`.
If a test or the build fails, fix the cause (do not skip tests) and rerun. Do not leave a half-bumped version: if you give up, restore `version.properties` and `releases/notes.txt` with git.

## 5. Verify the output
* `cat android/releases/version.json`: `versionCode` and `versionName` must equal `version.properties`; `sha256` and `size` must be present.
* Compare `sha256sum android/releases/vinayaka-finance.apk` with the `sha256` field.
* Count tests: `grep -ho 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*"' android/app/build/test-results/testDebugUnitTest/*.xml` must show 0 failures.
* Copy the APK for local use too: `cp android/releases/vinayaka-finance.apk android/dist/vinayaka-finance-debug.apk` (create `dist/` if needed; it is git-ignored).

## 6. Commit
Stage only: `android/version.properties`, `android/releases/` (apk, version.json, notes.txt), and any source/README changes that belong to this release.
Message: `Release <versionName>: <one-line summary>`, ending with the co-author trailer from the session. Never stage `secrets.properties`, `keystore/`, `dist/` or `build/`.

## 7. Push only with the owner's OK
Ask "Push release <versionName> to GitHub?". On yes: `git push origin master`, wait ~10 seconds, then verify what phones will see:
```bash
curl -sL https://raw.githubusercontent.com/laxmanarao5/Ganesh-Chaturthi-Finance-Manager/master/android/releases/version.json
```
The `versionCode` must be the new one. (GitHub caches raw files about 5 minutes, so it may lag briefly.) Phones then offer the update on next launch, or via More > App version.

## 8. Report
Say: old -> new version, test count, APK size, whether it was pushed, and that nothing was run on a phone (unless it was). Update the "State and open items" part of `CLAUDE.md` if something material changed.
