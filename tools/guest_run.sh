#!/system/bin/sh
# Run a command inside GameNative's Linux userland, the same way the app's terminal does.
# For host-side debugging and for installs that are painful to type on a tablet.
#
#   adb push tools/guest_run.sh /data/local/tmp/
#   adb shell "run-as app.gamenative sh /data/local/tmp/guest_run.sh 'apt-get update'"
#
# APKLIB is resolved from pm path when not supplied, since /data/app is not listable by the
# app itself. No X display is bound: this is for shell work, not for GUI programs.
set -e

F=/data/data/app.gamenative/files
ROOTFS=$F/linux/rootfs
: "${APKLIB:?set APKLIB to the native library dir of the installed app}"

[ -d "$ROOTFS" ] || { echo "no rootfs at $ROOTFS; set the userland up first" >&2; exit 1; }

mkdir -p "$ROOTFS/tmp/shm"

# --root-id because dpkg chowns everything it unpacks, so apt cannot install without it.
# Upstream proot, not the Wine fork: only this one can fake root.
PROOT_LOADER="$APKLIB/libproot-linux-loader.so" \
PROOT_TMP_DIR="$ROOTFS/tmp" \
exec "$APKLIB/libproot-linux.so" \
    --kill-on-exit \
    --root-id \
    --rootfs="$ROOTFS" \
    --cwd=/root \
    --bind=/dev \
    --bind=/proc \
    --bind=/sys \
    --bind="$ROOTFS/tmp/shm:/dev/shm" \
    /usr/bin/env \
    PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
    HOME=/root \
    TERM=xterm-256color \
    LANG=C.UTF-8 \
    XDG_RUNTIME_DIR=/tmp \
    DEBIAN_FRONTEND=noninteractive \
    /bin/sh -c "$*"
