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

# Saves this app's logcat since <since> and a screenshot, named <distro>-<test>.
evidence() {
    logcat -d -T "$3" 2>/dev/null | grep -E "app\.gamenative|$PKG|proot" > "$RUN/logs/$1-$2.logcat"
    screencap -p "$RUN/shots/$1-$2.png"
}

ui_dump() { uiautomator dump "$W/ui.xml" >/dev/null 2>&1; }

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

# Finds <id>, scrolling the screen <root> when it is off screen. Prints its centre.
ui_find() {
    id=$1; root=$2; tries=0
    while [ $tries -lt 8 ]; do
        ui_dump
        if c=$(ui_center_id "$id"); then echo "$c"; return 0; fi
        r=$(ui_center_id "$root") || return 1
        set -- $r
        if [ $tries -lt 4 ]; then
            input swipe "$1" $(( $2 + 250 )) "$1" $(( $2 - 250 )) 300
        else
            input swipe "$1" $(( $2 - 250 )) "$1" $(( $2 + 250 )) 300
        fi
        tries=$((tries + 1)); sleep 1
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
        out=$(logcat -d -T "$since" 2>/dev/null)
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
    for t in $tests; do rm -f "$SHARED/results/$d/$t.done" "$SHARED/results/$d/$t.result"; done
    ui_dump
    c=$(ui_center_id terminal_screen) || return 1
    set -- $c; input tap "$1" "$(( $2 + 100 ))"; sleep 1
    input text "sh%s$GUEST/probe.sh%s$(echo "$tests" | sed 's/ /%s/g')"
    input keyevent KEYCODE_ENTER
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

# C: the terminal opens on the distro and runs the probe; B: shell basics through the probe.
test_terminal_and_shell() {
    d=$1; since=$(now); start=$(date +%s)
    status "RUNNING $d terminal"
    open_terminal "$d"
    waited=0
    while [ $waited -lt 1800 ]; do
        ui_dump
        ui_has_id terminal_screen && ! ui_has_id terminal_progress && break
        sleep 10; waited=$((waited + 10))
    done
    ui_dump
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
    am start --user "$USER_ID" -n "$ACT" -a app.gamenative.action.LINUX_APPS >/dev/null 2>&1
    sleep 6; ui_dump
    entries=$(grep -o 'resource-id="linux_app_[^"]*"' "$W/ui.xml" | sed 's/resource-id="linux_app_//; s/"$//' |
        grep -vE '^(restart|stop|drawer|undrawer|pin)_')
    if [ -z "$entries" ]; then
        evidence "$d" apps "$since"
        result "$d" apps FAIL 0 "Linux Apps lists no apps"; back_to_android; return 1
    fi
    entry=$(echo "$entries" | grep -m1 -E '(^|-)xterm$' || echo "$entries" | head -n 1)
    result "$d" apps-list PASS 0 "$(echo "$entries" | wc -l | tr -d ' ') apps listed"

    # A cold launch each time: a session left running would only bring its window forward.
    if ui_has_id "linux_app_stop_$entry"; then
        tap_id "linux_app_stop_$entry" linux_apps_screen; sleep 6
    fi
    launched=$(now); t0=$(date +%s)
    tap_id "linux_app_$entry" linux_apps_screen
    # A window already open for the app is brought forward and its session reused.
    wait_log "$launched" "\[LinuxDisplaySession\]: display :[0-9]+ ready|\[LinuxAndroidXSession\]: slot [0-9]+ ready|\[LinuxSessions\]: reusing display :[0-9]+ for $entry" \
        "\[LinuxSessions\]: could not open $entry" 180
    rc=$?
    sleep 10
    alive=$(guest_count "$d")
    evidence "$d" app-window "$since"
    secs=$(( $(date +%s) - t0 ))
    if [ $rc -ne 0 ]; then result "$d" app-window FAIL "$secs" "$entry did not open"
    elif [ "$alive" -lt 1 ]; then result "$d" app-window FAIL "$secs" "$entry exited"
    else result "$d" app-window PASS "$secs" "$entry window up"
    fi

    # Stop it from its Linux Apps row, the way a user ends a session left running.
    am start --user "$USER_ID" -n "$ACT" -a app.gamenative.action.LINUX_APPS >/dev/null 2>&1
    sleep 4
    if tap_id "linux_app_stop_$entry" linux_apps_screen; then
        sleep 6; ui_dump
        if ui_has_id "linux_app_stop_$entry"; then result "$d" app-stop FAIL 6 "$entry still listed as running"
        else result "$d" app-stop PASS 6 "$entry stopped from Linux Apps"
        fi
    else
        result "$d" app-stop FAIL 0 "no stop button while $entry was running"
    fi

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

log "Linux test run: user=$USER_ID distros=[$DISTROS] tests=[$TESTS] keep=$KEEP"
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
    if has_test F; then test_apps "$d"; fi
    if has_test A && [ $KEEP -eq 0 ]; then test_remove "$d"; fi
done

write_report
back_to_android
svc power stayon false
status "DONE $RUN"
log "Report: $RUN/report.md"
