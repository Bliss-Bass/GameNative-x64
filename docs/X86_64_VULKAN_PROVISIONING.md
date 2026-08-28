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
| `/vendor/lib64/hw/vulkan.*.so` (device Mesa HAL) | Has X11 WSI compiled in, but exports only the `HMI` HAL symbol, so it cannot be used as a Khronos ICD. |
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

### D. Build a leaner ICD ourselves — best long-term

Lavapipe is a *software* rasterizer; it is the compatibility floor, not the goal.

* **Mesa ANV** (Intel Vulkan) needs no LLVM, so the payload drops to roughly
  10–20 MB, and it is hardware-accelerated on these Intel tablets. Requires an
  NDK/meson cross-build of Mesa against bionic x86_64.
* Keeping lavapipe as a fallback for non-Intel x86_64 hardware is still worthwhile.

Suggested sequence: land A with lavapipe to make x86_64 work out of the box, then
add ANV as the preferred ICD under D and let lavapipe be the fallback.

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
