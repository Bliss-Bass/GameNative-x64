# Bliss x86_64 Android host port

Branch **`bliss-x64`** tracks GameNative changes for Bass:Lineout x86_64 tablets
(ax86). Upstream default remains ARM64 + Box64/FEX.

## Build (ax86)

```bash
./gradlew :app:assembleModernX64Release
```

JNI prebuilts for `x86_64` are staged under `app/src/modernX64/jniLibs/x86_64/`.
Build open-source libs with `./scripts/build-x86_64-jni.sh` (see README there).

**Blockers for full runtime:** proprietary ARM-only `libredirect-bionic-wx.so`,
`libhook_impl.so`, `libmain_hook.so`, plus `libevshim.so` (SDL2 headers) and
`libwinlator_11.so`. Modern Bionic skips redirect preload on x86_64 until upstream
ships x86_64 redirect binaries.

## ax86 graphics

See [ax86-graphics.md](ax86-graphics.md) for Mesa/Vulkan boot props on Lineout images.

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

When the flag is **false** (normal upstream builds), behavior is unchanged:
user opt-out via Settings still applies to PostHog; compatibility reporting works
as shipped.
