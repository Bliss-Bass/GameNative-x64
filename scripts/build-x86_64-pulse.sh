#!/usr/bin/env bash
# Build PulseAudio 13.0 + module-aaudio-sink for Android x86_64 and stage into the modernX64 tree.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PA_DIR="$ROOT/native/pulseaudio-android"
JNI_OUT="$ROOT/app/src/modernX64/jniLibs/x86_64"
ASSET_OUT="$ROOT/app/src/modernX64/assets/pulseaudio-gamenative-x86_64-20260827.tzst"

NDK_ROOT="${NDK_ROOT:-${ANDROID_NDK_HOME:-${ANDROID_NDK:-}}}"
if [[ -z "$NDK_ROOT" ]]; then
    for candidate in \
        "${ANDROID_HOME:-}/ndk/27.0.12077973" \
        "${ANDROID_HOME:-}/ndk/27.3.13750724" \
        "${ANDROID_HOME:-}/ndk/28.0.13004108"; do
        if [[ -d "$candidate" ]]; then
            NDK_ROOT="$candidate"
            break
        fi
    done
fi
[[ -d "$NDK_ROOT" ]] || { echo "NDK not found; set NDK_ROOT" >&2; exit 1; }

export NDK_PATH="$NDK_ROOT"
export ARCH=x86_64

echo "Building PulseAudio stack for x86_64 (NDK=$NDK_ROOT)..."
cd "$PA_DIR"
./build-stack.sh
./build-module.sh

OUT="$PA_DIR/output/x86_64"
mkdir -p "$JNI_OUT"
cp -a "$OUT"/libpulse*.so "$OUT"/libsndfile.so "$OUT"/libltdl.so "$JNI_OUT/"

rm -rf /tmp/pulse-x64-pkg
mkdir -p /tmp/pulse-x64-pkg/modules
cp -a "$OUT/modules/"*.so /tmp/pulse-x64-pkg/modules/
cp -a "$OUT/pactl" /tmp/pulse-x64-pkg/
mkdir -p "$(dirname "$ASSET_OUT")"
tar -I 'zstd -19' -cf "$ASSET_OUT" -C /tmp/pulse-x64-pkg .

echo "Staged jniLibs:"
ls -la "$JNI_OUT"/libpulse* "$JNI_OUT"/libsndfile.so "$JNI_OUT"/libltdl.so
echo "Asset: $ASSET_OUT"
file "$JNI_OUT"/*.so "$ASSET_OUT" /tmp/pulse-x64-pkg/pactl /tmp/pulse-x64-pkg/modules/*.so
