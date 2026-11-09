---
name: build-debug
description: Run the Android unit tests and build a debug APK in Docker without changing the app version or publishing anything. Use to check that a change compiles and passes tests, or to get a local APK to try on a phone.
---

# Test and build the Android app (Docker only)

Read `CLAUDE.md` at the repo root first if you have not. Never install JDK, Android SDK or Gradle on the machine; the build image does it.

```bash
export PATH="$PATH:/c/Program Files/Docker/Docker/resources/bin"   # Windows host: makes `docker` available
cd <repo>/android
docker image inspect vinayaka-android-build >/dev/null 2>&1 || docker compose build
docker compose run --rm android ./gradlew testDebugUnitTest assembleDebug --console=plain -q > /tmp/build.log 2>&1; echo "exit $?"
```
* Run it with `run_in_background` and wait for the completion notification; do not poll or pipe it through `tail`/`grep` while it runs.
* To cancel, stop only your own container: `docker ps` then `docker stop <android-android-run-...>`; the machine runs unrelated containers that must stay up.
* On failure: `grep -nE "^e: |error:|FAILED|What went wrong" -A4 /tmp/build.log`.
* Test results: `grep -ho 'testsuite name="[^"]*" tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*"' android/app/build/test-results/testDebugUnitTest/*.xml`.
* The APK is `android/app/build/outputs/apk/debug/app-debug.apk`; copy it to `android/dist/vinayaka-finance-debug.apk` (git-ignored) for the owner.

This does **not** bump the version or touch `android/releases/`. To publish a new version use the `release-build` skill.
Report honestly: say it compiled and which tests passed, and that nothing was run on a phone unless it was.
