---
name: googlebook-gamenative-debug
description: Build, install and debug GameNative x64 (Wine games and Linux app sessions) on an Android desktop device such as the Googlebook over wireless adb. Use when testing GameNative on the Googlebook, reading its logs or screenshots, launching Linux apps on it, or chasing Linux session, scaling, PRoot or Vulkan rendering issues there.
disable-model-invocation: true
---

# Debugging GameNative x64 on the Googlebook

## Device facts

- Stock Android desktop build (user build, SELinux Enforcing), Intel Xe GPU, 2880x1800 at 260 dpi.
- GameNative runs as a **user app** (`app.gamenative`) in **Android user 10**, not user 0. Pass
  `--user 10` to `am`, `content` and `pm` commands, or they act on the wrong user.
- Release builds are not debuggable: no `run-as`, and `/data/user/10/app.gamenative` is unreadable
  from adb. Diagnose through logcat, screenshots and `ps`, not by reading app files.
- The user may be using the device while you work. Say before sending input (`input tap/keyevent`),
  stop if the screen changes under you, and prefer asking them to click.

## Connect

On the device: Settings > System > Developer options > Wireless debugging. Use "Pair device with
pairing code" once per laptop, then connect to the port shown on the Wireless debugging screen
(it changes after reboots and toggles):

```bash
adb pair <ip>:<pairing-port>        # enter the 6-digit code
adb connect <ip>:<port>
export D=<ip>:<port>                 # every command below uses -s "$D"
adb -s "$D" shell getprop ro.build.version.release
```

## Build and install

From the GameNative repo, branch `bliss-x64`. After a fresh clone or pull:

```bash
git submodule sync --recursive && git submodule update --init --recursive
./gradlew --no-configuration-cache :app:assembleModernX64Release -PnoMinify=true -x lint -q
```

Sign with the **GameNative x64 release keystore** (kept off-repo; ask the user where it is on this
machine), then install over the existing app:

```bash
APKSIGNER=$(ls -d ~/Android/Sdk/build-tools/*/apksigner | tail -1)
"$APKSIGNER" sign --ks <keystore.jks> --ks-pass file:<password.txt> \
  --out /tmp/GameNativeX64.apk app/build/outputs/apk/modernX64/release/app-modernX64-release.apk
adb -s "$D" install -r -d /tmp/GameNativeX64.apk
```

- Never platform-sign GameNative and never use AOSP test keys.
- `INSTALL_FAILED_UPDATE_INCOMPATIBLE` means a different key. Do **not** uninstall to get past it
  without asking: that deletes the Linux userland, Wine containers and games.
- Installing kills running games and Linux windows. Check with the user first if they are mid-test.
- Bump `versionCode` in `app/build.gradle.kts` only when cutting a release (see Release below).

Native pieces that Gradle does not rebuild by itself:

- Vulkan compositor: `cmake --build app/.cxx-bliss-x64/x86_64 --target vulkan_renderer`, then copy
  `libvulkan_renderer.so` into `app/src/modernX64/jniLibs/x86_64/`.
- PRoot for the Linux guest: `scripts/build-x86_64-proot-linux.sh` (podman). It applies
  `scripts/patches/proot-linux/*.patch`; change PRoot by committing in the clone and exporting with
  `git format-patch`, never by hand-editing patches.

## Look at the device

Screenshots (take them before and after every visual change and show them to the user):

```bash
adb -s "$D" exec-out screencap -p > /tmp/shot.png
```

A black screenshot usually means the screen is off; wake it or retake.

Screen recordings the user made land in user 10's MediaStore:

```bash
adb -s "$D" shell "content query --user 10 --uri content://media/external/video/media --projection _id:_display_name:date_added" | tail -3
adb -s "$D" exec-out content read --user 10 --uri content://media/external/video/media/<id> > /tmp/rec.mp4
```

Logs. Timber lines look like `app.gamenative: I: [Tag]: message`:

```bash
adb -s "$D" logcat -c                                   # before reproducing
adb -s "$D" logcat -d | rg 'app\.gamenative' | rg '\[(LinuxSessions|LinuxAndroidXSession|LinuxProgramLauncher|LinuxRootfs|LinuxSessionActivity|LinuxDesktopScreen|linux-app|xsettingsd)\]'
adb -s "$D" logcat -d | rg -i 'FATAL|AndroidRuntime|tombstone|avc: denied'
```

`[linux-app]` and `[xsettingsd]` lines are the guest program's own stdout and stderr.

Guest processes (PRoot shows only the program name, and its own command line contains the whole
guest command, so never `pkill -f` a guest path):

```bash
adb -s "$D" shell "ps -A -o PID,PPID,NAME,ARGS | grep -E 'proot|xsettingsd|firefox' | grep -v grep | cut -c1-200"
```

## Launch Linux apps without touching the screen

```bash
adb -s "$D" shell am start --user 10 -n app.gamenative/.MainActivity \
  -a app.gamenative.action.LINUX_DESKTOP \
  --es linux_argv firefox --es linux_entry_id firefox --es linux_label Firefox
```

`linux_entry_id` is the `.desktop` file name without the extension and is the session key, so a
second launch with the same id brings the existing window forward instead of starting another.

In a Linux window, Back or Shift+Esc opens the side menu: other installed apps (each opens in its
own window), this app's size, the default size for all apps, keyboard, keep-running, close.

## Workflow

```
- [ ] Reproduce with logcat cleared, grab logs and a screenshot
- [ ] Find the cause in code (see reference.md for how the pieces fit)
- [ ] Fix, build, sign, install
- [ ] Reproduce again; screenshot the result for the user
- [ ] User confirms, then commit
```

## Repo rules

- Commit once the user has validated the change; one commit per finished task, message style
  `Fix ...` / `Add ...` / `Update ...` explaining why. Push only when asked, to the `bliss` remote
  (`git@github.com:Bliss-Bass/GameNative-x64.git`, branch `bliss-x64`).
- Release: bump `versionCode` in the same commit as the last change, annotated tag
  `v1.2.0-x64.<N>` with message `GameNative x64 1.2.0-x64.<N>`, push branch then tag. The tag
  triggers the Create Release workflow (`gh run list -R Bliss-Bass/GameNative-x64`).
- Submodules: push submodule commits to a remote that has them (for `lsfg-vk-android`, the
  `Bliss-Bass/lsfg-vk-android` fork) **before** pushing a GameNative commit that pins them; CI does
  not fetch submodules, so only fresh clones catch a missing commit.
- Do not run Soong/`m`/`make` builds for the Bass ISO tree from a debugging session.

## Additional resources

- How the Linux session, scaling and PRoot fit together, plus known issues: [reference.md](reference.md)
