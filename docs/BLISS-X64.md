# Bliss x86_64 Android host port

Branch **`bliss-x64`** tracks GameNative changes for Bass:Lineout x86_64 tablets
(ax86). Upstream default remains ARM64 + Box64/FEX.

## Build (ax86)

```bash
./gradlew :app:assembleModernX64Release
```

JNI prebuilts for `x86_64` are staged under `app/src/modernX64/jniLibs/x86_64/`
(see README there). Phase 1 builds proot/winlator/vulkan_renderer for the host ABI.

## ax86 graphics

See [ax86-graphics.md](ax86-graphics.md) for Mesa/Vulkan boot props on Lineout images.

## Upstream

Contribute `HostCpu`, launcher branches, and CI in small PRs to
[utkarshdalal/GameNative](https://github.com/utkarshdalal/GameNative).

Product integration (preinstall APK): Bliss-Bass `gamenative` addon in
`vendor/ax86-lite/addons/gamenative/`.
