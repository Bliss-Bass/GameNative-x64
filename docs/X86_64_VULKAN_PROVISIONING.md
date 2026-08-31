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
  the staged manifests and pins `MESA_VK_WSI_DEBUG=sw,noshm` (see "How frames are
  presented" for why `noshm` is there, and what it costs).
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

**Half-Life 2 runs.** It reaches its main menu and keeps rendering, verified on the ax86
tablet on 2026-08-30, launched from a generated app-drawer entry. `vkQueueSubmit` and
`vkQueuePresentKHR` cycle continuously and the menu's background scene animates, so
frames are genuinely being produced and presented rather than a single frame sticking.

**ANV serves it, on real hardware.** Confirmed on 2026-08-30 by capturing logcat across a
cold HL2 launch: winevulkan's `fill_luid_property` reports a single physical device,
`Intel(R) UHD Graphics (AML-CFL)`, `vendorID=0x8086`. Lavapipe and RADV are staged and
listed in `VK_DRIVER_FILES` but neither enumerates a device, so the loader's own probing
picks the hardware driver with nothing for us to configure. The earlier
`llvmpipe (LLVM 21.1.8)` reading predates the hardware ICDs and is obsolete.

DXVK's own log is not the way to read this: `DXVKHelper.setEnvVars` sets
`DXVK_LOG_LEVEL=none` after `BlissPortDebug` has asked for `info`, so no `hl2_d3d9.log`
is written. The winevulkan traces under `WINEDEBUG=+vulkan` answer the same question and
are already in logcat under the `WineGuest` tag.

This matters for presentation, because the same capture shows guest ANV offering
`VK_EXT_external_memory_dma_buf`, `VK_EXT_image_drm_format_modifier`,
`VK_KHR_external_memory_fd` and `VK_EXT_queue_family_foreign` — the whole set a real
dma-buf export needs. The Android side of the app presents through ANV too (a
`MESA-INTEL: anv_get_image_format_properties` line accompanies `Winlator_Renderer`
creating its swapchain), so both ends of a zero-copy handoff would be the same driver on
the same device. DRI3 is therefore worth the effort here rather than being blocked on
hardware that cannot export.

Three app-side fixes were needed alongside the staging:

* `VortekRendererComponent`'s static initializer aborted the process on x86_64 because
  `libvortekrenderer.so` does not exist. The load is now non-fatal, since the class's
  static helpers are plain Java and are used by the X server regardless of renderer.
* The X server's Present extension did not implement `QueryCapabilities` (minor
  opcode 4). Mesa's X11 WSI issues it during swapchain setup and treats the protocol
  error as fatal.
* MIT-SHM had to be disabled; see below. Before that, Source died with status 1 shortly
  after its first queue submits, because the guest lost its X connection mid-frame and
  Xlib's IO error handler exits the process.

## How frames are presented

Presentation is a Settings choice (Performance -> Frame presentation), and on x86_64 it picks
between two working paths today:

* **Software copy** (`MESA_VK_WSI_DEBUG=sw,noshm`), the default: Mesa's software WSI copies the
  GPU-rendered image back to the CPU and pushes the whole frame to the X server as an
  `XPutImage` over the unix socket. Two costs — a readback per frame and a full frame through a
  socket.
* **Shared memory** (`sw`): the readback stays, but the frame does not travel through the socket;
  the server presents a pixmap backed by the guest's own segment. Measurably faster at game
  resolutions — see "The MIT-SHM path works" below for numbers and for what it took.

The `sw` half of both is still unavoidable: Mesa would otherwise use DRI3 with buffer sharing,
and this X server has no DRI3/Present buffer sharing without the Vortek renderer, which is an
arm64-only prebuilt. That zero-copy route is the remaining lever on presentation latency.

**Why `noshm` was needed originally.** Mesa enables MIT-SHM whenever the X server
advertises DRI3 and Present, which this one does. The X server implements MIT-SHM 1.1 —
the variant where the client passes a *SysV shmid*, an integer, and the server looks it up
(`MITSHMExtension.attach` -> `SHMSegmentManager.attach`). Bionic has no SysV shared
memory, so the shmid has to come from an emulation layer that both sides agree on: the app
runs a broker over a unix socket (`SysVSharedMemoryComponent`, at
`/tmp/.sysvshm/SM0`, path exported to the guest as `ANDROID_SYSVSHM_SERVER`), and the
guest is supposed to reach it through an `LD_PRELOAD` interposer that turns `shmget` and
`shmat` into broker requests.

