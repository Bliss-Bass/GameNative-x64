#!/system/bin/sh
# Device side of the GameNative Linux test suite. Pushed and started by run-linux-tests.sh; drives
# the app like a person would (intents, taps found through uiautomator ids, typing into the Linux
# Terminal) and writes a report. See README.md.
#
#   sh linux-test.sh --user 0 --distros "ubuntu debian archlinux" --tests "A B C" [--keep] [--no-install]

PKG=app.gamenative
ACT=$PKG/.MainActivity
W=/data/local/tmp/gn-linux-test
USER_ID=0
DISTROS="ubuntu debian archlinux"
TESTS="A B C"
KEEP=0
NO_INSTALL=0
PROBE=""

while [ $# -gt 0 ]; do
    case "$1" in
        --user) USER_ID=$2; shift ;;
        --probe) PROBE=$2; shift ;;
        --distros) DISTROS=$2; shift ;;
        --tests) TESTS=$2; shift ;;
        --keep) KEEP=1 ;;
        --no-install) NO_INSTALL=1 ;;
        *) echo "unknown argument $1"; exit 2 ;;
    esac
    shift
done

SHARED=/storage/emulated/$USER_ID/Download/gn-linux-test
GUEST=/mnt/storage/internal/Download/gn-linux-test
RUN=$W/run-$(date +%Y%m%d-%H%M%S)
mkdir -p "$RUN/shots" "$RUN/logs" "$SHARED/results"
rm -f "$W/latest"; ln -s "$RUN" "$W/latest"
cp "$W/probe.sh" "$SHARED/probe.sh"
: > "$RUN/results.tsv"

# ---------------------------------------------------------------- helpers

log() { echo "$(date +%H:%M:%S) $*" | tee -a "$RUN/run.log"; }
status() { echo "$*" > "$W/status"; }
now() { date +"%m-%d %H:%M:%S.000"; }
has_test() { case " $TESTS " in *" $1 "*) return 0 ;; esac; return 1; }

name_of() {
    case "$1" in
        ubuntu) echo "Ubuntu" ;; debian) echo "Debian" ;; archlinux) echo "Arch Linux" ;;
        alpine) echo "Alpine Linux" ;; fedora) echo "Fedora" ;; *) echo "$1" ;;
    esac
}

# result <distro> <test> <PASS|FAIL|SKIP> <seconds> <note>
result() {
    printf '%s\t%s\t%s\t%s\t%s\n' "$1" "$2" "$3" "$4" "$5" >> "$RUN/results.tsv"
    log "RESULT $1 $2: $3 (${4}s) $5"
}

# The log since <since> ("MM-DD HH:MM:SS.mmm", as [now] prints). Read from a capture of the whole
# run rather than the ring buffer: some devices keep 256 KiB, which a browser's console output
# and the window fitter's lines turn over in seconds.
log_since() { awk -v s="$1" 'substr($0, 1, 18) >= s' "$RUN/logcat.txt"; }

# Saves this app's logcat since <since> and a screenshot, named <distro>-<test>.
evidence() {
    log_since "$3" | grep -E "app\.gamenative|$PKG|proot" > "$RUN/logs/$1-$2.logcat"
    screencap -p "$RUN/shots/$1-$2.png"
}

# uiautomator dumps only the active window, which a dock or notification popup can briefly be;
# every screen the suite reads is GameNative's, so dump again until it is in the picture.
ui_dump() {
    local tries=0
    while [ $tries -lt 5 ]; do
        uiautomator dump "$W/ui.xml" >/dev/null 2>&1
        grep -q "package=\"$PKG\"" "$W/ui.xml" 2>/dev/null && return 0
        tries=$((tries + 1)); sleep 1
    done
    return 0
}

# Prints "x y" for the centre of the node with resource id <id> in the last dump.
ui_center_id() {
    b=$(grep -o "resource-id=\"$1\"[^>]*" "$W/ui.xml" | head -n 1 |
        sed -n 's/.*bounds="\[\([0-9]*\),\([0-9]*\)\]\[\([0-9]*\),\([0-9]*\)\]".*/\1 \2 \3 \4/p')
    [ -n "$b" ] || return 1
    set -- $b
    echo "$(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))"
}

# Same, for the first node whose text is exactly <text>.
ui_center_text() {
    b=$(grep -o "text=\"$1\"[^>]*" "$W/ui.xml" | head -n 1 |
        sed -n 's/.*bounds="\[\([0-9]*\),\([0-9]*\)\]\[\([0-9]*\),\([0-9]*\)\]".*/\1 \2 \3 \4/p')
    [ -n "$b" ] || return 1
    set -- $b
    echo "$(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))"
}

ui_has_id() { grep -q "resource-id=\"$1\"" "$W/ui.xml"; }
ui_has_text() { grep -q "text=\"$1" "$W/ui.xml"; }

