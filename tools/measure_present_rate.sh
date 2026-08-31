#!/bin/bash
# Measure both ends of the presentation path for a running game: the rate the guest hands us
# frames (FrameStats, from PresentExtension) and the rate SurfaceFlinger actually composites
# the app's surface. Run from the host with a session already up.
#
#   tools/measure_present_rate.sh <device> [sample_seconds]
#
# Why two numbers rather than an in-guest overlay: a Vulkan or DXVK HUD reports only the first,
# which the copy to the Android surface and then vsync throttle again downstream, so it cannot
# say which stage is the limit. If the composited rate tracks the guest rate, the guest is the
# limit; if it falls short, the cost is on our side of the socket.
set -e

D="${1:?usage: measure_present_rate.sh <device> [seconds]}"
SECONDS_TO_SAMPLE="${2:-20}"

echo "== presentation path in use =="
adb -s "$D" logcat -d -t 200000 2>/dev/null | grep -oE "presentation=[a-z_]*" | tail -1 ||
    echo "(not in the log buffer any more; relaunch the game to record it)"

echo
echo "== guest present rate (${SECONDS_TO_SAMPLE}s) =="
timeout "$SECONDS_TO_SAMPLE" adb -s "$D" logcat -s FrameStats 2>/dev/null |
    awk '/presented/ {n++; split($0, f, "presented "); split(f[2], g, "/"); sum += g[1]}
         END {if (n) printf "mean %.1f/s over %d samples\n", sum / n, n; else print "no samples: is FrameStats.ENABLED set?"}'

echo
echo "== composited rate =="
# The BLAST layer is the SurfaceView the X server renders into; its parent layers never post.
LAYER=$(adb -s "$D" shell dumpsys SurfaceFlinger --list 2>/dev/null |
    grep -oE "[0-9a-f]+ SurfaceView\[app\.gamenative/[^]]*\]\(BLAST\)#[0-9]+" | tail -1)
if [ -z "$LAYER" ]; then
    echo "no gamenative surface layer found; is a session up?"
    exit 1
fi

adb -s "$D" shell "dumpsys SurfaceFlinger --latency '$LAYER'" 2>/dev/null > /tmp/gn-latency.txt
python3 - <<'PY'
stamps = []
for i, line in enumerate(open('/tmp/gn-latency.txt')):
    parts = line.split()
    # First line is the refresh period; each frame is three timestamps, and a pending frame is
    # marked with INT64_MAX rather than being left out.
    if i == 0 or len(parts) != 3:
        continue
    try:
        posted = int(parts[1])
    except ValueError:
        continue
    if 0 < posted < 9e18:
        stamps.append(posted)

stamps.sort()
if len(stamps) < 3:
    print(f"only {len(stamps)} frames sampled, too few to rate")
else:
    span = (stamps[-1] - stamps[0]) / 1e9
    print(f"{len(stamps)} frames over {span:.2f}s = {(len(stamps) - 1) / span:.1f} fps composited")
PY
