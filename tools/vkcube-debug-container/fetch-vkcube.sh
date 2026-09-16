#!/usr/bin/env bash
# Download Termux vulkan-tools x86_64 vkcube into this directory.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT="${1:-$ROOT/vkcube}"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

DEB_URL="${VKCUBE_DEB_URL:-https://packages.termux.dev/apt/termux-main/pool/main/v/vulkan-tools/vulkan-tools_1.4.362_x86_64.deb}"

echo "==> fetching $DEB_URL"
curl -fsSL -o "$TMP/vulkan-tools.deb" "$DEB_URL"
( cd "$TMP" && ar x vulkan-tools.deb && tar xf data.tar.* )
SRC="$(find "$TMP" -type f -name vkcube | head -1)"
[[ -n "$SRC" ]] || { echo "vkcube not found in deb" >&2; exit 1; }
install -m 755 "$SRC" "$OUT"
file "$OUT"
echo "==> wrote $OUT"