On x86_64 that interposer did not exist, which is why MIT-SHM had to be off at first. It is
written now (`app/src/main/cpp/winlator/sysvshm_interposer.c`, built twice: as
`libandroid-sysvshm.so` and as `libandroid-shmem.so`, because Mesa's ICD names the latter in its
`DT_NEEDED` and bionic lets that outrank `LD_PRELOAD`). The rest of this section is the original
diagnosis, kept because the mechanism still explains the layout. The preloaded
`libandroid-sysvshm.so` was built
from `app/src/main/cpp/winlator/sysvshared_memory.c`, which defines only four JNI entry
points and **no `shmget`/`shmat`/`shmdt`/`shmctl`** — confirmed with `nm -D`. So Mesa's
`shmget` binds instead to Termux's `libandroid-shmem`, which hands back a shmid from its
own process-local table. That shmid means nothing to the server, `SHMSegmentManager.attach`
silently does nothing when the lookup fails, and the failure surfaces later as
`BadSHMSegment` — which is what costs the guest its X connection.

Note that arm64 does not have this problem only because it preloads a real interposer
shipped in imagefs. Both copies of that library on the device (`libandroid-sysvshm.so` and
`libandroid-shmem.so` under `imagefs/usr/lib`) are AArch64 binaries, useless here, and no
source for the interposer is in the tree.

**Two routes forward**, cheapest first:

1. ~~Write the x86_64 interposer and re-enable MIT-SHM.~~ **Done**, along with the three
   server-side pieces it turned out to need; see below.
2. **DRI3 + Present with dma-buf**, the zero-copy path and the real answer — the x86_64
   counterpart of what Vortek does on arm64. Note the fd-passing machinery this needs
   already exists on both sides: the transport handles `SCM_RIGHTS` in both directions and
   `DRI3Extension.pixmapFromFd` already maps a received fd. What MIT-SHM is missing is
   the 1.2 protocol requests (`ShmAttachFd`, `ShmCreateSegment`), not the plumbing.

Whichever lands, the route should become a user-visible Settings choice with a per-device
default, since the right answer will vary by GPU and driver.

### The MIT-SHM path works

The shared-memory setting presents frames, repeatably, and it is faster than the socket copy at
sizes that matter: 300 frames of vkcube at 1280x800 take 5.2 s through shared memory against
6.8 s through the socket (about 57 fps against 44). At 500x500 the socket path still wins, which
is the expected shape — the saving is the frame copy, so it grows with the frame.

Getting there needed three server-side pieces, not one. The shared-memory path is not "the same
blit through shared memory": it is *present a pixmap whose storage is the guest's segment*, so it
runs through the Present extension and drags Present's requirements in with it.

1. **MIT-SHM `CreatePixmap` (opcode 5), and `shared_pixmaps = true` in `ShmQueryVersion`.** The
   ICD's imports say which one it uses: `xcb_shm_attach` and `xcb_shm_create_pixmap` are
   imported, `xcb_shm_put_image` is not. The pixmap is a `Drawable` whose `ByteBuffer` is a slice
   of the segment `SHMSegmentManager` already holds. There is no stride on the wire and none is
   needed: every depth here pads scanlines to 32 bits, and Mesa passes its row pitch *as the
   pixmap width* (a 500-pixel-wide image arrives as a 512-wide pixmap), so both ends agree.
2. **XFIXES regions.** Mesa creates a region per swapchain image, before it attaches anything,
   and does it whether or not the server advertises XFIXES. This is the trap that made the
   failure so hard to read: **libxcb will not send a request for an extension the server
   disclaims — it shuts the connection down with `CLOSED_EXT_NOTSUPPORTED` instead**, writing no
   bytes and reporting no error. So the guest went quiet mid-setup with a healthy-looking server
   and an empty wire. Regions are recorded and unused; this server repaints whole windows.
