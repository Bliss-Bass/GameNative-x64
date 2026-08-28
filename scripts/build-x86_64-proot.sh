#!/usr/bin/env bash
# Build the *vendored* PRoot fork for x86_64 Android (bionic).
#
# Not used by the Linux-apps feature: this fork was stripped down for Wine and has no
# extension subsystem, so it cannot offer fake root (-0) and apt cannot unpack packages
# under it. Use scripts/build-x86_64-proot-linux.sh instead. Kept so the x86_64 support
# in app/src/main/cpp/proot does not rot, and for any future box86/box64 use.
#
# PRoot gives the Linux-apps feature its unprivileged userland: it runs a glibc
# rootfs under ptrace, without root or a kernel namespace.
#
# Native libraries in this project ship as prebuilts under app/src/*/jniLibs
# because the release packaging flow does not run externalNativeBuild (see the
# commented-out cmake blocks in app/build.gradle.kts), so this script builds the
# two artifacts and copies them where Gradle will package them:
#
#   libproot.so         - the tracer, exec'd as a program despite the .so name
#                         (Android only allows executables from the lib dir)
#   libproot-loader.so  - the stub PRoot injects to load guest ELFs
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/app/src/main/cpp/proot"
BUILD_DIR="$ROOT/native/proot-x86_64"
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

echo "==> configuring PRoot ($ABI, API $API, NDK $(basename "$NDK_ROOT"))"
rm -rf "$BUILD_DIR"
cmake -S "$SRC" -B "$BUILD_DIR" \
    -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN_FILE" \
    -DANDROID_ABI="$ABI" \
    -DANDROID_PLATFORM="android-$API" \
    -DCMAKE_BUILD_TYPE=Debug

echo "==> building"
cmake --build "$BUILD_DIR" -j"$(nproc)"

mkdir -p "$JNI_OUT"
for lib in libproot.so libproot-loader.so; do
    found="$(find "$BUILD_DIR" -name "$lib" -type f | head -1)"
    [[ -n "$found" ]] || { echo "build did not produce $lib" >&2; exit 1; }
    cp "$found" "$JNI_OUT/$lib"
    echo "staged $JNI_OUT/$lib ($(du -h "$JNI_OUT/$lib" | cut -f1))"
done

echo "==> verifying ELF machine is x86_64"
for lib in libproot.so libproot-loader.so; do
    readelf -h "$JNI_OUT/$lib" | grep -qE 'Machine:.*X86-64' \
        || { echo "$lib is not x86_64" >&2; exit 1; }
done
echo "done"
