#!/usr/bin/env bash
# Build open-source GameNative JNI prebuilts for x86_64 (Bliss ax86 port).
#
# Usage (from GameNative repo root):
#   ./scripts/build-x86_64-jni.sh
#   NDK_ROOT=... ABI=x86_64 ./scripts/build-x86_64-jni.sh
#
# Outputs to app/src/modernX64/jniLibs/x86_64/

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ABI="${ABI:-x86_64}"
API="${API:-29}"
BUILD_TYPE="${BUILD_TYPE:-Release}"
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

if [[ ! -d "$NDK_ROOT" ]]; then
    echo "Android NDK not found. Set NDK_ROOT or install NDK 27.x via sdkmanager." >&2
    exit 1
fi

OUT_DIR="${ROOT}/app/src/modernX64/jniLibs/${ABI}"
BUILD_DIR="${ROOT}/app/.cxx-bliss-x64/${ABI}"
TOOLCHAIN="${NDK_ROOT}/build/cmake/android.toolchain.cmake"

echo "== build-x86_64-jni =="
echo "NDK:        ${NDK_ROOT}"
echo "ABI:        ${ABI}"
echo "Build dir:  ${BUILD_DIR}"
echo "Output dir: ${OUT_DIR}"
echo

mkdir -p "$OUT_DIR" "$BUILD_DIR"

cmake -S "${ROOT}/app/src/main/cpp/bliss-x64" -B "$BUILD_DIR" \
    -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN" \
    -DANDROID_ABI="$ABI" \
    -DANDROID_PLATFORM="android-${API}" \
    -DCMAKE_BUILD_TYPE="$BUILD_TYPE"

cmake --build "$BUILD_DIR" -j"$(nproc)"

bash "$(dirname "$0")/stage-x86_64-jni.sh"

echo
echo "Open-source JNI staged. Still required from upstream (ARM-only proprietary prebuilts):"
echo "  libredirect-bionic-wx.so, libhook_impl.so, libmain_hook.so, libwinlator_11.so"
echo "  libevshim.so (needs SDL2 headers + build), libsteambootstrap.so, pulseaudio stack"
echo "  libproot.so / libproot-loader.so (proot arch.h needs x86_64 port for legacy path)"
