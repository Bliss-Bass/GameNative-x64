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
        # -Syu, as GameNative does: installing against a refreshed index without upgrading is a
        # partial upgrade, which Arch does not support.
        pacman) pacman -Syu --noconfirm --needed "$@" ;;
        dnf) dnf install -y "$@" ;;
        apk) apk add "$@" ;;
        *) return 1 ;;
    esac
}

pm_remove() {
    case "$PM" in
        apt) apt-get purge -y --autoremove "$@" ;;
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

# Ubuntu: browser package sources are set up, but no browser is installed by default.
t_srcs() {
    [ "$DISTRO" = ubuntu ] || { echo "Ubuntu only"; return 2; }
    rc=0
    for f in /etc/apt/sources.list.d/mozilla.sources /etc/apt/sources.list.d/xtradeb-ubuntu-apps-noble.sources \
        /etc/apt/sources.list.d/google-chrome.sources; do
        if [ -f "$f" ]; then echo "source: $f"; else echo "missing source: $f"; rc=1; fi
    done
    for b in firefox chromium google-chrome-stable; do
        if dpkg-query -W -f='${Status}' "$b" 2>/dev/null | grep -q 'install ok installed'; then
            echo "installed by default: $b"; rc=1
        fi
    done
    [ $rc -eq 0 ] && echo "sources present, no browser installed"
    return $rc
}

# Browsers, by the names the Linux Apps hint gives: <browser> is firefox, chromium or chrome.
# Prints this distro's package for it, nothing when it has none (Chrome is AUR-only on Arch).
browser_pkg() {
    case "$1:$DISTRO" in
        firefox:debian) echo firefox-esr ;;
        firefox:*) echo firefox ;;
        chromium:*) echo chromium ;;
        chrome:ubuntu | chrome:debian) echo google-chrome-stable ;;
        chromebeta:ubuntu | chromebeta:debian) echo google-chrome-beta ;;
    esac
}

browser_bin() {
    case "$1:$DISTRO" in
        firefox:debian) echo firefox-esr ;;
        firefox:*) echo firefox ;;
        chromium:*) echo chromium ;;
        chrome:*) echo google-chrome-stable ;;
        chromebeta:*) echo google-chrome-beta ;;
    esac
}

# The desktop entries <package> installed, one per line, without .desktop.
pkg_entries() {
    case "$PM" in
        apt) dpkg -L "$1" 2>/dev/null ;;
        pacman) pacman -Qlq "$1" 2>/dev/null ;;
    esac | grep -E '^/usr/share/applications/[^/]+\.desktop$' | sed 's|.*/||; s|\.desktop$||'
}

# Installs <browser> from the distro's sources and checks it runs. Writes the desktop entry it
# added to br_<browser>.entry, which linux-test.sh opens from Linux Apps.
t_browser() {
    b=$1; pkg=$(browser_pkg "$b")
    rm -f "$OUT/br_$b.entry"
    [ -n "$pkg" ] || { echo "$b is not packaged for $DISTRO"; return 2; }
    pm_refresh || echo "refresh failed; trying the install anyway"
    pm_install "$pkg" || { echo "installing $pkg failed"; return 1; }
    hash -r 2>/dev/null
    bin=$(browser_bin "$b")
    command -v "$bin" || { echo "$bin missing after installing $pkg"; return 1; }
    version=$("$bin" --version 2>&1 | tail -n 1)
    echo "version: $version"
    # Every entry it added: Chrome ships two, and only one is listed in Linux Apps.
    pkg_entries "$pkg" > "$OUT/br_$b.entry"
    [ -s "$OUT/br_$b.entry" ] || { echo "$pkg installed no desktop entry"; return 1; }
    echo "installed $pkg ($version), entries $(tr '\n' ' ' < "$OUT/br_$b.entry")"
}

# Installs htop, which every supported distro packages with a desktop entry, for test G to
# uninstall again through Linux Apps.
t_htop() {
    pm_refresh || echo "refresh failed; trying the install anyway"
    pm_install htop || { echo "installing htop failed"; return 1; }
    [ -f /usr/share/applications/htop.desktop ] || { echo "htop installed no desktop entry"; return 1; }
    echo "installed htop"
}

# XTerm, for test G's session checks in a distro that has none (a fresh or reset one).
t_xterm() {
    pm_refresh || echo "refresh failed; trying the install anyway"
    pm_install xterm || { echo "installing xterm failed"; return 1; }
    echo "installed xterm"
}

# A file left in the distro before test G resets it, and its absence afterwards.
t_mark() { echo marked > /root/.gn-reset-marker && echo "left /root/.gn-reset-marker"; }
t_unmarked() {
    if [ -e /root/.gn-reset-marker ]; then echo "marker survived the reset"; return 1; fi
    echo "marker gone: the distro was reinstalled"
}

pkg_installed() {
    case "$PM" in
        apt) dpkg-query -W -f='${Status}' "$1" 2>/dev/null | grep -q 'install ok installed' ;;
        pacman) pacman -Q "$1" >/dev/null 2>&1 ;;
        *) return 1 ;;
    esac
}

# Judged by the package manager: GameNative's /usr/local/bin/firefox launcher outlives the
# package until the next session start clears it.
t_rmbrowser() {
    b=$1; pkg=$(browser_pkg "$b")
    [ -n "$pkg" ] || { echo "$b is not packaged for $DISTRO"; return 2; }
    pkg_installed "$pkg" || { echo "$pkg was not installed"; return 2; }
    pm_remove "$pkg" || { echo "removing $pkg failed"; return 1; }
    if pkg_installed "$pkg"; then echo "$pkg still installed after remove"; return 1; fi
    echo "removed $pkg"
}

# Package manager state, for diagnosing a failing pkg test; always passes.
t_pkgdiag() {
    case "$PM" in
        pacman)
            grep -nE '^\s*#?\s*(DownloadUser|DisableSandbox|CheckSpace|SigLevel|Architecture)' /etc/pacman.conf
            grep -n '^Server' /etc/pacman.d/mirrorlist
            ls -la --time-style=+%F_%T /var/lib/pacman/sync/
            pacman -Sy --noconfirm 2>&1 | tail -n 5
            ls -la --time-style=+%F_%T /var/lib/pacman/sync/
            pacman -Si nano 2>&1 | grep -E '^(Version|Repository)'
            ;;
        apt) cat /etc/apt/apt.conf.d/* 2>/dev/null; apt-cache policy nano | head -n 5 ;;
        *) echo "pm=$PM" ;;
    esac
    return 0
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
        info|net|pkg|pkgdiag|user|terminal|srcs|htop|xterm|mark|unmarked) "t_$test" > "$OUT/$test.log" 2>&1; rc=$? ;;
        br_*) t_browser "${test#br_}" > "$OUT/$test.log" 2>&1; rc=$? ;;
        rmbr_*) t_rmbrowser "${test#rmbr_}" > "$OUT/$test.log" 2>&1; rc=$? ;;
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