# Finds <id>, scrolling the screen <root> when it is off screen: to the top, then down a page at
# a time until the list stops moving. Prints its centre.
ui_find() {
    local id=$1 root=$2 c b x lo hi dir prev n sig
    ui_dump
    if c=$(ui_center_id "$id"); then echo "$c"; return 0; fi
    # Swipe in the lower part of the screen: fixed settings can sit above the list, and a swipe
    # that starts on them scrolls nothing.
    b=$(grep -o "resource-id=\"$root\"[^>]*" "$W/ui.xml" | head -n 1 |
        sed -n 's/.*bounds="\[\([0-9]*\),\([0-9]*\)\]\[\([0-9]*\),\([0-9]*\)\]".*/\1 \2 \3 \4/p')
    [ -n "$b" ] || return 1
    set -- $b
    x=$(( ($1 + $3) / 2 )); hi=$(( $2 + ($4 - $2) * 85 / 100 )); lo=$(( $2 + ($4 - $2) * 45 / 100 ))
    for dir in top down; do
        prev=""; n=0
        while [ $n -lt 30 ]; do
            if [ $dir = top ]; then input swipe "$x" "$lo" "$x" "$hi" 300
            else input swipe "$x" "$hi" "$x" "$lo" 300
            fi
            sleep 1; ui_dump
            if c=$(ui_center_id "$id"); then echo "$c"; return 0; fi
            sig=$(md5sum "$W/ui.xml" | cut -c1-32)
            [ "$sig" = "$prev" ] && break
            prev=$sig; n=$((n + 1))
        done
    done
    return 1
}

tap_id() {
    c=$(ui_find "$1" "$2") || { log "cannot find $1"; return 1; }
    set -- $c; input tap "$1" "$2"
}

# wait_log <since> <success regex> <failure regex> <timeout s>; 0 success, 1 failure, 2 timeout.
wait_log() {
    since=$1; ok=$2; bad=$3; limit=$4; waited=0
    while [ $waited -lt "$limit" ]; do
        out=$(log_since "$since")
        echo "$out" | grep -Eq "$ok" && return 0
        [ -n "$bad" ] && echo "$out" | grep -Eq "$bad" && return 1
        sleep 10; waited=$((waited + 10))
    done
    return 2
}

wake() {
    input keyevent KEYCODE_WAKEUP
    wm dismiss-keyguard >/dev/null 2>&1
}

open_distros() {
    am start --user "$USER_ID" -n "$ACT" -a app.gamenative.action.LINUX_DISTROS >/dev/null 2>&1
    sleep 4
}

open_terminal() {
    am start --user "$USER_ID" -n "$ACT" -a app.gamenative.action.LINUX_TERMINAL --es linux_distro "$1" >/dev/null 2>&1
    sleep 4
}

back_to_android() { input keyevent KEYCODE_HOME; }

# Removes Linux app stub tasks left in recents. Desktop mode relaunches a desk's tasks when the
# desk comes forward, so a stale one would open its app over the screen under test.
clear_stub_tasks() {
    for t in $(dumpsys activity recents | grep -E 'Recent #[0-9]+: Task\{.*app\.gamenative\.stub\.' |
        sed -E 's/.* #([0-9]+) type=.*/\1/'); do
        am stack remove "$t" >/dev/null 2>&1 && log "removed stale stub task $t"
    done
}

is_installed() {
    open_distros
    ui_find "distro_remove_$1" linux_distros_screen >/dev/null
}

# ---------------------------------------------------------------- tests

# A: install through the Linux Distros screen.
test_install() {
    d=$1; since=$(now); start=$(date +%s)
    status "RUNNING $d install"
    if is_installed "$d"; then
        result "$d" install SKIP 0 "already installed"
        return 0
    fi
    tap_id "distro_install_$d" linux_distros_screen || { result "$d" install FAIL 0 "no install button"; return 1; }
    wait_log "$since" "\[LinuxDistros\]: installed $d|\[LinuxRootfs\]: installed to .*distros/$d/" \
        "\[LinuxDistros\]: $d install failed|\[LinuxDistrosScreen\]: $d failed" 5400
    rc=$?; secs=$(( $(date +%s) - start ))
    evidence "$d" install "$since"
    case $rc in
        0) result "$d" install PASS "$secs" "installed" ;;
        1) result "$d" install FAIL "$secs" "$(grep -A2 -E "LinuxDistrosScreen\]: $d failed|$d install failed" "$RUN/logs/$d-install.logcat" | tail -n 2 | tr '\n' ' ' | cut -c1-200)" ;;
        *) result "$d" install FAIL "$secs" "timed out" ;;
    esac
    [ $rc -eq 0 ]
}

