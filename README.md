<div align="center">

# GameNative-x64 (GameNativeX64)

**Play the PC games you already own - from Steam, Epic, GOG and Amazon - natively on x86_64 Android devices and Intel Googlebooks, with cloud saves.**

[![GitHub Release](https://img.shields.io/github/v/release/Bliss-Bass/GameNative-x64?style=flat-square&logo=github&label=x64+release)](https://github.com/Bliss-Bass/GameNative-x64/releases/latest)
[![License](https://img.shields.io/badge/license-GPL%203.0-blue?style=flat-square)](LICENSE)

[**Download**](https://github.com/Bliss-Bass/GameNative-x64/releases/latest) · [**Report an issue**](https://github.com/Bliss-Bass/GameNative-x64/issues/new/choose) · [**Fork docs**](docs/BLISS-X64.md) · [**Upstream GameNative**](https://github.com/utkarshdalal/GameNative)

</div>

---

*Also known as: GameNativeX64, GameNative x64, GameNative x86, GameNative x86_64, GNx64.*

**GameNative-x64** is the native x86_64 fork of
[GameNative](https://github.com/utkarshdalal/GameNative), maintained by
[Bliss-Bass](https://github.com/Bliss-Bass) and [Navotpala Tech](https://navotpala.tech)
on the `bliss-x64` branch. It runs Windows PC games on **x86_64 Android** - Intel
Googlebooks, Bliss OS and Android-x86 PCs, and Bass:Lineout tablets - through
**Proton and Wine with no CPU emulation**, using **Mesa ANV (Intel) and RADV (AMD)
Vulkan** drivers with DXVK and VKD3D-Proton. It also runs Linux desktop apps
(Ubuntu, Debian, Arch) in their own Android windows.

## Why this fork exists

The Google Play release of GameNative is built only for ARM64 phones and handhelds. It
relies on ARM-only components: Box64/FEX to translate x86 code, the Vortek Vulkan proxy,
and ARM redirect shims. On x86_64 Android hardware those components fail to launch games
or leave them on an endless loading screen.

GameNative-x64 replaces that stack with a native x86_64 one: Proton and Wine run directly
on the CPU, and games talk to the device's own Mesa Vulkan driver.

## Supported devices

- **Intel Googlebooks (Googlebook OS)** - tested on a Lenovo Googlebook running stock
  Googlebook OS. ARM-based Googlebooks should use upstream GameNative instead.
- **Bliss OS, Android-x86 and other x86_64 Android builds** on Intel or AMD CPUs with a
  Vulkan-capable GPU, Android 10 or newer (tested on Android 16 and 17).
- **Bass:Lineout (ax86)** tablets and PCs, where GameNativeX64 comes preinstalled.

## Install

1. Download **`app-modernX64-release.apk`** from the
   [latest release](https://github.com/Bliss-Bass/GameNative-x64/releases/latest).
   Do not install GameNative from the Google Play Store on an x86_64 device; that build is
   ARM64-only.
2. Open the APK and allow installs from your browser or file manager when Android asks.
3. Optional: to get updates automatically, add `https://github.com/Bliss-Bass/GameNative-x64`
   in [Obtainium](https://github.com/ImranR98/Obtainium) and set its APK filter to
   `modernX64-release`.
4. Open **GameNativeX64**, sign in to Steam, Epic, GOG or Amazon, install a game and press
   Play. The first launch unpacks the Vulkan drivers, so it takes longer than later ones.

## FAQ

**Is there a GameNative for x86, Intel or AMD devices?**
Yes, this one. GameNative-x64 (GameNativeX64, sometimes searched as GameNative x64 or
GameNative x86) is the x86_64 build of GameNative for Intel and AMD Android devices,
including Googlebooks, Bliss OS and Android-x86. The app installs as **GameNativeX64**.

**Why does the Play Store version of GameNative not start games on my Intel Googlebook?**
The Play Store build targets ARM64 and depends on Box64/FEX and Vortek, which do not work on
Intel or AMD x86_64 CPUs. Install `app-modernX64-release.apk` from
[our releases](https://github.com/Bliss-Bass/GameNative-x64/releases/latest) instead.

**Which games work?**
Support is still experimental. Games we have checked on x86_64 include Half-Life 2, Boltgun
and Selaco. The upstream [compatibility list](https://gamenative.app/compatibility) is a
useful hint, but its reports come mostly from ARM devices.

**A game shows a black screen or runs slowly. What can I change?**
*Settings → Performance → Frame presentation* defaults to **Automatic**: games present
directly through the GPU (DRI3), and a game whose frames cannot be shown that way switches
to **Software copy** on its next launch. Per-container options under Frame presentation trade
speed against black or torn frames on Intel GPUs.

**The mouse or touchpad does nothing in a game.**
Update to the latest release first; raw mouse input and pointer capture were fixed in
`v1.2.0-x64.31`. If a game still ignores the mouse, open the in-game quick menu, go to
*Shooter Mode Settings*, and turn on *Relative mouse input*.

**Where do I report a bug?**
Open an issue on [Bliss-Bass/GameNative-x64](https://github.com/Bliss-Bass/GameNative-x64/issues/new/choose)
with the x86 bug report form. The app's *Help & Support* and update checks point here too.

**Does GameNative-x64 collect analytics?**
No. x86_64 builds do not start PostHog, do not send game-run compatibility reports, and do
not show exit-feedback prompts. See [Privacy](#privacy).

## What is different from upstream

| Area | Upstream GameNative | GameNative-x64 |
|------|---------------------|----------------|
| CPU | ARM64; Box64 / FEX translate x86 code | Native x86_64 - no emulation |
| Vulkan | Vortek ARM proxy | Mesa ANV (Intel) + RADV (AMD); lavapipe fallback |
| Presentation | Android surface | Automatic: DRI3 GPU zero-copy, per-game Software copy fallback |
| Linux apps | Not present | Ubuntu, Debian, Arch userlands; apps in their own windows and the app drawer |
| Analytics | PostHog (optional parts can be turned off) | Off |
| Support | Discord | [GitHub issues](https://github.com/Bliss-Bass/GameNative-x64/issues) |
| App name | GameNative | GameNativeX64 |

## Features in this fork

- **Native x86_64 Proton and Wine**, with x86_64 builds of Mesa, PulseAudio and the X11
  client libraries bundled in the APK
- **Automatic frame presentation** - DRI3 zero-copy by default, Software copy fallback per
  game, and Lean, Balanced and Quality modes for Intel GPUs
- **Mouse, touchpad and touch** - raw mouse input in games, pointer capture at launch, and
  on-screen controls that appear when you touch the screen and hide when you use the mouse
- **LSFG-VK frame generation** on Intel and AMD GPUs (requires Lossless Scaling on Steam)
- **ProtonDB, Steam Deck and SteamOS ratings** next to each game
- **Linux apps** - install Ubuntu, Debian or Arch (Alpine and Fedora experimental), open a
  full desktop or a terminal, run Firefox, Chromium, Chrome and AppImages, and launch Linux
  apps in their own Android windows or from the app drawer
- **App UI size** setting for dense tablet displays

The technical details, port internals and roadmap are in [docs/BLISS-X64.md](docs/BLISS-X64.md).

## Releases

Releases are tagged `v<upstream version>-x64.<n>` on
[Bliss-Bass/GameNative-x64](https://github.com/Bliss-Bass/GameNative-x64/releases). CI builds
and signs `app-modernX64-release.apk` with the Bliss-Bass GameNative x64 key (`CN=Bliss-Bass`),
so it installs as an update over earlier GameNative-x64 releases but not over upstream
GameNative.

## Bass:Lineout ROM integration

Bass:Lineout images built with `--gamenative` preinstall the release APK from the
`vendor/ax86-lite/addons/gamenative` addon, and a privileged stub installer publishes Linux
apps and games into the app drawer. See the
[GameNativeX64 integration guide](https://github.com/Bliss-Bass/Documentation/blob/main/applications/GameNativeX64/GameNativeX64.md)
for build flags and image integration.

## Contributing

Fork-specific changes belong on the `bliss-x64` branch of this repository. Fixes that are not
specific to x86_64 or Bass:Lineout should go to
[utkarshdalal/GameNative](https://github.com/utkarshdalal/GameNative).
[docs/UPSTREAM_MERGE.md](docs/UPSTREAM_MERGE.md) explains how the fork stays mergeable with
upstream.

### Building from source

The native libraries are built in a pinned container (podman or docker):

```bash
./scripts/native-build-container.sh scripts/build-x86_64-bionic-libs.sh
./scripts/native-build-container.sh scripts/build-x86_64-mesa-vulkan.sh
./scripts/provision-x86_64-vulkan.sh tarball
./gradlew :app:assembleModernX64Release -PnoMinify=true
```

`-PnoMinify=true` is currently required: a minified release hangs shortly after launch.

**SteamGridDB API key (optional):** to fetch artwork for custom games, add
`STEAMGRIDDB_API_KEY=your_api_key_here` to `local.properties`. You can get a key from your
[SteamGridDB preferences](https://www.steamgriddb.com/profile/preferences). Everything else
works without it.

---

## About upstream GameNative

GameNative lets you run the PC games in your Steam, Epic and GOG libraries directly on
Android - no streaming required. Your saves sync to the cloud, so you can stop on your PC and
keep going on your device. Upstream features carried by this fork include:

- Play games you actually own on Steam, Epic, GOG and Amazon
- Cloud saves that carry over between your PC and your device
- Automatically applied known configs, so many games work without tweaking
- Controller and touch support, with a custom control editor and on-screen HUD
- Steam DLC, workshop and branch support

[TechDweeb](https://www.youtube.com/@TechDweeb) walks through setting up upstream GameNative
on an ARM Android handheld:

<div align="center">

<a href="https://youtu.be/QqIChmAu2_A?si=Ha6xzTQXZA2H8HUN&t=53" target="_blank"><img src="https://github.com/user-attachments/assets/6957e3a1-34ac-41f5-b558-0f1868dbf3d4" alt="GameNative setup video on YouTube" /></a>

</div>

For general GameNative questions, the upstream community is on
[Discord](https://discord.gg/2hKv4VfZfE). Please report x86_64 problems
[here](https://github.com/Bliss-Bass/GameNative-x64/issues/new/choose) rather than upstream.

## Privacy

GameNative-x64 builds (`modernX64`) turn off upstream's remote telemetry entirely: PostHog is
never started, game-run compatibility reports are not sent, and exit-feedback prompts are not
shown. Upstream GameNative builds use [PostHog](https://posthog.com) for anonymous analytics,
as described in its [Privacy Policy](PrivacyPolicy/README.md).

## Credits and license

GameNative is created by [Utkarsh Dalal](https://github.com/utkarshdalal) and its
contributors. If you would like to support upstream development, see its
[Ko-fi](https://ko-fi.com/gamenative) and [GitHub sponsors](https://github.com/sponsors/utkarshdalal).

Licensed under [GPL 3.0](LICENSE). See [THIRD_PARTY_NOTICES](THIRD_PARTY_NOTICES) for
attributions, copyleft source offers, and notices about third-party and proprietary components
bundled with the app.

**Disclaimer:** This software is meant for playing games that you legally own. Do not use it
for piracy or anything else illegal. The maintainers take no responsibility for misuse.