3. **DRI3 `FenceFromFD` (opcode 4).** Mesa allocates an `xshmfence` — one `int32` in shared
   memory — and waits on it before reusing an image. The server must map the passed fd and, when
   the pixmap goes idle, store 1 and `FUTEX_WAKE` (no `FUTEX_PRIVATE_FLAG`: the waiter is another
   process). Present's idle path already called `SyncExtension.setTriggered`, so this only had to
   give those fences memory to poke. Without it each image presents exactly once and the client
   blocks for good.

A fourth bug was not about shared memory at all, but it hid behind this one and would have bitten
any second Present client in a session: **Present's event contexts were never dropped when a
client disconnected.** Resource ids are recycled, so the next client's `SelectInput` found the
dead client's entry under its own id and got `BadMatch` — leaving it registered for nothing, so it
sent one present and waited forever for a completion. The symptom was that the first run in a
container worked and every later run hung, which reads like flakiness rather than a leak.
`XClient.freeResources` now tells Present to forget the client, the way it already told XInput2.

Two smaller fixes on the way: `ShmQueryVersion` replied with 17 bytes where every X reply must be
32 (this output stream pads nothing automatically, so the client read the next 15 bytes on the
socket as the tail of that reply), and a failed `ShmAttach` now returns `BadAccess` instead of
silently succeeding, so a client that cannot attach falls back instead of failing later and
elsewhere.

What was ruled out before the cause was found, kept because these are the cheap checks worth
repeating on the next presentation bug:

- **Not the driver.** With the Intel ICD moved aside the guest runs on llvmpipe and fails
  identically.
- **Not the broker, size, or present mode.** No interposer call fails; a 64x64 window (16 KB
  segments) fails the same as 4 MB ones; `immediate` and `mailbox` hang exactly as `fifo`.
- **Not a malformed reply or event.** Every byte written to the client was dumped and decoded.
- **Not the resource id space.** The setup reply advertises a 4 M-wide id range
  (base `0x02c00000`, mask `0x003fffff`), so `xcb_generate_id` cannot run dry and never needs
  XC-MISC.
- **"Connection is not dead" was wrong**, and worth recording as a lesson: `tools/xcbtrace.c`
  only sees calls the *test binary* makes, because a preload cannot outrank an ICD's own
  `DT_NEEDED` under bionic. Mesa's internal `xcb_connection_has_error` never went through the
  shim, and the connection was in fact dead the whole time.

### Reproducing it in seconds

`tools/guest_vk_run.sh` runs any native x86_64 Vulkan binary inside a live container
environment with the WSI mode as an argument, so both paths can be compared without a game.
`vkcube` from Termux's `vulkan-tools` (x86_64) is a good subject: it is 288 KB, links only
libc, and finds the container's loader through `LD_LIBRARY_PATH`.

Two things make this bearable to iterate on:

- **A container of one's own.** Import a folder containing nothing but `winemine.exe` as a
  custom game (called `vk-testing` here). It boots a session, and therefore the X server and
  the broker, in about half a minute, and its window stays open indefinitely.
- **Keep test binaries out of `imagefs`.** A container start wipes `imagefs/tmp` and resets the
  home directory, so staged tools belong somewhere else; the script expects `files/vktest`.

With that in place a full comparison of both paths takes about fifteen seconds. Use a
game-sized window (`--width 1280 --height 800`) when comparing: at small sizes the copy being
saved is too cheap to measure and the socket path looks better than it is.

One habit worth keeping: run each mode **twice**. The Present event-context leak above only
showed up on the second client in a session, and a single run per mode reported it as a success.

Three tools earned their keep on this and are worth reaching for again:

- `tools/xcbtrace.c`, an `LD_PRELOAD` shim over the handful of xcb entry points a client can
  block in. Preloading works for the test binary because it is an executable; it would not work
  for the Vulkan ICDs, whose own `DT_NEEDED` outranks `LD_PRELOAD` under bionic.
- `debuggerd -b <pid>`, which names the blocked call per thread without any setup.
- `grep -a <symbol> <ICD>`, which answers "what does this driver actually call" in seconds and
  settled this question outright.