# A: remove through the Linux Distros screen.
test_remove() {
    d=$1; since=$(now); start=$(date +%s)
    status "RUNNING $d remove"
    open_distros
    tap_id "distro_remove_$d" linux_distros_screen || { result "$d" remove FAIL 0 "no remove button"; return 1; }
    sleep 2; ui_dump
    c=$(ui_center_id dialog_confirm) || { result "$d" remove FAIL 0 "no confirm dialog"; return 1; }
    set -- $c; input tap "$1" "$2"
    wait_log "$since" "\[LinuxDistros\]: removed $d" "" 600
    rc=$?; secs=$(( $(date +%s) - start ))
    evidence "$d" remove "$since"
    if [ $rc -eq 0 ]; then result "$d" remove PASS "$secs" "removed"; else result "$d" remove FAIL "$secs" "no removal logged"; fi
}

# Types the probe command into an open terminal and waits for each test's .done file.
run_probe() {
    d=$1; shift; tests="$*"
    for t in $tests; do rm -f "$SHARED/results/$d/$t.done" "$SHARED/results/$d/$t.result" "$SHARED/results/$d/$t.log"; done
    tries=0; c=""
    while [ $tries -lt 10 ] && [ -z "$c" ]; do
        ui_dump; c=$(ui_center_id terminal_screen) || { sleep 2; tries=$((tries + 1)); }
    done
    if [ -z "$c" ]; then
        evidence "$d" probe-start "$(now)"
        for t in $tests; do result "$d" "probe-$t" FAIL 0 "the terminal was not on screen"; done
        return 1
    fi
    set -- $c; input tap "$1" "$(( $2 + 100 ))"
    # A shell still starting under PRoot drops what is typed ahead of its prompt.
    sleep 3
    command="sh%s$GUEST/probe.sh%s$(echo "$tests" | sed 's/ /%s/g')"
    input text "$command"; input keyevent KEYCODE_ENTER
    # The first test's log appears as soon as the probe starts. Without it the command never
    # reached the shell, and waiting out every test's timeout would only stall the run. One
    # retry, after Enter has cleared whatever part of the line did arrive.
    first=${tests%% *}; waited=0
    while [ ! -f "$SHARED/results/$d/$first.log" ] && [ $waited -lt 90 ]; do
        sleep 5; waited=$((waited + 5))
        if [ $waited -eq 20 ] && [ ! -f "$SHARED/results/$d/$first.log" ]; then
            log "probe did not start in $d; typing the command again"
            input keyevent KEYCODE_ENTER; sleep 1
            input text "$command"; input keyevent KEYCODE_ENTER
        fi
    done
    if [ ! -f "$SHARED/results/$d/$first.log" ]; then
        evidence "$d" probe-start "$(now)"
        for t in $tests; do result "$d" "probe-$t" FAIL 0 "probe did not start (the command did not reach the shell)"; done
        return 1
    fi
    for t in $tests; do
        waited=0
        while [ ! -f "$SHARED/results/$d/$t.done" ] && [ $waited -lt 2400 ]; do sleep 5; waited=$((waited + 5)); done
        if [ -f "$SHARED/results/$d/$t.result" ]; then
            st=$(sed -n 's/^STATUS //p' "$SHARED/results/$d/$t.result")
            secs=$(sed -n 's/^SECONDS //p' "$SHARED/results/$d/$t.result")
            note=$(sed -n 's/^NOTE //p' "$SHARED/results/$d/$t.result")
            cp "$SHARED/results/$d/$t.log" "$RUN/logs/$d-probe-$t.log" 2>/dev/null
            result "$d" "probe-$t" "$st" "$secs" "$note"
        else
            result "$d" "probe-$t" FAIL "$waited" "no result (probe did not run or timed out)"
        fi
    done
}

# Opens the terminal on <distro> and waits out any setup it shows first.
await_terminal() {
    open_terminal "$1"
    waited=0
    while [ $waited -lt 1800 ]; do
        ui_dump
        ui_has_id terminal_screen && ! ui_has_id terminal_progress && break
        sleep 10; waited=$((waited + 10))
    done
    ui_dump
}

# C: the terminal opens on the distro and runs the probe; B: shell basics through the probe.
test_terminal_and_shell() {
    d=$1; since=$(now); start=$(date +%s)
    status "RUNNING $d terminal"
    await_terminal "$d"
    if ui_has_id terminal_message; then
        evidence "$d" terminal "$since"
        result "$d" terminal FAIL "$(( $(date +%s) - start ))" "terminal shows a message instead of a shell"
        return 1
    fi
    if ui_has_text "Distro: " && ! ui_has_text "Distro: $(name_of "$d")"; then
        evidence "$d" terminal "$since"
        result "$d" terminal FAIL "$(( $(date +%s) - start ))" "opened on another distro"
        return 1
    fi
    probe_tests=terminal
    has_test B && probe_tests="terminal info net pkg user"
    has_test B && [ "$d" = ubuntu ] && probe_tests="$probe_tests srcs"
    [ -n "$PROBE" ] && probe_tests="terminal $PROBE"
    run_probe "$d" $probe_tests
    evidence "$d" terminal "$since"
    if grep -q "^$d	probe-terminal	PASS" "$RUN/results.tsv"; then
        result "$d" terminal PASS "$(( $(date +%s) - start ))" "shell answered the probe"
    else
        result "$d" terminal FAIL "$(( $(date +%s) - start ))" "probe did not report back"
    fi
    back_to_android
}

