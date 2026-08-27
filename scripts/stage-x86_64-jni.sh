#!/usr/bin/env bash
# Copy built JNI artifacts from app/.cxx-bliss-x64/<abi>/ into jniLibs.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ABI="${ABI:-x86_64}"
BUILD_DIR="${ROOT}/app/.cxx-bliss-x64/${ABI}"
OUT_DIR="${ROOT}/app/src/modernX64/jniLibs/${ABI}"

if [[ ! -d "$BUILD_DIR" ]]; then
    echo "Build dir missing: $BUILD_DIR (run scripts/build-x86_64-jni.sh first)" >&2
    exit 1
fi

mkdir -p "$OUT_DIR"
find "$BUILD_DIR" -name '*.so' -type f | while read -r so; do
    base="$(basename "$so")"
    cp -f "$so" "${OUT_DIR}/${base}"
    echo "=> ${OUT_DIR}/${base}"
done
