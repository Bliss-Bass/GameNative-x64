#!/bin/sh
# Guest side of the Linux test suite: runs inside a distro, under PRoot, from the Linux Terminal.
#
#   sh /mnt/storage/internal/Download/gn-linux-test/probe.sh <test> [<test> ...]
#
# Results go next to this script, in results/<distro id>/<test>.result (STATUS, SECONDS, NOTE
# lines) and <test>.log (full output), then <test>.done, which is what linux-test.sh waits for.
# Plain POSIX sh: it has to run in dash, bash and busybox ash alike.

HERE=$(cd "$(dirname "$0")" && pwd)
. /etc/os-release 2>/dev/null
DISTRO=${ID:-unknown}
case "$DISTRO" in
    arch) DISTRO=archlinux ;;
esac
OUT="$HERE/results/$DISTRO"
mkdir -p "$OUT"

if command -v apt-get >/dev/null 2>&1; then PM=apt
elif command -v pacman >/dev/null 2>&1; then PM=pacman
elif command -v dnf >/dev/null 2>&1; then PM=dnf
elif command -v apk >/dev/null 2>&1; then PM=apk
else PM=none
fi

export DEBIAN_FRONTEND=noninteractive

pm_refresh() {
    case "$PM" in
        apt) apt-get update ;;
        pacman) pacman -Sy --noconfirm ;;
        dnf) dnf -y makecache ;;
        apk) apk update ;;
        *) return 1 ;;
    esac
}

pm_install() {
    case "$PM" in
        apt) apt-get install -y --no-install-recommends "$@" ;;
        pacman) pacman -S --noconfirm --needed "$@" ;;
        dnf) dnf install -y "$@" ;;
        apk) apk add "$@" ;;
        *) return 1 ;;
    esac
}

pm_remove() {
    case "$PM" in
        apt) apt-get purge -y "$@" ;;
        pacman) pacman -Rns --noconfirm "$@" ;;
        dnf) dnf remove -y "$@" ;;
        apk) apk del "$@" ;;
        *) return 1 ;;
    esac
}

# --- tests: each prints what it did and returns 0 (pass), 1 (fail) or 2 (skip) ---

t_info() {
    echo "id=$DISTRO pretty=${PRETTY_NAME:-?} pm=$PM"
    uname -a
    echo "shell=$SHELL tty=$(tty 2>/dev/null) user=$(id -un 2>/dev/null) uid=$(id -u)"
    echo "LANG=$LANG TZ=$TZ date=$(date)"
    echo "rootfs size:"
    du -sxh / 2>/dev/null | tail -n 1
    [ "$PM" != none ]
}

t_net() {
    host=deb.debian.org
    if command -v getent >/dev/null 2>&1; then
        getent hosts "$host" || return 1
    elif command -v nslookup >/dev/null 2>&1; then
        nslookup "$host" || return 1
    else
        echo "no resolver tool"; return 2
    fi
    if command -v curl >/dev/null 2>&1; then
        curl -fsSI https://www.google.com | head -n 1
    elif command -v wget >/dev/null 2>&1; then
        wget -q -S --spider https://www.google.com 2>&1 | head -n 3
    else
        echo "no curl or wget; DNS only"
    fi
}

t_pkg() {
    pm_refresh || { echo "refresh failed"; return 1; }
    pm_install nano || { echo "install failed"; return 1; }
    command -v nano || { echo "nano missing after install"; return 1; }
    nano --version | head -n 1
    pm_remove nano || { echo "remove failed"; return 1; }
    # The shell remembers where it found nano; look at the files instead.
    hash -r 2>/dev/null
    if [ -e /usr/bin/nano ] || [ -e /bin/nano ]; then echo "nano still present after remove"; return 1; fi
    echo "installed and removed nano"
}

t_user() {
    name=gntest
    if command -v useradd >/dev/null 2>&1; then
        useradd -m -s /bin/sh "$name" || return 1
    elif command -v adduser >/dev/null 2>&1; then
        adduser -D -s /bin/sh "$name" || return 1
    else
        echo "no useradd or adduser"; return 2
    fi
    out=$(su -s /bin/sh -c 'id -un' "$name" 2>&1)
    echo "su -> $out"
    if command -v userdel >/dev/null 2>&1; then userdel -r "$name" 2>/dev/null; else deluser --remove-home "$name" 2>/dev/null; fi
    [ "$out" = "$name" ]
}

t_terminal() {
    echo "terminal ok: tty=$(tty 2>/dev/null) term=$TERM cols=$(stty size 2>/dev/null)"
    [ -n "$TERM" ]
}

run_one() {
    test=$1
    rm -f "$OUT/$test.done" "$OUT/$test.result"
    start=$(date +%s)
    case "$test" in
        info|net|pkg|user|terminal) "t_$test" > "$OUT/$test.log" 2>&1; rc=$? ;;
        *) echo "unknown test $test" > "$OUT/$test.log"; rc=1 ;;
    esac
    end=$(date +%s)
    case $rc in
        0) status=PASS ;;
        2) status=SKIP ;;
        *) status=FAIL ;;
    esac
    {
        echo "STATUS $status"
        echo "SECONDS $((end - start))"
        echo "NOTE $(tail -n 1 "$OUT/$test.log" | cut -c1-200)"
    } > "$OUT/$test.result"
    touch "$OUT/$test.done"
    echo "[probe] $DISTRO $test: $status ($((end - start))s)"
}

for t in "$@"; do run_one "$t"; done