# Number of PRoot hosts running guest processes in <distro>'s rootfs.
guest_count() { ps -A -o ARGS 2>/dev/null | grep libproot-linux | grep -c "distros/$1/rootfs"; }

# Picks <text> from the dropdown behind button <id> on the Linux Distros screen.
choose() {
    tap_id "$1" linux_distros_screen || return 1
    sleep 1; ui_dump
    c=$(ui_center_text "$2") || { input keyevent KEYCODE_BACK; return 1; }
    set -- $c; input tap "$1" "$2"; sleep 1
}

# Closes the Linux window in front through its side menu (Back opens it).
close_linux_window() {
    input keyevent KEYCODE_BACK; sleep 2; ui_dump
    c=$(ui_center_text "Close app") || return 1
    set -- $c; input tap "$1" "$2"; sleep 3
}

# D: the desktop window runs <distro> with Openbox.
test_desktop() {
    d=$1; since=$(now); start=$(date +%s)
    status "RUNNING $d desktop"
    open_distros
    if ! choose desktop_distro "$(name_of "$d")"; then
        result "$d" desktop FAIL 0 "$(name_of "$d") not offered as the desktop distro"; return 1
    fi
    choose desktop_session Openbox || { result "$d" desktop FAIL 0 "Openbox not offered"; return 1; }
    ui_dump
    if ui_has_id desktop_session_install; then
        tap_id desktop_session_install linux_distros_screen
        wait_log "$since" "\[LinuxDesktopSessions\]: openbox installed in $d" \
            "\[LinuxDistrosScreen\]: session:$d:openbox failed|openbox install in $d failed" 2400
        if [ $? -ne 0 ]; then
            evidence "$d" desktop "$since"
            result "$d" desktop FAIL "$(( $(date +%s) - start ))" "installing Openbox failed or timed out"; return 1
        fi
    fi
    launched=$(now)
    am start --user "$USER_ID" -n "$ACT" -a app.gamenative.action.LINUX_DESKTOP >/dev/null 2>&1
    wait_log "$launched" "\[LinuxDisplaySession\]: display :[0-9]+ ready|\[LinuxAndroidXSession\]: slot [0-9]+ ready \(desktop\)" \
        "\[LinuxSessions\]: could not open ::desktop|\[LinuxDesktopScreen\]: could not" 300
    rc=$?
    sleep 15
    alive=$(guest_count "$d")
    evidence "$d" desktop "$since"
    close_linux_window
    back_to_android
    secs=$(( $(date +%s) - start ))
    if [ $rc -ne 0 ]; then result "$d" desktop FAIL "$secs" "desktop did not report ready"
    elif [ "$alive" -lt 1 ]; then result "$d" desktop FAIL "$secs" "no guest processes 15 s after start"
    else result "$d" desktop PASS "$secs" "Openbox desktop up, $alive guest hosts"
    fi
}

# F: Linux Apps lists <distro>'s apps; one opens in its own window; on Bass its drawer stub
# installs and goes again.
test_apps() {
    d=$1; since=$(now); start=$(date +%s)
    status "RUNNING $d apps"
    clear_stub_tasks
    am start --user "$USER_ID" -n "$ACT" -a app.gamenative.action.LINUX_APPS >/dev/null 2>&1
    sleep 6; ui_dump
    # Rows are tagged linux_app_<distro>__<desktop entry>; buttons put their verb first.
    entries=$(grep -o "resource-id=\"linux_app_${d}__[^\"]*\"" "$W/ui.xml" | sed "s/resource-id=\"linux_app_${d}__//; s/\"\$//")
    if [ -z "$entries" ]; then
        # A fresh or reset distro has no apps yet: install XTerm and look again.
        back_to_android
        await_terminal "$d"
        run_probe "$d" xterm
        back_to_android
        am start --user "$USER_ID" -n "$ACT" -a app.gamenative.action.LINUX_APPS >/dev/null 2>&1
        sleep 4
        tap_id linux_apps_refresh linux_apps_screen; sleep 4; ui_dump
        entries=$(grep -o "resource-id=\"linux_app_${d}__[^\"]*\"" "$W/ui.xml" | sed "s/resource-id=\"linux_app_${d}__//; s/\"\$//")
    fi
    if [ -z "$entries" ]; then
        evidence "$d" apps "$since"
        result "$d" apps FAIL 0 "Linux Apps lists no $d apps"; back_to_android; return 1
    fi
    name=$(echo "$entries" | grep -m1 -E '(^|-)xterm$' || echo "$entries" | head -n 1)
    result "$d" apps-list PASS 0 "$(echo "$entries" | wc -l | tr -d ' ') $d apps listed"
    t0=$(date +%s)
    open_app "$d" "$name" app-window 10
    stop_app "$d" "$name" app-stop
    entry="${d}__$name"

    am start --user "$USER_ID" -n "$ACT" -a app.gamenative.action.LINUX_APPS >/dev/null 2>&1
    sleep 4; ui_dump
    if ui_has_id "linux_app_undrawer_$entry"; then
        # Already in the drawer: take it out and put it back, leaving the device as it was.
        if toggle_stub "$entry" undrawer down && toggle_stub "$entry" drawer up; then
            result "$d" app-stub PASS "$(( $(date +%s) - t0 ))" "stub removed and added again"
        else
            result "$d" app-stub FAIL "$(( $(date +%s) - t0 ))" "stub did not leave and come back"
        fi
    elif ui_has_id "linux_app_drawer_$entry"; then
        if ! toggle_stub "$entry" drawer up; then
            result "$d" app-stub SKIP "$(( $(date +%s) - t0 ))" "stub did not install silently (needs the Bass stub installer)"
        elif toggle_stub "$entry" undrawer down; then
            result "$d" app-stub PASS "$(( $(date +%s) - t0 ))" "stub added and removed"
        else
            result "$d" app-stub FAIL "$(( $(date +%s) - t0 ))" "stub added but not removed"
        fi
    else
        result "$d" app-stub SKIP 0 "no drawer button (stubs unsupported here)"
    fi
    back_to_android
}

