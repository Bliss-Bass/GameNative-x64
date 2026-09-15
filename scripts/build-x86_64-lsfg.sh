#!/usr/bin/env bash
# Build and stage the LSFG-VK layer for GameNative x86_64 (bionic / Wine).
#
# Usage:
#   ./scripts/build-x86_64-lsfg.sh
#   ANDROID_NDK=/path/to/ndk ./scripts/build-x86_64-lsfg.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC="$ROOT/app/src/main/cpp/lsfg-vk-android"
NDK="${ANDROID_NDK:-${ANDROID_NDK_ROOT:-${ANDROID_HOME:-$HOME/Android/Sdk}/ndk/27.0.12077973}}"

if [[ ! -d "$SRC" || ! -f "$SRC/scripts/build/android.sh" ]]; then
    echo "error: lsfg-vk-android submodule missing; run:" >&2
    echo "  git submodule update --init app/src/main/cpp/lsfg-vk-android" >&2
    exit 1
fi

export ANDROID_NDK="$NDK"
export ANDROID_ABI=x86_64
export HOST_TAG=linux-x86_64

(
    cd "$SRC"
    git submodule update --init thirdparty/volk thirdparty/dxbc thirdparty/pe-parse thirdparty/toml11
    bash ./scripts/build/android.sh Release
)

DIST="$SRC/build-android-x86_64/dist"
JNI="$ROOT/app/src/modernX64/jniLibs/x86_64"
ASSETS="$ROOT/app/src/main/assets/lsfg_vk/android_x86_64"
mkdir -p "$JNI" "$ASSETS"
cp "$DIST/liblsfg-vk-x86_64.so" "$JNI/liblsfg-vk-layer.so"
# Match the arm64 manifest so LsfgVkManager's install path rewrite stays a no-op.
cp "$ROOT/app/src/main/assets/lsfg_vk/android_arm64_v8a/VkLayer_LS_frame_generation.json" \
    "$ASSETS/VkLayer_LS_frame_generation.json"

echo "Staged:"
echo "  $JNI/liblsfg-vk-layer.so"
echo "  $ASSETS/VkLayer_LS_frame_generation.json"
file "$JNI/liblsfg-vk-layer.so"
