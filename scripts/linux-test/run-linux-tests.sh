#!/usr/bin/env bash
# Host side of the GameNative Linux test suite: pushes linux-test.sh and probe.sh to a device,
# starts the run detached (so a dropped adb connection does not kill an hour-long install), polls
# until it finishes and pulls the report. See README.md.
set -euo pipefail

usage() {
    cat <<'EOF'
usage: run-linux-tests.sh -s <serial> --yes [options]
  -s <serial>          adb device, e.g. 192.168.1.208:5555
  --user <id>          Android user GameNative runs in (default 0; the Googlebook uses 10)
  --distros "<ids>"    default "ubuntu debian archlinux"
  --tests "<letters>"  default "A B C" (A install/remove, B shell basics, C terminal,
                       D desktop session, E browsers, F Linux Apps and app windows)
  --probe "<tests>"    run these probe tests instead of B's set (e.g. "pkgdiag pkg")
  --keep               leave the distros installed at the end
  --no-install         only test distros that are already installed
  --out <dir>          where to put the report (default ./linux-test-reports)
  --yes                required: this installs, removes and types into GameNative on the device
EOF
}

SERIAL="" USER_ID=0 DISTROS="ubuntu debian archlinux" TESTS="A B C" KEEP="" NO_INSTALL="" YES=0 PROBE=""
OUT=./linux-test-reports
while [ $# -gt 0 ]; do
    case "$1" in
        -s) SERIAL=$2; shift ;;
        --user) USER_ID=$2; shift ;;
        --distros) DISTROS=$2; shift ;;
        --tests) TESTS=$2; shift ;;
        --keep) KEEP=--keep ;;
        --probe) PROBE=$2; shift ;;
        --no-install) NO_INSTALL=--no-install ;;
        --out) OUT=$2; shift ;;
        --yes) YES=1 ;;
        -h|--help) usage; exit 0 ;;
        *) echo "unknown argument: $1"; usage; exit 2 ;;
    esac
    shift
done
[ -n "$SERIAL" ] || { usage; exit 2; }
if [ $YES -ne 1 ]; then
    echo "This drives GameNative on $SERIAL: it installs and removes distros ($DISTROS) and types into the Linux Terminal."
    echo "Re-run with --yes to go ahead."
    exit 2
fi

HERE=$(cd "$(dirname "$0")" && pwd)
W=/data/local/tmp/gn-linux-test
A=(adb -s "$SERIAL")

"${A[@]}" get-state >/dev/null
echo "Device: $("${A[@]}" shell getprop ro.product.model | tr -d '\r'), user $USER_ID"
busy=$("${A[@]}" shell "[ -f $W/run.pid ] && kill -0 \$(cat $W/run.pid) 2>/dev/null && cat $W/run.pid; true" | tr -d '\r')
if [ -n "$busy" ]; then
    echo "A run is already in progress on $SERIAL (pid $busy); not starting another."
    exit 3
fi
"${A[@]}" shell "mkdir -p $W && rm -f $W/status"
"${A[@]}" push "$HERE/linux-test.sh" "$HERE/probe.sh" "$W/" >/dev/null
# The guest reaches probe.sh and writes its results through shared storage, which PRoot binds
# only while GameNative has all-files access.
"${A[@]}" shell "appops set --user $USER_ID app.gamenative MANAGE_EXTERNAL_STORAGE allow"

PROBE_ARG=""
[ -n "$PROBE" ] && PROBE_ARG="--probe '$PROBE'"
"${A[@]}" shell "cd $W && nohup sh linux-test.sh --user $USER_ID --distros '$DISTROS' --tests '$TESTS' $KEEP $NO_INSTALL $PROBE_ARG > $W/console.log 2>&1 &"
echo "Started. Following progress (Ctrl+C stops following, not the run):"

last=""
while true; do
    sleep 20
    st=$("${A[@]}" shell "cat $W/status 2>/dev/null" 2>/dev/null | tr -d '\r' || true)
    line=$("${A[@]}" shell "tail -n 1 $W/console.log 2>/dev/null" 2>/dev/null | tr -d '\r' || true)
    if [ "$line" != "$last" ]; then echo "  $line"; last=$line; fi
    case "$st" in DONE*) break ;; esac
done

run=$("${A[@]}" shell "readlink $W/latest" | tr -d '\r')
mkdir -p "$OUT"
"${A[@]}" pull "$run" "$OUT/" >/dev/null
dest="$OUT/$(basename "$run")"
echo "Report: $dest/report.md"
sed -n '/^## Summary/,/^## Results/p' "$dest/report.md" | sed '$d'