# open_app <distro> <desktop entry> <result name> <settle s> [<command pattern>]: opens the app
# from its Linux Apps row, cold, and checks it is still running <settle> seconds after its window
# came up: any guest of the distro, or only PRoot hosts whose command matches <command pattern>.
open_app() {
    d=$1; name=$2; what=$3; settle=$4; pattern=$5; since=$(now)
    # $entry tags the row; $key is the session the app runs under, which only Ubuntu leaves bare.
    entry="${d}__$name"
    if [ "$d" = ubuntu ]; then key=$name; else key="$d:$name"; fi
    clear_stub_tasks
    am start --user "$USER_ID" -n "$ACT" -a app.gamenative.action.LINUX_APPS >/dev/null 2>&1
    sleep 4; ui_dump
    # A cold launch each time: a session left running would only bring its window forward.
    if ui_has_id "linux_app_stop_$entry"; then
        tap_id "linux_app_stop_$entry" linux_apps_screen; sleep 6
    fi
    t0=$(date +%s)
    if ! tap_id "linux_app_$entry" linux_apps_screen; then
        evidence "$d" "$what" "$since"
        result "$d" "$what" FAIL 0 "$key is not listed in Linux Apps"; return 1
    fi
    # A window already open for the app is brought forward and its session reused.
    wait_log "$since" "\[LinuxDisplaySession\]: display :[0-9]+ ready|\[LinuxAndroidXSession\]: slot [0-9]+ ready|\[LinuxSessions\]: reusing display :[0-9]+ for $key" \
        "\[LinuxSessions\]: could not open $key|\[LinuxDesktopScreen\]: could not complete" 600
    rc=$?
    sleep "$settle"
    if [ -n "$pattern" ]; then
        alive=$(ps -A -o ARGS 2>/dev/null | grep libproot-linux | grep "distros/$d/rootfs" | grep -cE "$pattern")
    else
        alive=$(guest_count "$d")
    fi
    evidence "$d" "$what" "$since"
    secs=$(( $(date +%s) - t0 ))
    if [ $rc -ne 0 ]; then result "$d" "$what" FAIL "$secs" "$key did not open"
    elif [ "$alive" -lt 1 ]; then result "$d" "$what" FAIL "$secs" "$key exited within ${settle}s"
    else result "$d" "$what" PASS "$secs" "$key window up, running after ${settle}s"
    fi
    [ $rc -eq 0 ] && [ "$alive" -ge 1 ]
}

# stop_app <distro> <desktop entry> <result name>: stops it from its Linux Apps row, the way a
# user ends a session left running.
stop_app() {
    d=$1; entry="${1}__$2"; what=$3
    if [ "$d" = ubuntu ]; then key=$2; else key="$d:$2"; fi
    am start --user "$USER_ID" -n "$ACT" -a app.gamenative.action.LINUX_APPS >/dev/null 2>&1
    sleep 4
    if tap_id "linux_app_stop_$entry" linux_apps_screen; then
        sleep 6; ui_dump
        if ui_has_id "linux_app_stop_$entry"; then result "$d" "$what" FAIL 6 "$key still listed as running"
        else result "$d" "$what" PASS 6 "$key stopped from Linux Apps"
        fi
    else
        result "$d" "$what" FAIL 0 "no stop button while $key was running"
    fi
}

