# Bliss x86_64 Android host port

Branch **`bliss-x64`** tracks GameNative changes for Bass:Lineout x86_64 tablets
(ax86). Upstream default remains ARM64 + Box64/FEX.

## Build (ax86)

```bash
./gradlew :app:assembleModernX64Release
```

JNI prebuilts for `x86_64` are staged under `app/src/modernX64/jniLibs/x86_64/`.
Build open-source libs with `./scripts/build-x86_64-jni.sh` (see README there).
PulseAudio and x86_64 bionic libs (FreeType/fontconfig, **X11 client libs** for
`winex11.drv`, and a `libvulkan.so.1` → `/system/lib64/libvulkan.so` symlink) use
`./scripts/build-x86_64-pulse.sh` and `./scripts/build-x86_64-bionic-libs.sh`;
assets land in `app/src/modernX64/assets/`.

**Blockers for full runtime:** proprietary ARM-only `libredirect-bionic-wx.so`,
`libhook_impl.so`, `libmain_hook.so`, plus `libevshim.so` (SDL2 headers) and
`libwinlator_11.so`. Modern Bionic skips redirect preload on x86_64 until upstream
ships x86_64 redirect binaries.

## ax86 graphics

See [ax86-graphics.md](ax86-graphics.md) for Mesa/Vulkan boot props on Lineout images.

## LSFG-VK (frame generation) on x86_64

Bionic containers can arm Lossless Scaling FG on Mesa (Intel/AMD). Rebuild the layer with
`./scripts/build-x86_64-lsfg.sh` (submodule `lsfg-vk-android` at **v1.0.4-android**); the
APK ships `liblsfg-vk-layer.so` under `modernX64/jniLibs/x86_64/` plus
`assets/lsfg_vk/android_x86_64/`.

Requirements: Bionic container, Graphics → enable LSFG, Steam app **993090**
(`Lossless.dll`) installed. Launch logs `LsfgVkManager: LSFG armed…`; live proof is
`~/.config/lsfg-vk/stats.txt` (`fps` ≈ `base` × multiplier). Prefer DRI3 presents
(`debug.gamenative.presentation=dri3`) so FG frames stay cheap.

## Upstream

Contribute `HostCpu`, launcher branches, and CI in small PRs to
[utkarshdalal/GameNative](https://github.com/utkarshdalal/GameNative).

Product integration (preinstall APK): Bliss-Bass `gamenative` addon in
`vendor/ax86-lite/addons/gamenative/`.

## Port debug builds (`BLISS_PORT_DEBUG`)

The `modernX64` flavor sets `BuildConfig.BLISS_PORT_DEBUG = true`. All other
flavors leave it `false`.

When the flag is **true** (ax86 port / dev builds):

- PostHog is not initialized; `Telemetry.capture()` is a no-op
- Game-run / compatibility API submissions are skipped (`GameFeedbackUtils`)
- Automatic exit-feedback prompts after a game session are suppressed
- Guest launches enable `WINEDEBUG=+seh,+module,+d3d9,+dxgi,+vulkan`, `PROTON_LOG=1`,
  and `DXVK_LOG_LEVEL=info`; Wine stderr is forwarded to logcat as `WineGuest`

## x86_64 system Vulkan (`HostGraphicsEnv`)

On x86_64 hosts with graphics driver **System**, `HostGraphicsEnv` strips ARM
Turnip/Wrapper env vars (`TU_DEBUG`, `ZINK_*`, `VK_LAYER_PATH`, `WRAPPER_*`, …)
so DXVK uses the device Mesa ICD (`ro.hardware.vulkan=intel` on ax86). It also
ensures `WINEDLLOVERRIDES` includes native DXVK DLLs for D3D9–D3D11.

## x86_64 X11 display (`HostDisplayEnv`)

Without ARM `libredirect-bionic-wx.so`, guest libX11 cannot reach `/tmp/.X11-unix`
when `DISPLAY=:0`. `HostDisplayEnv` points `DISPLAY` at the absolute in-app
socket (`{imagefs}/tmp/.X11-unix/X0`), aligns `TMPDIR`/`XDG_RUNTIME_DIR` with
that tree, and unsets `WINE_X11FORCEGLX` (no GLX on the Android X server).

## Game session memory (`GameSessionMemory`)

While a container is running, `GameManager.setGameState(MODE_GAME_PLAYING)` is
set and Coil bitmap caches are dropped on memory pressure. This improves survival
under moderate pressure but does not override lmkd when RAM is critically low.

When the flag is **false** (normal upstream builds), behavior is unchanged:
user opt-out via Settings still applies to PostHog; compatibility reporting works
as shipped.
