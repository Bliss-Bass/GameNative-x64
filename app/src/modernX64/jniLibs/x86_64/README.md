# x86_64 JNI prebuilts (Bliss ax86 port)

## Build open-source libs

```bash
./scripts/build-x86_64-jni.sh
```

Produces: `libwinlator.so`, `libwinlator_11.so`, `libvulkan_renderer.so` (Mesa/system Vulkan, no adrenotools),
`libextras.so`, `libvirglrenderer.so`, `libpatchelf.so`, `libxconnectorpatch.so`, `libandroid-sysvshm.so`,
`libevshim.so` (host stub), `libexec-redirect.so`.

`libexec-redirect.so` is preloaded into Wine. Stock Android denies apps a direct exec of files in
their data directory, so it starts those binaries through `/system/bin/linker64` instead. This is
the part of the ARM-only `libredirect-bionic-wx.so` that x86_64 needs.

It also keeps 32-bit Windows programs working on kernels without ia32 syscalls (booted with
`ia32_emulation=false`, as on stock Android 17 devices). When `int $0x80` fails and the CPU has
FSGSBASE, it patches the x86_64 `ntdll.so` as it loads to set the 32-bit TEB through the fs base
instead of a GDT descriptor, and removes the `%fs` reload in `wow64cpu.dll`. Set
`EXEC_REDIRECT_WOW64_FS=off` to skip this, or `force` to apply it even when `int $0x80` works.

## Pulse + bionic font libs (x86_64)

```bash
./scripts/build-x86_64-pulse.sh          # libpulse*.so → jniLibs + pulse tzst asset
./scripts/build-x86_64-bionic-libs.sh    # freetype/fontconfig + X11 + libvulkan.so.1 → bionic-libs tzst asset
```

`modernX64` assets live under `app/src/modernX64/assets/` (host-specific pulse + bionic libs).

## PRoot for the Linux userland (x86_64)

```bash
scripts/native-build-container.sh scripts/build-x86_64-proot-linux.sh
```

Produces `libproot-linux.so` and `libproot-linux-loader.so` from upstream (Termux) PRoot.
Upstream rather than the fork vendored in `app/src/main/cpp/proot`: that fork was stripped
down for Wine and has no extension subsystem, so it cannot fake root and `apt` cannot
unpack a package under it.

## Still blocked (upstream proprietary / ARM-only)

| Library | Notes |
|---------|--------|
| `libredirect-bionic-wx.so` | Closed-source; modern Bionic LD_PRELOAD (exec redirect covered by `libexec-redirect.so`) |
| `libhook_impl.so`, `libmain_hook.so` | Closed-source; Turnip wrapper ICD path |
| `libevshim.so` | Host stub only; full SDL vjoy needs `third_party/SDL2/` |
| `libsteambootstrap.so` | Source not in public tree |

Request x86_64 builds from GameNative maintainers for proprietary components.
