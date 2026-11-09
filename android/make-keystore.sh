#!/bin/sh
# Creates a signing key once (inside Docker) and prints its SHA-1 for Google Cloud.
# Passwords are random and stored only in keystore/keystore.properties (git-ignored).
set -e
mkdir -p keystore
if [ ! -f keystore/vinayaka.keystore ]; then
  PASS=$(head -c 24 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 20)
  keytool -genkeypair -v -keystore keystore/vinayaka.keystore -alias vinayaka \
    -keyalg RSA -keysize 2048 -validity 10000 -storepass "$PASS" -keypass "$PASS" \
    -dname "CN=Vinayaka Finance, O=Committee, C=IN" > /dev/null 2>&1
  printf 'storeFile=keystore/vinayaka.keystore\nstorePassword=%s\nkeyAlias=vinayaka\nkeyPassword=%s\n' "$PASS" "$PASS" > keystore/keystore.properties
  echo "Created keystore/vinayaka.keystore. Back this folder up."
fi
PASS=$(grep '^storePassword=' keystore/keystore.properties | cut -d= -f2)
echo "SHA-1 to register in Google Cloud (Android OAuth client):"
keytool -list -v -keystore keystore/vinayaka.keystore -alias vinayaka -storepass "$PASS" 2>/dev/null | grep 'SHA1:' | sed 's/^ *//'
