# x86_64 JNI prebuilts (Bliss ax86 port)

## Build open-source libs

```bash
./scripts/build-x86_64-jni.sh
```

Produces: `libwinlator.so`, `libwinlator_11.so`, `libvulkan_renderer.so` (Mesa/system Vulkan, no adrenotools),
`libextras.so`, `libvirglrenderer.so`, `libpatchelf.so`, `libxconnectorpatch.so`, `libandroid-sysvshm.so`,
`libevshim.so` (host stub).

## Pulse + bionic font libs (x86_64)

```bash
./scripts/build-x86_64-pulse.sh          # libpulse*.so → jniLibs + pulse tzst asset
./scripts/build-x86_64-bionic-libs.sh    # freetype/fontconfig + X11 + libvulkan.so.1 → bionic-libs tzst asset
```

`modernX64` assets live under `app/src/modernX64/assets/` (host-specific pulse + bionic libs).

## Still blocked (upstream proprietary / ARM-only)

| Library | Notes |
|---------|--------|
| `libredirect-bionic-wx.so` | Closed-source; modern Bionic LD_PRELOAD |
| `libhook_impl.so`, `libmain_hook.so` | Closed-source; Turnip wrapper ICD path |
| `libevshim.so` | Host stub only; full SDL vjoy needs `third_party/SDL2/` |
| `libsteambootstrap.so` | Source not in public tree |
| `libproot.so` | PRoot `arch.h` is ARM-only; modern Bionic uses `linker64` instead |

Request x86_64 builds from GameNative maintainers for proprietary components.
