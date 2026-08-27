# x86_64 JNI prebuilts (Bliss ax86 port)

## Build open-source libs

```bash
./scripts/build-x86_64-jni.sh
```

Produces: `libwinlator.so`, `libvulkan_renderer.so` (Mesa/system Vulkan, no adrenotools),
`libextras.so`, `libvirglrenderer.so`, `libpatchelf.so`, `libxconnectorpatch.so`.

## Still blocked (upstream proprietary / ARM-only)

| Library | Notes |
|---------|--------|
| `libredirect-bionic-wx.so` | Closed-source; modern Bionic LD_PRELOAD |
| `libhook_impl.so`, `libmain_hook.so` | Closed-source; Turnip wrapper ICD path |
| `libwinlator_11.so` | Prebuilt only (no source in public tree) |
| `libevshim.so` | Source in tree; needs `third_party/SDL2/` headers |
| `libsteambootstrap.so` | Source not in public tree |
| Pulse stack | `libpulse*.so`, `libsndfile.so`, … |
| `libproot.so` | PRoot `arch.h` is ARM-only; modern Bionic uses `linker64` instead |

Request x86_64 builds from GameNative maintainers for proprietary components.
