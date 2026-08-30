# Linux apps in GameNative — working notes

Running notes for the Linux userland work. Not a design doc; the plan lives with the task
list.

## Where the work stands

Tagged `linux-apps-phase4-app-entries` in both this repo and `vendor_ax86-lite`.

Committed: PRoot for x86_64, the Ubuntu rootfs installer, the Termux-based terminal
screen, the graphical session packages (Xtigervnc + openbox + xsettingsd from apt), the
session manager, the RFB client and presenter, the Linux session screen with both an app
and a minimal-desktop profile, `.desktop` scanning with a Linux Apps screen, shared-storage
binding into the guest, and app-drawer entries for both Linux apps and installed games.

Verified end to end on the tablet: a session comes up on its own display and loopback
port, the presenter shows it, touch and mouse reach the guest, keystrokes reach it
(`whoami` typed from Android returned `root` in xterm), and resizing the app's freeform
window resizes the X screen, with openbox relayouting the client to match. A guest process
can write to `/mnt/storage/internal/Download` and the file appears in `/sdcard/Download`.
Galculator and Half-Life 2 both have drawer entries that launch them, and **HL2 reaches its
main menu and keeps rendering** when started from its entry.

Remaining, in rough order: presentation latency (see
`X86_64_VULKAN_PROVISIONING.md` — the missing x86_64 SysV interposer is the near-term
item), repointing the ROM `linuxwindow` addon and deleting the dead Debian/Wayland
scaffolding, then the userland gaps that have no bridge yet at all: audio, GL for demanding
apps, camera and Bluetooth. Session polish is queued separately (double cursor, colours
arriving lighter than sent, panel noise, a wallpaper, and the app's own name and icon in
Android's title bar).

## Things that cost time, so they are worth remembering

- **`Socket().apply { connect(InetSocketAddress(host, port), …) }` connects to port 0.**
  Inside `apply` the receiver is the socket, so `port` means `Socket.getPort()`, which is
  zero until connected. Resolve the address outside the block.
- **PRoot's `--link2symlink` corrupts the rootfs.** It writes host-absolute targets into
  the `.l2s` symlinks it creates, which do not resolve inside the guest; dpkg's
  unpack-and-rename left `perl` dangling, which broke debconf and every postinst after it.
  `/data` is ext4 and takes real hardlinks, so the flag is gone. An install made under it
  cannot be repaired in place -- hence the layout version bump.
- **`ProcessHelper.exec` never drains the child's pipes.** A server that logs will
  eventually block in `write()`, and its own account of a failed start is lost. Use
  `LinuxProgramLauncher.start`, which logs under a tag.
- **A bare TCP connect is not proof the X server started.** A dying server from an earlier
  session can still hold the port while the new one, unable to bind, has already exited.
  Readiness reads the `RFB 003.008` greeting.
- **`DisposableEffect(session)` tears the session down as it is created**, because
  assigning the session is itself a key change. Key it on `Unit`.
- **The window caption covers the top of the surface.** Before the inset fix, xterm's
  prompt was invisible while everything else rendered, which looked like a decode bug for
  far too long. A centred marker rectangle showing while a corner one did not is what
  finally identified it.
- **xterm takes about five seconds to map.** Sampling the framebuffer before that shows an
  empty black root and proves nothing.
- **Signing with an AndroidKeyStore key depends on provider order, which is not stable.**
  `Signature.getInstance("SHA256withRSA")` picked the bundled Bouncy Castle provider, which
  rejects a keystore-backed key outright (`not a RSAPrivateKey instance`). It worked for
  Linux app stubs and failed for game stubs purely because other libraries had loaded by
  then and shifted the order. `ApkSigner.signerFor` now walks the providers and takes the
  first that accepts the key.
- **A `sharedUserId="android.uid.system"` app signed with the wrong key bootloops the
  device**, and needs a full OS reinstall to recover. Incremental builds silently used AOSP
  test-keys unless `TARGET_USE_CUSTOM_KEYS=true`. Verify certs match before pushing to
  `/system`; there is no soft failure mode here.
- **`USER_ACTION_NOT_REQUIRED` does not skip Play Protect.** It suppresses the confirmation
  prompt, not verification, so installs still stall on a scan. Only running as system uid
  and calling the hidden `disableVerificationForUid` makes it genuinely silent.
- **A `PendingIntent` for a non-exported receiver has to name the class.** An implicit one
  never arrives, and the session then looks like it committed into nothing.
- **An asset called `classes.dex` is silently dropped from the APK.** It collides with the
  app's own dex; the trampoline ships as `stub/trampoline.dex` instead.
- **`am broadcast` from a shell is not equivalent to a system broadcast.** Simulated
  `PACKAGE_FULLY_REMOVED` never reached the receiver even with
  `FLAG_INCLUDE_STOPPED_PACKAGES`; a real `adb uninstall` did. Test removal paths for real.
- **A custom game's icon is a local `.ico`, and only the app's own Coil loader can decode
  it** — the ICO decoder is registered there. Building a throwaway `ImageLoader(context)`
  per call, as `loadGameArtwork` used to, cannot read it and shares none of the caches.
- **`canRead()` on the storage root returns true without `MANAGE_EXTERNAL_STORAGE`**, which
  yields a bind mount that exists and reads nothing. `isExternalStorageManager()` is the
  only honest check. Granting it via `appops` takes effect without an app restart, but the
  banner needs a window-focus trigger to notice.
- **tint2 resolves background ids while parsing**, so a config that references one before
  defining it segfaults rather than complaining. Ordering is load-bearing.
- **`adb shell input` coordinates are device pixels, not screenshot pixels** — the trap
  already noted under Testing, hit again while driving HL2's menu. Taps land silently
  nowhere and read as the app ignoring input. Multiply by 2.109 on this panel, or read
  bounds straight out of `uiautomator dump`.

## Testing notes

- Screenshots from `adb exec-out screencap` are full resolution (2160x1440 here) even
  though the chat renders them about 1024 wide. Read coordinates off a 1024-wide preview
  and multiply by 2.109; assuming the preview matches whatever width the file was resized
  to puts every tap ~5% off, which still hits wide text buttons and reliably misses icon
  buttons, so it looks like the app ignoring input rather than a bad aim.

- Injected taps do reach the app once the coordinates are right. Drive the desktop with:

```
adb shell am start -n app.gamenative/.MainActivity \
  -a app.gamenative.action.LINUX_DESKTOP --es linux_argv xterm
adb shell am start -n app.gamenative/.MainActivity \
  -a app.gamenative.action.LINUX_TERMINAL
```

  Either works from a cold start now: the request is held and replayed once the UI is
  composed, the way game launches already were. Before that it was emitted into an event bus
  nobody was collecting from yet and silently dropped, which a launcher shortcut would have
  hit every time.

- A screen that tears something down on leaving composition reports it through its exit
  callback, which arrives after its replacement is showing. That is what popped the desktop
  ~20s after launching it over an open terminal: the terminal's shell died, and its `onBack`
  called `navigateUp` on whatever was now on top. Exit callbacks go through
  `navigateUpFrom(entry)`, which pops only while that entry is still current.

- Build and install: `./gradlew :app:assembleModernX64Debug` then
  `adb install -r app/build/outputs/apk/modernX64/debug/app-modernX64-debug.apk`.

- To check the guest's pixels independently of the presenter, forward the port and grab a
  frame: `adb forward tcp:15950 tcp:5950`. The throwaway scripts used for this were
  `/tmp/rfbgrab.py` (one full frame to a PPM) and `/tmp/rfbkey.py` (click, type, report
  damage); both are small enough to rewrite when needed.

- A clean install has been re-validated from a wiped userland: download, unpack, apt, and a
  live prompt, with `perl` a real hardlink, no `.l2s` leftovers, and no half-configured
  packages. `man-db` is silent now that its index rebuild is preseeded off -- the postinst
  drops privileges with `setpriv`, which PRoot's fake root cannot do.

- `xterm` and the fonts are shipped rather than installed by hand, and a userland missing
  them is repaired in place instead of re-downloaded, so the package list can grow without
  a layout bump. The session's config files are rewritten on every start for the same
  reason.

- The X server is told the panel's density. Without it every Xft client draws at about half
  size, and xterm ignores dpi entirely until a scalable font is named in `~/.Xdefaults`.

- Edit source only through the editor's tools. Shell edits (`sed`, heredocs) were silently
  reverted by stale IDE buffers earlier in this work.
