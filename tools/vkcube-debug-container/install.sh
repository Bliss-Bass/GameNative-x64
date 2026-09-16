#!/usr/bin/env bash
# Stage tools/vkcube-debug-container onto a device as a Custom Game.
#
# Usage:
#   ./tools/vkcube-debug-container/install.sh [serial]
#   DEVICE=192.168.1.208:5555 ./tools/vkcube-debug-container/install.sh
#
# Idempotent: re-running refreshes vkcube + metadata. Stable CUSTOM_GAME_424242.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEVICE="${1:-${DEVICE:-}}"
ADB=(adb)
[[ -n "$DEVICE" ]] && ADB=(adb -s "$DEVICE")

FOLDER_NAME="vkcube-debug"
PKG="app.gamenative"

echo "==> device: ${DEVICE:-default}"
"${ADB[@]}" wait-for-device

# Prefer public CustomGames (scanner + MTP); fall back to app sandbox.
DEST="$("${ADB[@]}" shell "
  for p in \
    /storage/emulated/0/GameNative/CustomGames \
    /sdcard/GameNative/CustomGames \
    /storage/emulated/0/Android/data/$PKG/files/CustomGames \
    /data/data/$PKG/files/CustomGames
  do
    parent=\$(dirname \"\$p\")
    if [ -d \"\$parent\" ] || mkdir -p \"\$p\" 2>/dev/null; then
      mkdir -p \"\$p\" 2>/dev/null || true
      if [ -d \"\$p\" ] && touch \"\$p/.write_test\" 2>/dev/null; then
        rm -f \"\$p/.write_test\"
        echo \"\$p\"
        exit 0
      fi
    fi
  done
  # last resort as root
  mkdir -p /data/data/$PKG/files/CustomGames
  echo /data/data/$PKG/files/CustomGames
" | tr -d '\r' | tail -1)"

[[ -n "$DEST" ]] || { echo "could not resolve CustomGames path" >&2; exit 1; }
TARGET="$DEST/$FOLDER_NAME"
echo "==> target: $TARGET"

VKCUBE="$ROOT/vkcube"
if [[ ! -x "$VKCUBE" ]]; then
  echo "==> vkcube missing; fetching"
  bash "$ROOT/fetch-vkcube.sh" "$VKCUBE"
fi

"${ADB[@]}" shell "mkdir -p '$TARGET'"
"${ADB[@]}" push "$VKCUBE" /data/local/tmp/vkcube-debug-bin
"${ADB[@]}" push "$ROOT/.gamenative" /data/local/tmp/vkcube-debug-meta
"${ADB[@]}" push "$ROOT/README.md" /data/local/tmp/vkcube-debug-readme
"${ADB[@]}" shell "
  cp /data/local/tmp/vkcube-debug-bin '$TARGET/vkcube'
  cp /data/local/tmp/vkcube-debug-meta '$TARGET/.gamenative'
  cp /data/local/tmp/vkcube-debug-readme '$TARGET/README.md'
  chmod 755 '$TARGET/vkcube'
  chmod 644 '$TARGET/.gamenative' '$TARGET/README.md'
  # Ensure app uid can read/exec if we wrote as root
  if id | grep -q uid=0; then
    APP_UID=\$(stat -c %u /data/data/$PKG 2>/dev/null || echo 10129)
    chown -R \"\$APP_UID:\$APP_UID\" '$TARGET' 2>/dev/null || true
    # world-exec on shared storage so the app can run it regardless of ownership quirks
    chmod 755 '$TARGET/vkcube'
  fi
  ls -la '$TARGET'
"

echo
echo "Installed. In GameNative: open library entry \"$FOLDER_NAME\" (CUSTOM_GAME_424242)."
echo "Force DRI3:  adb ${DEVICE:+-s $DEVICE }shell setprop debug.gamenative.presentation dri3"
echo "Logs:        adb ${DEVICE:+-s $DEVICE }logcat -s DRI3:I XServerScreen:I"
