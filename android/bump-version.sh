#!/bin/sh
# Raises the app version in version.properties.
#   sh bump-version.sh major    1.4.2 -> 2.0.0
#   sh bump-version.sh minor    1.4.2 -> 1.5.0
#   sh bump-version.sh patch    1.4.2 -> 1.4.3
# versionCode always goes up by one (Android and the in-app updater compare it, not the name).
# Run it inside Docker like every other build step:  docker compose run --rm android sh bump-version.sh minor
set -eu
cd "$(dirname "$0")"
kind="${1:-}"
case "$kind" in major|minor|patch) ;; *) echo "usage: sh bump-version.sh major|minor|patch" >&2; exit 2 ;; esac

code=$(sed -n 's/^versionCode=//p' version.properties | tr -d '\r ')
name=$(sed -n 's/^versionName=//p' version.properties | tr -d '\r ')
major=${name%%.*}; rest=${name#*.}; minor=${rest%%.*}; patch=${rest#*.}
case "$code$major$minor$patch" in *[!0-9]*|"") echo "version.properties is not in the form versionCode=N / versionName=X.Y.Z" >&2; exit 1 ;; esac

case "$kind" in
  major) major=$((major + 1)); minor=0; patch=0 ;;
  minor) minor=$((minor + 1)); patch=0 ;;
  patch) patch=$((patch + 1)) ;;
esac
new_code=$((code + 1))
new_name="$major.$minor.$patch"
printf 'versionCode=%s\nversionName=%s\n' "$new_code" "$new_name" > version.properties
echo "Version $name ($code) -> $new_name ($new_code)"