# E: each browser installs from the terminal the way the Linux Apps hint says, opens from Linux
# Apps and stays up, then is removed again (browsers are large and the suite runs often).
BROWSERS="firefox chromium chrome"
test_browsers() {
    d=$1
    status "RUNNING $d browsers"
    await_terminal "$d"
    probes=""; for b in $BROWSERS; do probes="$probes br_$b"; done
    run_probe "$d" $probes
    back_to_android
    for b in $BROWSERS; do
        grep -q "^$d	probe-br_$b	PASS" "$RUN/results.tsv" || continue
        # The first of its desktop entries that Linux Apps lists.
        am start --user "$USER_ID" -n "$ACT" -a app.gamenative.action.LINUX_APPS >/dev/null 2>&1
        sleep 4
        name=""
        for candidate in $(cat "$SHARED/results/$d/br_$b.entry" 2>/dev/null); do
            if ui_find "linux_app_${d}__$candidate" linux_apps_screen >/dev/null; then name=$candidate; break; fi
        done
        if [ -z "$name" ]; then
            evidence "$d" "open-$b" "$(now)"
            result "$d" "open-$b" FAIL 0 "none of its entries ($(tr '\n' ' ' < "$SHARED/results/$d/br_$b.entry")) is listed in Linux Apps"
            continue
        fi
        case $b in chrome) pattern=google-chrome ;; *) pattern=$b ;; esac
        if open_app "$d" "$name" "open-$b" 20 "$pattern"; then
            stop_app "$d" "$name" "stop-$b"
        else
            stop_app "$d" "$name" "stop-$b" >/dev/null 2>&1
        fi
    done
    back_to_android
    await_terminal "$d"
    probes=""; for b in $BROWSERS; do probes="$probes rmbr_$b"; done
    run_probe "$d" $probes
    back_to_android
}

