<div align="center">

# GameNativeX64 (Bliss-Bass fork)

> **This is the [Bliss-Bass](https://github.com/Bliss-Bass) fork of GameNative,
> maintained on the `bliss-x64` branch for Bass:Lineout x86_64 Android tablets
> (ax86). It diverges from upstream to support native x86_64 execution, Linux
> app integration, and ROM preinstall. Upstream is
> [utkarshdalal/GameNative](https://github.com/utkarshdalal/GameNative).**

**Play the PC games you already own - from Steam, Epic and GOG - on your Android device, with cloud saves.**

<a href="https://trendshift.io/repositories/14497" target="_blank"><img src="https://trendshift.io/api/badge/repositories/14497" alt="utkarshdalal%2FGameNative | Trendshift" style="width: 250px; height: 55px;" width="250" height="55"/></a>

[![GitHub Release](https://img.shields.io/github/v/release/Bliss-Bass/GameNative-x64?style=flat-square&logo=github&label=x64+release)](https://github.com/Bliss-Bass/GameNative-x64/releases/latest)
[![License](https://img.shields.io/badge/license-GPL%203.0-blue?style=flat-square)](https://github.com/utkarshdalal/GameNative/blob/master/LICENSE)

[**Releases**](https://github.com/Bliss-Bass/GameNative-x64/releases) · [**Upstream**](https://github.com/utkarshdalal/GameNative) · [**Discord**](https://discord.gg/2hKv4VfZfE) · [**Support on Ko-fi**](https://ko-fi.com/gamenative)

</div>

---

## Bliss-Bass x86_64 fork

### Why this fork exists

Upstream GameNative targets ARM64 Android devices and depends on ARM-only
components: Box64/FEX for x86 emulation, the Vortek Vulkan proxy, and
proprietary ARM redirect shims. Bass:Lineout runs on **x86_64 Android**
(Intel / AMD tablets), so those components are both unnecessary and unavailable.

This fork replaces the ARM execution stack with a fully native x86_64 path and
adds integration features specific to ax86 tablet deployments.

### What is different from upstream

| Area | Upstream (ARM64) | This fork (x86_64) |
|------|-----------------|---------------------|
| CPU execution | Box64 / FEX translation | Native x86_64 - no emulation |
| Vulkan | Vortek ARM proxy | Mesa ANV (Intel) + RADV (AMD); lavapipe fallback |
| Linux apps | Not present | Session manager: per-app X display, FGS, drawer stubs |
| Window management | Single activity | `LinuxSessionActivity` - per-app freeform tasks via document URI |
| GTK4 window sizing | N/A | `fit-windows.sh` watcher fixes post-map size-hint timing |
| DPI scaling | ARM device DPIs | `LinuxDisplayScale` converts Android DPI → X baseline correctly |
| ROM integration | Standalone APK | Preinstalled via `vendor/ax86-lite/addons/gamenative`; stub installer |
| Signing | Release keystore | CI-signed - Bliss-Bass GameNative x64 key (`CN=Bliss-Bass`) |
| Build flavor | `arm64` | `modernX64` - x86_64 JNI prebuilts + Mesa Vulkan payload |
| App name | GameNative | GameNativeX64 |

### Changes shipped in this fork

**v1.2.0-x64.x series:**

- **Native x86_64 execution** - `HostCpu` detection, `modernX64` build flavor,
  x86_64 JNI prebuilts (proot, Mesa ANV/RADV, PulseAudio, X11 client libs)
- **Linux app session management (Phases A-C)**
  - `LinuxSessions` registry + `LinuxSessionService` foreground service
    (`foregroundServiceType specialUse`), keyed by entry id
  - `LinuxSession` interface - display, run, stop; Xvnc/RFB path behind it
  - Proper teardown - `GuestProcesses` kills the whole guest process group
  - Liveness-aware stale display lock (prevents display collisions between sessions)
  - `LinuxSessionActivity` with per-app document URI
    (`gamenative://linux/<entryId>`) - one freeform task per Linux app
  - Entry id in stub metadata + trampoline extras; fingerprint change triggers
    automatic reconcile/republish
  - Session mode pref (`CLOSE_WITH_APP` / `KEEP_RUNNING`), per-app stop/restart
    controls, Stop all header action + notification action
- **GTK4 window fill** - `fit-windows.sh` watcher maximizes windows after their
  size hints relax (openbox map-time rule alone misses the GTK4 hint window)
- **DPI scaling fix** - `LinuxDisplayScale` corrects the ~1.7x overshoot on
  ax86 displays by converting Android DPI (160 baseline) to X DPI (96 baseline)
- **Icon decode fix** - launcher entries no longer rejected for icons Android
  cannot decode at scan time
- **Drawer parent fix** - drawer-launched Linux apps receive the app list as
  their task parent
- **Shared-memory present path** - SysV shm broker, MIT-SHM negotiation, and
  fence/region release on client disconnect (groundwork for hardware-accelerated
  present; currently falling back to software WSI pending DRI3 wiring)

### Planned / in progress

- **Hardware-accelerated present** - wire DRI3 / MIT-SHM so DXVK presents
  through the hardware path rather than Mesa's software WSI fallback
- **Phase D - boot sessions** - AT_BOOT session mode; start a Linux session at
  device boot via a receiver or ROM addon init script (deferred pending
  idle-cost measurement)
- **Per-app SmartDock DFC** - stub-hosted-window approach for per-Linux-app
  launch-mode overrides (current SmartDock DFC keys on package name only)
- **Theme sync** - propagate Android light/dark preference to guest xsettingsd,
  `GTK_THEME`, and `ADW_DEBUG_COLOR_SCHEME`
- **R8 minification fix** - resolve the Compose recomposition loop ANR so
  release builds can run without `-PnoMinify=true`
- **Phase E - shared desktop mode** - optional shared X desktop session for
  multi-window Linux use alongside Android apps

### Releases

Releases are tagged `v<upstream>-x64.<n>` on
[Bliss-Bass/GameNative-x64](https://github.com/Bliss-Bass/GameNative-x64/releases).
The `create_release` CI workflow builds and signs `app-modernX64-release.apk`
with the Bliss-Bass GameNative x64 key.

**Latest: v1.2.0-x64.4** (versionCode 46) - Linux session lifetime A-C, GTK4
window fill, DPI scaling, icon + drawer fixes.

### ROM integration (Bass:Lineout)

The prebuilt APK is built by CI on [Bliss-Bass/GameNative-x64](https://github.com/Bliss-Bass/GameNative-x64)
and preinstalled on Bass: Lineout images built with `--gamenative`. A privileged stub installer
handles per-app drawer entries.

See the [GameNativeX64 integration guide](https://github.com/Bliss-Bass/Documentation/blob/main/applications/GameNativeX64/GameNativeX64.md)
in Bliss-Bass Documentation for build flags, release APKs, and image integration.

### Contributing to the fork

Fork-specific changes belong on `bliss-x64`. Core app fixes that are not ax86-specific
should go to [utkarshdalal/GameNative](https://github.com/utkarshdalal/GameNative)
directly.

---

## Original GameNative

GameNative lets you run the PC games in your Steam, Epic and GOG libraries directly on Android - no streaming required. Your saves sync to the cloud, so you can stop on your PC and keep going on your phone.

It's still early. Not every game runs yet, and some need tweaking to play well, but the community is constantly finding and sharing configs that work - and these get applied automatically. You can see if anyone has tried running your game successfully at https://gamenative.app/compatibility.

## What you get

- Play games you actually own on Steam, Epic, GOG and Amazon
- Cloud saves that carry over between your PC and your phone
- Automatically applied known configs, so many games just work out of the box with no tweaking required
- Controller and touch support, with a custom control editor and on-screen HUD
- Steam DLC, workshop and branch support
- Active support over Discord if you need help getting a game running

## Demo

[TechDweeb](https://www.youtube.com/@TechDweeb) walks through setting up GameNative on an Android handheld in a couple of minutes:

<div align="center">

<a href="https://youtu.be/QqIChmAu2_A?si=Ha6xzTQXZA2H8HUN&t=53" target="_blank"><img src="https://github.com/user-attachments/assets/6957e3a1-34ac-41f5-b558-0f1868dbf3d4" alt="Youtube Video" /></a>

</div>

## How to use

1. Download the latest release [here](https://downloads.gamenative.app/releases//gamenative-v.apk)
2. Install the APK on your Android device
3. Log in to your Steam account
4. Install your game
5. Hit play and enjoy

## Support

The fastest way to get help is the [Discord server](https://discord.gg/2hKv4VfZfE) - we're 35k+ strong and someone's usually around.

Please **don't** open issues on GitHub; they're closed automatically. Bring it to Discord instead.

If you'd like to chip in, you can support the project on [Ko-fi](https://ko-fi.com/gamenative).

## Contributing

Want to help out? Message us to get into the **#development** channel on [Discord](https://discord.gg/2hKv4VfZfE), or open a thread there. Things we're currently looking for help with live on our [Trello board](https://trello.com/b/vGRkFoAM/open-source-board).

### Building

Most of the time you don't need this - if you just want to play, grab the release above. This is for contributors.

1. Build it like any normal Android Studio project. Ask on Discord if you get stuck.
2. **SteamGridDB API key (optional):** to pull game artwork for custom games, add your key to `local.properties`:
   ```properties
   STEAMGRIDDB_API_KEY=your_api_key_here
   ```
   You can get one from your [SteamGridDB preferences](https://www.steamgriddb.com/profile/preferences). Without it everything still works - it just won't fetch images.

## Analytics & privacy

GameNative uses [PostHog](https://posthog.com) for anonymous analytics. No personal information is ever collected - no names, emails, IPs or device identifiers.

**Always collected**, to improve game compatibility:
- Game launch, close and exit events (game name, store, session length, average FPS, container config)
- Game install, cancel and uninstall events

This is how we figure out which games work, how well they run, and which configs to apply automatically for the next person. It can't identify you.

**Optional**, and switchable under *Settings → Info → Usage Analytics*:
- Feature usage (on-screen keyboard, controller, HUD, control editor)
- Login success/failure events
- Recommendation interactions
- App lifecycle events (foreground/background)
- Cloud sync events

The full [Privacy Policy](PrivacyPolicy/README.md) has the details.

## Supporters

Thanks to our [Ko-fi sponsors](https://ko-fi.com/gamenative) and [GitHub sponsors](https://github.com/sponsors/utkarshdalal?preview=true), including [CodeRabbit](https://coderabbit.link/gnative).

[![Star History Chart](https://star-history.dera.page/svg?repos=utkarshdalal/GameNative&type=Date&theme=dark)](https://star-history.dera.page/#utkarshdalal/GameNative&Date)

## License

[GPL 3.0](https://github.com/utkarshdalal/GameNative/blob/master/LICENSE).

See [THIRD_PARTY_NOTICES](THIRD_PARTY_NOTICES) for attributions, copyleft source offers, and notices about third-party and proprietary components bundled with the app.

---

**Disclaimer:** This software is meant for playing games that you legally own. Don't use it for piracy or anything else illegal. The maintainer takes no responsibility for misuse.
