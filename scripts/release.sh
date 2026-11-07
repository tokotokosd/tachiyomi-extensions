#!/usr/bin/env bash
# Build, test, sign and publish the ReManga extension into this repo.
#
#   scripts/release.sh          build + update repo/ (no git)
#   scripts/release.sh --push   also commit and push
#
# Before running: bump extVersionCode in src/ru/remanga/build.gradle.
# Needs signingkey.jks and signing.env (KEY_STORE_PASSWORD, ALIAS, KEY_PASSWORD)
# in the repo root; both are gitignored. In CI the same variables come from secrets.
set -euo pipefail

cd "$(dirname "$0")/.."
ROOT=$(pwd)
EXPECTED_CERT="4a16e59bfea936a7b733a1592abbb2cdf54028e03db51922e803a7962441b39a"
MODULE=":src:ru:remanga"
OUT="src/ru/remanga/build/outputs/apk/release"

if [[ -z "${JAVA_HOME:-}" && -d /opt/homebrew/opt/openjdk@17 ]]; then
    export JAVA_HOME=/opt/homebrew/opt/openjdk@17
fi
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
if [[ -f signing.env ]]; then
    # shellcheck disable=SC1091
    source signing.env
fi
: "${KEY_STORE_PASSWORD:?missing (signing.env or CI secret)}"
: "${ALIAS:?missing}"
: "${KEY_PASSWORD:?missing}"
[[ -f signingkey.jks ]] || { echo "signingkey.jks not found in $ROOT" >&2; exit 1; }

rm -rf "$OUT"
./gradlew --no-daemon "$MODULE:testReleaseUnitTest" "$MODULE:assembleRelease"

APK=$(ls "$OUT"/*.apk)
APKSIGNER=$(ls "$ANDROID_HOME"/build-tools/*/apksigner | sort | tail -1)
CERT=$("$APKSIGNER" verify --print-certs "$APK" | awk -F': ' '/certificate SHA-256/{print $2}')
if [[ "$CERT" != "$EXPECTED_CERT" ]]; then
    echo "APK is signed with $CERT, expected $EXPECTED_CERT. Installed copies would refuse the update." >&2
    exit 1
fi

python3 scripts/update_index.py "$APK"

if [[ "${1:-}" == "--push" ]]; then
    VERSION=$(python3 -c "import json;print(next(e['version'] for e in json.load(open('repo/index.min.json')) if e['pkg'].endswith('.ru.remanga')))")
    git add repo src
    git commit -m "ReManga $VERSION"
    git push
fi
