#!/usr/bin/env bash
# Builds the trampoline dex that every generated stub APK shares.
#
# The dex is a build artefact, not something generated per application: a stub differs from its
# neighbours only in its manifest and its icon, so this is compiled once and shipped as an asset
# for StubApk to package.
#
# Usage: build-dex.sh <out-dir>   (needs javac, an Android SDK with build-tools, and android.jar)
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
out="${1:-$here/out}"

: "${ANDROID_JAR:=${ANDROID_HOME:-$HOME/Android/Sdk}/platforms/android-34/android.jar}"
: "${BUILD_TOOLS:=${ANDROID_HOME:-$HOME/Android/Sdk}/build-tools/35.0.0}"

[ -f "$ANDROID_JAR" ] || { echo "android.jar not found: $ANDROID_JAR" >&2; exit 1; }
[ -x "$BUILD_TOOLS/d8" ] || { echo "d8 not found: $BUILD_TOOLS/d8" >&2; exit 1; }

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

mkdir -p "$out"
javac -source 8 -target 8 -nowarn -bootclasspath "$ANDROID_JAR" \
  -d "$work/classes" "$here/LaunchActivity.java"
"$BUILD_TOOLS/d8" --min-api 26 --output "$out" --lib "$ANDROID_JAR" \
  $(find "$work/classes" -name '*.class')

echo "wrote $out/classes.dex"
