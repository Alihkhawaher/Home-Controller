#!/usr/bin/env bash
# Build a signed release APK.
#
#   HC_KEYSTORE=keystore/release.jks HC_KEY_ALIAS=homecontroller HC_STORE_PASSWORD=... bash scripts/release.sh
#
# The keystore and its password never live in the repository: keystore.properties is written for this build only
# and removed afterwards. The APK lands in dist/HomeController-<version>.apk with its SHA-256 next to it.
set -euo pipefail
cd "$(dirname "$0")/.."

: "${HC_KEYSTORE:?set HC_KEYSTORE to the keystore path}"
: "${HC_KEY_ALIAS:?set HC_KEY_ALIAS}"
: "${HC_STORE_PASSWORD:?set HC_STORE_PASSWORD}"

cleanup() { rm -f keystore.properties; }
trap cleanup EXIT
umask 077
{
  echo "storeFile=$HC_KEYSTORE"
  echo "storePassword=$HC_STORE_PASSWORD"
  echo "keyAlias=$HC_KEY_ALIAS"
  echo "keyPassword=${HC_KEY_PASSWORD:-$HC_STORE_PASSWORD}"
} > keystore.properties

./gradlew --console=plain clean :app:testReleaseUnitTest :app:assembleRelease

version="$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' app/build.gradle.kts | head -1)"
mkdir -p dist
out="dist/HomeController-${version}.apk"
cp app/build/outputs/apk/release/app-release.apk "$out"
( cd dist && sha256sum "$(basename "$out")" > "$(basename "$out").sha256" )
echo "built: $out"
cat "$out.sha256"
