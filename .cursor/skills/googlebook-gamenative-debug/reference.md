# GameNative x64 on the Googlebook: how it fits together

All source paths are under `app/src/main/java/app/gamenative/` unless they start with `app/` or
`scripts/`.

## Linux app sessions

- `linux/LinuxSessions.kt`: process-wide registry. One session per application, keyed by desktop
  entry id (`keyFor`). `open` reuses a running session, `applyScale` retells sessions their size.
- `linux/LinuxAndroidXSession.kt` (default backend): an in-process Winlator X server per app,
  slots 10 to 25. Guests always see `DISPLAY=:0`; each slot binds its own socket dir
  (`files/linux/x11/<slot>/tmp/.X11-unix`) onto the guest's `/tmp/.X11-unix`. No guest window
  manager; `installAppWindowFitter` makes top-level windows fill the screen and centres dialogs.
- `linux/LinuxDisplaySession.kt`: Xtigervnc/RFB fallback backend (Settings > Linux display backend).
- `ui/LinuxSessionActivity.kt`: one Android task per Linux app (`documentLaunchMode=intoExisting`,
  data `gamenative://linux/<entryId>`). `dispatchKeyEvent` forwards keys to the X view.
- `ui/screen/linux/LinuxDesktopScreen.kt`: sizes the X screen to the window's client area and
  hosts `LinuxSessionMenu.kt` (the side menu).
- `linux/LinuxXServerView.kt`: `onKeyPreIme` sends keys to X **before** the activity sees them;
  `keyInterceptor` is how the menu claims Back/Shift+Esc first. With predictive back (targetSdk
  36), a Back *key* that a view or activity handles never reaches `BackHandler`, so menu keys are
  toggled by hand (`toggleLinuxMenu`).
- `linux/LinuxProgramLauncher.kt`: builds the PRoot command (`--kill-on-exit --root-id`, binds,
  `/usr/bin/env K=V ... argv`). Command strings are split on whitespace, so no shell quoting:
  write a script into the rootfs and run `/bin/sh /path/script.sh` instead.
- `linux/LinuxRootfs.kt`: installs and configures the guest (`configureSession` runs per launch):
  xsettings, Xdefaults, Firefox prefs and shim, OpenShot shim, ffmpeg links for Firefox.
- `linux/LinuxAppScanner.kt`: reads `.desktop` files from the rootfs; `launchArgv` wraps terminal
  apps in `xterm -e`.

## Scaling

- `linux/LinuxDisplayScale.kt`: `xdpi = density * 96/160 * 1.5 * percent/100` (260 dpi at 100%
  gives 234). The 1.5 (`DESKTOP_TO_ANDROID_VISUAL`) lifts GTK/Qt widgets to Material touch size.
- `deviceScale(xdpi)` undoes the lift for browsers: 260 dpi gives 1.625, Android's own density.
- Per-app overrides: `linux/LinuxAppSettings.kt`, JSON at `files/linux/app-settings.json` keyed by
  session key; global default `PrefManager.linuxUiScalePercent`.
- Per display (Android X), under guest `/root/.config/gamenative/x11-<slot>/`:
  `xsettingsd` (Xft/DPI), `Xresources` (via `XENVIRONMENT`), `xsettingsd.sh` which writes
  `xsettingsd.pid`. `setDpi` rewrites these and sends SIGHUP to that pid only. GTK follows live;
  Qt (`QT_FONT_DPI`), xterm and Firefox pick up a new size on restart.
- Firefox:
  - `defaults/pref/gamenative-scale.js`: `layout.css.devPixelsPerPx` (shared fallback),
    `ui.textScaleFactor` 100 and `browser.display.os-zoom-behavior` 0. Without the last two
    Firefox stacks GTK's text scale (about 2.4x) on top and pages come out huge.
  - `gamenative.cfg` autoconfig: per-window `devPixelsPerPx` from env `GAMENATIVE_DEVICE_SCALE`.
  - `/usr/local/bin/firefox` shim sets `GDK_DPI_SCALE=0.667`, so toolbar and tab fonts drop the 1.5
    widget lift and match the page scale.
- Verify scale changes from the log: `[LinuxAndroidXSession]: slot N dpi set to D (P%)`.

## PRoot and the guest on stock Android

- SELinux denies `link()` on app data for untrusted apps; dpkg needs hard links. Our PRoot patch
  (`scripts/patches/proot-linux/0001-...`) copies the file when the host denies a hard link.
  `--link2symlink` is deliberately not used (host-absolute `.l2s` targets break the guest).
- `installDisplaySession` runs `dpkg --configure -a` before `apt-get install` to finish
  interrupted installs.
- Symlinks inside the rootfs point at guest paths, so from Android they look dangling:
  `File.exists()` is false. Check `Files.isSymbolicLink` before recreating links.
- PRoot does not virtualise pids: `$$` in a guest shell is the host pid, and the app can signal it
  with `android.os.Process.sendSignal`.

## Wine games: Vulkan compositor on Intel Xe

- `app/src/main/cpp/winlator/VulkanRendererContext.cpp`, 2 frames in flight. Xe has no implicit
  sync, so a dma-buf texture retired while another in-flight frame still samples it reads as black.
  Retired textures wait in `deleteQueue` until every frame that could see them has completed
  (`retiredAfter` vs `slotSnapshot`).
- `PresentExtension.java` holds a flip until the next present replaces it, then calls
  `waitForSampling()` before releasing the idle notification.
- Frame failures log once a second via `logFrameFailure(step, result)`.

## Known issues

- `xterm` exits with `Error 14, errno 38 ... spawn: open() failed on /dev/tty` on stock Android;
  UXTerm (`debian-uxterm`) starts. Not yet investigated.
- Firefox shows "Restore Session" after GameNative is reinstalled, since the install kills it.
- Release builds cannot be inspected with `run-as`; add Timber logging and rebuild instead.
