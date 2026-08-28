**GameNative ported to x86_64 (bionic)** for Bass:Lineout / ax86 tablets — run Windows games
through Proton/Wine on Intel or AMD Android devices, with hardware Vulkan.

Upstream GameNative targets arm64 and leans on ARM-only pieces (Box64, the Vortek Vulkan
proxy, `libredirect-bionic-wx.so`). This fork replaces those with a native x86_64 path: no
emulation layer, a real Khronos Vulkan loader with X11 surfaces, and Mesa drivers
cross-compiled for bionic.

### Highlights

- Native **x86_64** execution — Proton/Wine runs directly, without Box64 translation
- **Hardware Vulkan** via Mesa ANV (Intel) and RADV (AMD); lavapipe bundled as a software fallback
- Vulkan loader, ICDs, and X11 client stack shipped in-APK and staged on first launch
- PulseAudio wired up for in-game sound
- Trackpad/mouse pointer capture fixes for desktop-style tablets

**Experimental.** Verified with Half-Life 2 on Intel UHD (Amber Lake / Coffee Lake).
Presentation still uses Mesa's software WSI path, so framerate is below what the GPU can do.

## Install

1. Download **`app-modernX64-release.apk`** below.
2. Install on an **x86_64** Android device (API 26+).

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