# G: managing what runs. An app opened from Linux Apps is listed under Running on Linux Distros,
# restarts from there and stops from there.
test_manage() {
    d=$1; since=$(now)
    status "RUNNING $d manage"
    clear_stub_tasks
    am start --user "$USER_ID" -n "$ACT" -a app.gamenative.action.LINUX_APPS >/dev/null 2>&1
    sleep 4
    # XTerm stays up on its own; a panel like Tint2 exits without a window manager.
    find_xterm() {
        name=""
        for candidate in debian-xterm xterm debian-uxterm uxterm; do
            if ui_find "linux_app_${d}__$candidate" linux_apps_screen >/dev/null; then name=$candidate; return 0; fi
        done
        return 1
    }
    if ! find_xterm; then
        # A fresh or reset distro has none yet.
        back_to_android
        await_terminal "$d"
        run_probe "$d" xterm
        back_to_android
        am start --user "$USER_ID" -n "$ACT" -a app.gamenative.action.LINUX_APPS >/dev/null 2>&1
        sleep 4
        tap_id linux_apps_refresh linux_apps_screen; sleep 4
        find_xterm
    fi
    if [ -z "$name" ]; then result "$d" running-listed FAIL 0 "no $d XTerm to open"; return 1; fi
    open_app "$d" "$name" manage-open 5 || return 1
    if [ "$d" = ubuntu ]; then key=$name; else key="$d:$name"; fi
    tag=$(echo "$key" | sed 's/[^A-Za-z0-9.-]/_/g')

    open_distros
    if ui_find "linux_running_$tag" linux_distros_screen >/dev/null; then
        result "$d" running-listed PASS 0 "$key listed under Running"
    else
        evidence "$d" running-listed "$since"
        result "$d" running-listed FAIL 0 "$key not listed under Running on Linux Distros"; return 1
    fi

    restarted=$(now)
    tap_id "linux_running_restart_$tag" linux_distros_screen
    wait_log "$restarted" "\[LinuxSessions\]: opening $key " "\[LinuxSessions\]: could not open $key" 120
    rc=$?
    sleep 5; open_distros
    if [ $rc -eq 0 ] && ui_find "linux_running_$tag" linux_distros_screen >/dev/null; then
        result "$d" running-restart PASS 0 "$key restarted from Running"
    else
        evidence "$d" running-restart "$restarted"
        result "$d" running-restart FAIL 0 "$key did not come back after Restart"
    fi

    tap_id "linux_running_stop_$tag" linux_distros_screen
    sleep 6; ui_dump
    if ui_has_id "linux_running_$tag"; then
        evidence "$d" running-stop "$since"
        result "$d" running-stop FAIL 6 "$key still listed after Stop"
    else
        result "$d" running-stop PASS 6 "$key stopped from Running"
    fi
    back_to_android

    # Uninstall through edit mode: htop, installed from the terminal for the purpose.
    await_terminal "$d"
    run_probe "$d" htop
    back_to_android
    grep -q "^$d	probe-htop	PASS" "$RUN/results.tsv" || return 1
    since=$(now); t0=$(date +%s)
    am start --user "$USER_ID" -n "$ACT" -a app.gamenative.action.LINUX_APPS >/dev/null 2>&1
    sleep 4
    tap_id linux_apps_refresh linux_apps_screen; sleep 4
    tap_id linux_apps_edit linux_apps_screen; sleep 1
    if ! tap_id "linux_app_select_${d}__htop" linux_apps_screen; then
        evidence "$d" app-uninstall "$since"
        result "$d" app-uninstall FAIL 0 "htop not selectable in edit mode"; back_to_android; return 1
    fi
    tap_id linux_apps_action_uninstall linux_apps_screen
    waited=0
    while [ $waited -lt 120 ]; do
        ui_dump; ui_has_id dialog_confirm && break
        sleep 3; waited=$((waited + 3))
    done
    if ! c=$(ui_center_id dialog_confirm); then
        evidence "$d" app-uninstall "$since"
        result "$d" app-uninstall FAIL "$(( $(date +%s) - t0 ))" "no uninstall confirmation"; back_to_android; return 1
    fi
    set -- $c; input tap "$1" "$2"
    wait_log "$since" "\[LinuxAppUninstaller\]: removed \[htop\] from $d" "\[LinuxAppUninstaller\]: uninstall failed" 600
    rc=$?
    sleep 4
    am start --user "$USER_ID" -n "$ACT" -a app.gamenative.action.LINUX_APPS >/dev/null 2>&1
    sleep 4
    secs=$(( $(date +%s) - t0 ))
    if [ $rc -ne 0 ]; then
        evidence "$d" app-uninstall "$since"
        result "$d" app-uninstall FAIL "$secs" "uninstall did not report removing htop"
    elif ui_find "linux_app_${d}__htop" linux_apps_screen >/dev/null; then
        evidence "$d" app-uninstall "$since"
        result "$d" app-uninstall FAIL "$secs" "htop removed but still listed"
    else
        result "$d" app-uninstall PASS "$secs" "htop uninstalled from Linux Apps edit mode"
    fi
    back_to_android

    # Reset through the Linux Distros edit mode: a file left in the distro is gone afterwards.
    await_terminal "$d"
    run_probe "$d" mark
    back_to_android
    grep -q "^$d	probe-mark	PASS" "$RUN/results.tsv" || return 1
    since=$(now); t0=$(date +%s)
    open_distros
    tap_id distros_edit linux_distros_screen; sleep 1
    if ! tap_id "distro_reset_$d" linux_distros_screen; then
        evidence "$d" distro-reset "$since"
        result "$d" distro-reset FAIL 0 "no Reset in edit mode"; back_to_android; return 1
    fi
    sleep 2; ui_dump
    if ! c=$(ui_center_id dialog_confirm); then
        evidence "$d" distro-reset "$since"
        result "$d" distro-reset FAIL 0 "no reset confirmation"; back_to_android; return 1
    fi
    set -- $c; input tap "$1" "$2"
    wait_log "$since" "\[LinuxDistros\]: reset $d" "\[LinuxDistros\]: $d reset failed|\[LinuxDistrosScreen\]: $d failed" 5400
    rc=$?
    evidence "$d" distro-reset "$since"
    tap_id distros_edit linux_distros_screen
    back_to_android
    if [ $rc -ne 0 ]; then
        result "$d" distro-reset FAIL "$(( $(date +%s) - t0 ))" "reset did not finish"; return 1
    fi
    await_terminal "$d"
    run_probe "$d" unmarked
    back_to_android
    if grep -q "^$d	probe-unmarked	PASS" "$RUN/results.tsv"; then
        result "$d" distro-reset PASS "$(( $(date +%s) - t0 ))" "reset from Linux Distros edit mode; reinstalled clean"
    else
        result "$d" distro-reset FAIL "$(( $(date +%s) - t0 ))" "reset finished but the distro kept its files"
    fi
}

# toggle_stub <entry> <drawer|undrawer> <up|down>: taps the row's drawer button and waits up to a
# minute for the number of installed stub packages to go up or down.
toggle_stub() {
    before=$(pm list packages --user "$USER_ID" 2>/dev/null | grep -c 'app.gamenative.stub.')
    ui_dump
    tap_id "linux_app_$2_$1" linux_apps_screen || return 1
    waited=0
    while [ $waited -lt 60 ]; do
        sleep 5; waited=$((waited + 5))
        n=$(pm list packages --user "$USER_ID" 2>/dev/null | grep -c 'app.gamenative.stub.')
        if [ "$3" = up ] && [ "$n" -gt "$before" ]; then sleep 2; return 0; fi
        if [ "$3" = down ] && [ "$n" -lt "$before" ]; then sleep 2; return 0; fi
    done
    return 1
}

# ---------------------------------------------------------------- report

