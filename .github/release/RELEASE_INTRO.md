**GameNative-x64 (GameNativeX64, GameNative x86)** is GameNative ported to x86_64 (bionic) —
run your Steam, Epic, GOG and Amazon PC games through Proton/Wine on Intel or AMD Android
devices (Googlebooks, Bliss OS, Android-x86, Bass:Lineout), with hardware Vulkan.

Upstream GameNative targets arm64 and leans on ARM-only pieces (Box64, the Vortek Vulkan
proxy, `libredirect-bionic-wx.so`). This fork replaces those with a native x86_64 path: no
emulation layer, a real Khronos Vulkan loader with X11 surfaces, and Mesa drivers
cross-compiled for bionic.

### Highlights

- Native **x86_64** execution — Proton/Wine runs directly, without Box64 translation
- **Hardware Vulkan** via Mesa ANV (Intel) and RADV (AMD); lavapipe bundled as a software fallback
- Vulkan loader, ICDs, and X11 client stack shipped in-APK and staged on first launch
- PulseAudio wired up for in-game sound
- Automatic presentation: DRI3 GPU zero-copy, with a per-game Software copy fallback
- Raw mouse input, pointer capture, and touch controls that follow the last input used
- Linux apps from Ubuntu, Debian or Arch in their own Android windows

**Experimental.** Verified with Half-Life 2, Boltgun and Selaco on Intel GPUs.

## Install

1. Download **`app-modernX64-release.apk`** below (not the debug APK).
2. Install on an **x86_64** Android device running Android 10 (API 29) or newer.
   Do not use the Play Store GameNative on x86_64; it is ARM64-only.

```bash
adb install -r app-modernX64-release.apk
```

The Vulkan payload (~50MB compressed) unpacks on first launch, so the first game start
takes longer than later ones.

### Verifying which driver you got

After launching a game, check the DXVK log inside the game's directory:

```bash
adb shell "grep -iE 'Device name|Skipping CPU adapter' \
  /data/data/app.gamenative/Steam/steamapps/common/*/[a-z]*_d3d9.log"
```

A real GPU name (for example `Intel(R) UHD Graphics`) means hardware Vulkan. If it reports
`llvmpipe`, it fell back to software rendering.

### Building it yourself

The native libraries are built in a pinned container (podman or docker required), which
keeps the toolchain identical across machines:

```bash
./scripts/native-build-container.sh scripts/build-x86_64-bionic-libs.sh
./scripts/native-build-container.sh scripts/build-x86_64-mesa-vulkan.sh
./scripts/provision-x86_64-vulkan.sh tarball
./gradlew :app:assembleModernX64Release -PnoMinify=true
```

`-PnoMinify=true` is currently required: a minified release ANRs shortly after launch in a
Compose recomposition loop.
