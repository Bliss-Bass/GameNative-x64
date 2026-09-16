# vkcube-debug — DRI3 / present-path smoke container

A Custom Game folder that boots the in-app X server and launches Termux's
`vkcube` (Android ELF) instead of Wine. Use it to A/B `software` / `shm` / `dri3`
presentation without starting a real game.

## Layout (after install)

```text
…/CustomGames/vkcube-debug/
  .gamenative     # stable CUSTOM_GAME_424242
  vkcube          # Termux vulkan-tools x86_64 binary
  README.md
```

## Deploy to a device

From the GameNative repo root (device online, `adb root` optional but helpful):

```bash
./tools/vkcube-debug-container/install.sh 192.168.1.208:5555
```

Then in GameNative:

1. Library → refresh / open **vkcube-debug**
2. Settings → Performance → Frame presentation → **Direct (DRI3)**  
   (or `adb shell setprop debug.gamenative.presentation dri3`)
3. Launch the game
4. Watch logcat: `adb logcat -s DRI3:I XServerScreen:I`

Expect `pixmapFromDmaBufAhb ok` / `pixmapFromDmaBufVk ok`, or LINEAR fallback with
`opaque=true` and non-zero `headSum`.

## Rebuild the package binary only

```bash
./tools/vkcube-debug-container/fetch-vkcube.sh
# writes vkcube next to this README (gitignored if you prefer; install.sh fetches when missing)
```

## Notes

- Folder name must stay `vkcube-debug` (or still contain a file named `vkcube`).
- Executable path auto-detects to `vkcube`.
- Wine is skipped via `GUEST_PROGRAM_LAUNCHER_COMMAND`.
