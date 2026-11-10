# Merging upstream GameNative into bliss-x64

`bliss-x64` is GameNative x64, the x86_64 port. `origin` is upstream
(`utkarshdalal/GameNative`), `bliss` is our fork (`Bliss-Bass/GameNative-x64`).

The rule that keeps merges cheap: **gate, do not remove**. Upstream features that do not apply on
x86_64 stay in the tree and are switched off by a host or flavor check. Our own behaviour lives in
our own files and is reached through small hooks in upstream's files.

## Before a merge

```bash
git fetch origin
git merge-base HEAD origin/master                 # last merge point
git rev-list --count $(git merge-base HEAD origin/master)..origin/master
# Preview without touching the tree:
git merge-tree --write-tree --name-only --no-messages HEAD origin/master
```

Merge in slices on a `merge/upstream-<version>` branch (each slice a merge commit at an upstream
release or large refactor), build and smoke-test each slice, then fast-forward `bliss-x64`.

After resolving:

- Rebuild `libvulkan_renderer.so` (`cmake --build app/.cxx-bliss-x64/x86_64 --target vulkan_renderer`)
  if `app/src/main/cpp/winlator/` changed; never pick either side's prebuilt binary.
- Check `.github/workflows`: ours stay (see below), upstream's release workflows stay deleted.
- Run the unit tests, then launch Boltgun, Selaco and Half-Life 2 on an Intel device with a mouse
  (in-game mouse look), check Automatic and Software copy presentation, run `scripts/linux-test`
  A to G, a Linux Services job, and a stub app launch.

## Where we diverge, and how it is gated

| Area | Ours | Gate / hook |
|---|---|---|
| Build flavor | `modernX64` in `app/build.gradle.kts`: `HOST_CPU`, `BLISS_PORT_DEBUG`, `PROJECT_REPO`, x86_64 ABI | Flavor fields only |
| Channels (help, issues, updates, source) | `utils/ProjectChannels.kt`, flavor strings in `app/src/modernX64/res/` | `ProjectChannels.isOwnProject` (`PROJECT_REPO` set); call sites use `ProjectChannels.supportUrl` / `sourceUrl` |
| Support relay, Discord, AI support, paywall | Not used on x64 | Debug report Send opens a GitHub issue; membership pitches suppressed by `BLISS_PORT_DEBUG` |
| Telemetry (PostHog, session and compatibility reports) | Off on x64 | `utils/Telemetry.kt`; `PluviaApp` skips PostHog setup, so upstream's direct `PostHog.capture` calls are no-ops |
| Strings | `res/values/strings_x64.xml`; renames as flavor overrides (`app_name`, LSFG wording, every locale) | upstream's `strings.xml` files stay identical to upstream |
| Host graphics | `HostBionicLibs`, `HostDisplayEnv`, `HostGraphicsEnv`, `PresentationFallback`, `enums/PresentationPath` | `HostCpu.current().isX86_64` |
| X server | `DRI3Extension` import listener (read by `PresentationFallback`); `PresentExtension` does not give windows an AHB on x86 | `HostCpu` check in `PresentExtension.selectInput` |
| Input | `PointerCaptureCompat`, `PointerCaptureDispatchLayout`, `TouchpadView` capture and raw deltas, input switching in `XServerScreen` | x86-only parts check `HostCpu`; the rest is generic |
| Linux apps, distros, services | `app/gamenative/linux/**`, Linux Services AIDL, stubs | Our own packages |
| Native payloads | `scripts/build-x86_64-*.sh`, `scripts/provision-x86_64-vulkan.sh`, `scripts/patches/mesa/`, `scripts/patches/proot-linux/`, `.github/actions/x64-payload` | Built in the pinned container; CI caches key on the scripts and patches |
| Assets | `app/src/modernX64/assets/bionic-libs-x86_64-*.tzst` (committed), `vulkan-x86_64-*.tzst` (built by CI) | Names in `HostBionicLibs`; a new name restages on devices |
| CI | `create_release.yml`, `compile_*_apk.yml` and `verify_build.yml` (our versions), `issue-triage.yml`; issue form in `.github/ISSUE_TEMPLATE/` | Keep ours on conflict. Upstream's `adhoc-signed-build`, `app-release-signed`, `issues-contributors-only`, `pluvia-pr-check`, `tagged-release` are deleted on purpose |
| Release tooling | `vendor/ax86-lite/addons/gamenative/tools/update_addon.sh` | Bumps, tags, waits for CI, pins and stages the addon |

## Known hot spots

These files carry both large upstream changes and our additions, so expect conflicts there:
`ui/screen/xserver/XServerScreen.kt`, `ui/PluviaMain.kt`, `utils/ContainerUtils.kt`,
`xenvironment/components/BionicProgramLauncherComponent.java`, `cpp/winlator/VulkanRendererContext.cpp`,
`MainActivity.kt`, `PluviaApp.kt`. When resolving, move our blocks into our own files behind a hook
rather than re-weaving them into upstream's code.

Upstream features that are ARM-only and should stay gated off on x86_64 rather than removed:
gamescope (display server choice, payloads, shims), Turnip and Adreno driver defaults, FEX and
Box64 options, ColorOS and Samsung-specific hints.

Upstream content services (best configs, compatibility stats, recommendations, component
manifests) are consumed as-is. Best configs come from ARM devices, so review how they apply on
x86_64 when upstream changes them.
