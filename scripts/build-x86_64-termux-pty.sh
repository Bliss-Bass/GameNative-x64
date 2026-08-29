#!/usr/bin/env bash
# Build libtermux.so (the terminal's PTY helper) for x86_64 and stage it into
# modernX64 jniLibs.
#
# Backs com.termux.terminal.JNI in the vendored Termux terminal emulator: it opens the
# pty, forks the shell and reports window size changes. Shipped as a prebuilt because the
# release packaging flow does not run externalNativeBuild.
#
# Only x86_64: the Linux userland this terminal talks to is x86_64-only, and the arm
# flavors keep their existing prebuilts.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC="$ROOT/app/src/main/cpp/termux-pty"
BUILD_DIR="$ROOT/native/termux-pty-x86_64"
JNI_OUT="$ROOT/app/src/modernX64/jniLibs/x86_64"

ABI=x86_64
API=26

NDK_ROOT="${NDK_ROOT:-${ANDROID_NDK_HOME:-${ANDROID_NDK:-}}}"
if [[ -z "$NDK_ROOT" ]]; then
    for candidate in \
        "${ANDROID_HOME:-}/ndk/27.3.13750724" \
        "${ANDROID_HOME:-}/ndk/27.0.12077973"; do
        [[ -d "$candidate" ]] && NDK_ROOT="$candidate" && break
    done
fi
[[ -d "$NDK_ROOT" ]] || { echo "NDK not found (set NDK_ROOT)" >&2; exit 1; }

TOOLCHAIN_FILE="$NDK_ROOT/build/cmake/android.toolchain.cmake"
[[ -f "$TOOLCHAIN_FILE" ]] || { echo "missing $TOOLCHAIN_FILE" >&2; exit 1; }

echo "==> configuring libtermux ($ABI, API $API)"
rm -rf "$BUILD_DIR"
cmake -S "$SRC" -B "$BUILD_DIR" \
    -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN_FILE" \
    -DANDROID_ABI="$ABI" \
    -DANDROID_PLATFORM="android-$API" \
    -DCMAKE_BUILD_TYPE=Release

echo "==> building"
cmake --build "$BUILD_DIR" -j"$(nproc)"

mkdir -p "$JNI_OUT"
found="$(find "$BUILD_DIR" -name libtermux.so -type f | head -1)"
[[ -n "$found" ]] || { echo "build did not produce libtermux.so" >&2; exit 1; }
install -m 755 "$found" "$JNI_OUT/libtermux.so"

readelf -h "$JNI_OUT/libtermux.so" | grep -qE 'Machine:.*X86-64' \
    || { echo "libtermux.so is not x86_64" >&2; exit 1; }
echo "staged $JNI_OUT/libtermux.so ($(du -h "$JNI_OUT/libtermux.so" | cut -f1))"
