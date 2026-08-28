# x86_64 guest Vulkan provisioning

How the Wine/DXVK guest gets a Vulkan driver on ax86 (Bliss x86_64) tablets, what
has to be provisioned on-device, and how to ship it without a 190 MB APK.

## Why anything has to be provisioned at all

DXVK needs a Vulkan driver that can present to the in-app X server, i.e. one that
exposes `VK_KHR_xlib_surface` / `VK_KHR_xcb_surface`. Nothing on the device provides
that:

| Candidate | Why it doesn't work |
|---|---|
| `/system/lib64/libvulkan.so` (Android loader) | Exposes `VK_KHR_android_surface` only; no X11 WSI. |
| `/vendor/lib64/hw/vulkan.*.so` (device Mesa HAL) | Android-platform build: exports only the `HMI` HAL symbol (no `vk_icdGetInstanceProcAddr`), links `libnativewindow`/`libui` with no `libxcb`/`libX11`, so it has no X11 WSI and cannot act as a Khronos ICD. |
| Vortek (GameNative's ARM answer) | `libvortekrenderer.so` is an arm64-only prebuilt with no source. |

So the guest needs its own bionic x86_64 **Khronos loader + ICD**. Termux publishes
both, built against bionic for x86_64, which is exactly the ABI we need.

## What gets staged, and where

Staging root is `files/host_vk_x86_64` (`HostBionicLibs.hostVulkanRoot`).

> Do **not** stage into `files/host_libs_x86_64`: that tree is deleted and
> re-extracted from assets on every launch, so anything placed there is lost.

```
files/host_vk_x86_64/
  usr/lib/libvulkan.so.1                      -> libvulkan.so.1.4.360   (Khronos loader)
  usr/lib/libvulkan_lvp.so                                              (Mesa lavapipe ICD)
  usr/lib/…                                                             (dependency closure, ~80 libs)
  usr/share/vulkan/icd.d/lvp_icd.x86_64.json                            (ICD manifest)
```

The app wires this up automatically once present:

* `HostBionicLibs.hasStagedVulkanLoader` gates everything below.
* `buildLdLibraryPath` puts `host_vk_x86_64/usr/lib` **before** `/system/lib64` so the
  Khronos loader wins over Android's.
* `HostBionicLibs.applyGuestVulkanEnv` sets `VK_ICD_FILENAMES` / `VK_DRIVER_FILES` to
  the staged manifests and pins `MESA_VK_WSI_DEBUG=sw`.
* `ensureVulkanLoaderSymlink` skips its `/system/lib64/libvulkan.so` symlink when a
  real loader is staged.

The ICD manifest's `library_path` must be an **absolute on-device path**. Termux's
own manifest hardcodes `/data/data/com.termux/...` and must be rewritten.

## Reproducing it (dev loop)

All of the above is automated by `scripts/provision-x86_64-vulkan.sh`:

```bash
./scripts/provision-x86_64-vulkan.sh build            # fetch + stage locally
./scripts/provision-x86_64-vulkan.sh push <serial>    # stage + install on device
./scripts/provision-x86_64-vulkan.sh tarball          # stage + emit .tzst
```

What it does, in order:

1. Fetch the Termux `binary-x86_64` `Packages` index.
2. Download the seeds `vulkan-loader-generic` and `mesa-vulkan-icd-swrast`.
3. Unpack (`ar x` + `tar xf data.tar.*`) and flatten every `usr/lib/*.so*` into one dir.
4. Walk ELF `NEEDED` entries and pull more packages until the closure is satisfied,
   treating bionic-provided sonames (`libc`, `libm`, `libdl`, `liblog`, …) as external.
   The closure is ~80 libs / 190 MB, dominated by `libLLVM` (139 MB) and `libicu` (36 MB).
5. Rewrite the ICD manifest `library_path` to the on-device staging path.
6. Push via `/data/local/tmp`, then `cp -a` into the app data dir and
   `chown` to the app's uid + `restorecon`.

The last step needs root; `adb push` cannot write another app's data dir directly.

## Packaging options

### A. Ship as a downloadable component — recommended

Publish the staged tree as a single `.tzst` and fetch it on first x86_64 launch,
reusing the existing graphics-driver component download path
(`ContentsManager` / `GeneralComponents`).

* 190 MB uncompressed compresses to **45 MB** with `zstd -19`, comparable to the
  graphics driver components already downloaded at runtime.
* No APK bloat, no Termux dependency, no root, no user steps.
* Version/verify it like any other component; the app already knows how to extract
  into its own data dir with correct ownership.

### B. Termux startup script — does not work unprivileged

Attractive because the device already ships Termux from `vendor/foss_userapp`, but
it cannot deliver the libraries:

* `/data/data/com.termux/files/usr/lib` is not readable by GameNative (different uid,
  and app data dirs are not world-traversable), so we cannot point
  `LD_LIBRARY_PATH` at Termux's tree.
* Termux cannot write into GameNative's data dir either.
* `apt-get` refuses to run as root ("disabled permanently for safety purposes"), so
  the root workaround isn't even convenient.

A Termux route would have to hand off through shared storage (Termux writes a
tarball to `/sdcard`, GameNative imports it), which needs storage permissions and
manual user action — strictly worse than option A. **Recommend dropping this.**

### C. Bundle in the APK — rejected

139 MB of `libLLVM` in the APK is not viable, and it would be dead weight on ARM.

### D. Build hardware ICDs ourselves — the performance path

Lavapipe is a *software* rasterizer; it is the compatibility floor, not the goal.
See "Hardware acceleration" below.

Suggested sequence: land A with lavapipe to make x86_64 work out of the box, then
add hardware ICDs under D and keep lavapipe as the fallback.

## Hardware acceleration

PC-class x86_64 targets are overwhelmingly Intel or AMD, with NVIDIA and virtio
in the tail, so this needs to cover several GPU vendors rather than one.

### The paths do not need to be selected by hand

One Mesa build produces a separate ICD per driver — `libvulkan_intel.so` (ANV),
`libvulkan_radeon.so` (RADV), `libvulkan_nouveau.so` (NVK), `libvulkan_lvp.so`
(lavapipe) — and each one probes DRM on its own. If several manifests are listed
in `VK_DRIVER_FILES`, the Khronos loader enumerates them all and only the driver
matching the hardware reports a physical device. Hardware detection is therefore
already solved by the loader; there is no need for GameNative to branch on vendor
to choose a driver.

What still needs deciding is narrower:

1. **Which payload to fetch.** Shipping every driver plus LLVM to every device is
   wasteful, so the vendor is read from
   `/sys/class/drm/card*/device/vendor` (`0x8086` Intel, `0x1002` AMD, `0x10de`
   NVIDIA, `0x1af4`/`0x1b36` virtio) to pick a component.
2. **Which device wins when more than one enumerates.** With a hardware ICD and
   lavapipe both staged, DXVK sees two physical devices; it prefers non-CPU
   devices, but the choice should be explicit and overridable rather than left to
   a heuristic.
3. **Fallback.** If the hardware ICD fails to initialise, lavapipe must still be
   reachable, so the two components are independent rather than either/or.

### Why the device's own drivers cannot be reused

`/vendor/lib64/hw/vulkan.intel.so` is Mesa ANV already built for bionic x86_64,
which looks like a free win, but it is an Android-platform build: it exports only
`HMI`, links `libnativewindow`/`libui`, and contains no `libxcb`/`libX11`. The
`VK_KHR_xlib_surface` string is present only because Mesa generates the full
extension-name table regardless of the platforms enabled. So a fresh Mesa
cross-build with `-Dplatforms=x11 -Ddri3=enabled` is unavoidable.

### What makes this feasible

`/dev/dri/renderD128` is `crw-rw-rw-`, so an unprivileged app can open the render
node directly — which is what ANV and RADV need. Neither driver requires LLVM, so
a hardware component is roughly 10–20 MB per driver rather than lavapipe's 45 MB
compressed.

## Current status

With the stack staged, the guest gets a fully working Vulkan device: the DXVK
`hl2_dxgi.log` reports `llvmpipe (LLVM 21.1.8)`, Vulkan 1.4.335, 7788 MiB heap, and
DXVK creates a `1280x800` `VK_FORMAT_B8G8R8A8_UNORM` swapchain with 3 images.

Two app-side fixes were needed alongside the staging:

* `VortekRendererComponent`'s static initializer aborted the process on x86_64 because
  `libvortekrenderer.so` does not exist. The load is now non-fatal, since the class's
  static helpers are plain Java and are used by the X server regardless of renderer.
* The X server's Present extension did not implement `QueryCapabilities` (minor
  opcode 4). Mesa's X11 WSI issues it during swapchain setup and treats the protocol
  error as fatal.

Remaining blocker: Source requests fullscreen (`Windowed: false`), queries `RANDR`,
gets `present=false` from the X server, and exits. Next step is either forcing
windowed mode for the title or implementing a minimal RANDR extension exposing one
fixed mode.