write_report() {
    r=$RUN/report.md
    {
        echo "# GameNative Linux test report"
        echo
        echo "- Device: $(getprop ro.product.manufacturer) $(getprop ro.product.model), Android $(getprop ro.build.version.release), build $(getprop ro.build.date)"
        echo "- GameNative: $(dumpsys package $PKG | grep -m1 versionName | tr -d ' ') $(dumpsys package $PKG | grep -m1 versionCode | awk '{print $1}')"
        echo "- Run: $(basename "$RUN"), user $USER_ID, distros: $DISTROS, tests: $TESTS"
        echo
        echo "## Summary"
        echo
        cols=$(cut -f2 "$RUN/results.tsv" | awk '!seen[$0]++' | tr '\n' ' ')
        printf '| Distro |'; for c in $cols; do printf ' %s |' "$c"; done; echo
        printf '|---|'; for c in $cols; do printf -- '---|'; done; echo
        for d in $DISTROS; do
            printf '| %s |' "$d"
            for c in $cols; do
                v=$(awk -F'\t' -v d="$d" -v c="$c" '$1==d && $2==c {s=$3} END {print s}' "$RUN/results.tsv")
                printf ' %s |' "${v:--}"
            done
            echo
        done
        echo
        echo "## Results"
        echo
        echo "| Distro | Test | Status | Seconds | Note |"
        echo "|---|---|---|---|---|"
        awk -F'\t' '{gsub(/\|/, "/", $5); printf "| %s | %s | %s | %s | %s |\n", $1, $2, $3, $4, $5}' "$RUN/results.tsv"
        echo
        echo "## Failures"
        awk -F'\t' '$3=="FAIL" {print $1 "\t" $2}' "$RUN/results.tsv" | while IFS='	' read -r d t; do
            echo
            echo "### $d $t"
            f=$RUN/logs/$d-$t.log; [ -f "$f" ] || f=$RUN/logs/$d-$t.logcat
            if [ -f "$f" ]; then echo '```'; tail -n 25 "$f"; echo '```'; fi
            [ -f "$RUN/shots/$d-$t.png" ] && echo "Screenshot: shots/$d-$t.png"
        done
    } > "$r"
}

# ---------------------------------------------------------------- main

# Two runs would type into each other's windows.
if [ -f "$W/run.pid" ] && kill -0 "$(cat "$W/run.pid")" 2>/dev/null; then
    log "another run (pid $(cat "$W/run.pid")) is in progress; not starting"
    status "DONE busy $RUN"
    exit 3
fi
echo $$ > "$W/run.pid"
log "Linux test run: user=$USER_ID distros=[$DISTROS] tests=[$TESTS] keep=$KEEP"
# Package installs under PRoot flood the log with SELinux audit lines; with a small ring buffer
# the capture below falls behind and lines it waits for are pruned unread. Restored on exit.
LOG_SIZE=$(logcat -b main -g 2>/dev/null | sed -n 's/.*ring buffer is \([0-9]*\) *\([KM]\)i*B.*/\1\2/p' | head -n 1)
logcat -b main -G 16M 2>/dev/null && log "main log buffer 16M (was ${LOG_SIZE:-unknown})"
# Only the lines the tests read, so a long run does not fill the disk.
logcat -v threadtime -T 1 2>/dev/null |
    grep --line-buffered -E "app\.gamenative|proot|ActivityTaskManager|AndroidRuntime" > "$RUN/logcat.txt" &
LOGCAT_PID=$!
# SmartDock pops up notifications (its own "Dock service is running" among them) in a window that
# uiautomator then dumps instead of GameNative's. Paused for the run, and only if it was on.
DOCK_LISTENER=cu.axel.smartdock/cu.axel.smartdock.services.NotificationService
DOCK_PAUSED=0
if dumpsys notification 2>/dev/null | grep -q "$DOCK_LISTENER (user: $USER_ID"; then
    cmd notification disallow_listener "$DOCK_LISTENER" "$USER_ID" && DOCK_PAUSED=1 && log "paused SmartDock notification popups"
fi
trap 'kill $LOGCAT_PID 2>/dev/null; rm -f "$W/run.pid"; [ -n "$LOG_SIZE" ] && logcat -b main -G "$LOG_SIZE"; [ $DOCK_PAUSED -eq 1 ] && cmd notification allow_listener "$DOCK_LISTENER" "$USER_ID"' EXIT
trap 'exit 1' INT TERM HUP
svc power stayon true
wake

for d in $DISTROS; do
    installed=1
    if has_test A && [ $NO_INSTALL -eq 0 ]; then
        test_install "$d" || installed=0
    elif ! is_installed "$d"; then
        result "$d" install SKIP 0 "not installed and installs are off"
        installed=0
    fi
    [ $installed -eq 1 ] || continue
    if has_test B || has_test C; then test_terminal_and_shell "$d"; fi
    if has_test D; then test_desktop "$d"; fi
    if has_test E; then test_browsers "$d"; fi
    if has_test F; then test_apps "$d"; fi
    if has_test G; then test_manage "$d"; fi
    if has_test A && [ $KEEP -eq 0 ]; then test_remove "$d"; fi
done

write_report
back_to_android
svc power stayon false
status "DONE $RUN"
log "Report: $RUN/report.md"
