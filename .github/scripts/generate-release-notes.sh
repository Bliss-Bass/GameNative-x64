#!/usr/bin/env bash
# Writes GitHub Release notes markdown to stdout.
# Usage: generate-release-notes.sh <tag>   e.g. v1.2.0-x64.1
set -euo pipefail

TAG="${1:?Usage: generate-release-notes.sh <tag>}"
REPO="${GITHUB_REPOSITORY:-Bliss-Bass/GameNative-x64}"
INTRO_FILE=".github/release/RELEASE_INTRO.md"
GRADLE_FILE="app/build.gradle.kts"
HOST_LIBS_KT="app/src/main/java/app/gamenative/utils/HostBionicLibs.kt"

RELEASE_APK="app/build/outputs/apk/modernX64/release/app-modernX64-release.apk"
DEBUG_APK="app/build/outputs/apk/modernX64/debug/app-modernX64-debug.apk"

if [ ! -f "$INTRO_FILE" ]; then
  echo "::error::Missing $INTRO_FILE" >&2
  exit 1
fi

MIN_SDK="$(grep -E 'minSdk\s*=' "$GRADLE_FILE" | head -1 | grep -Eo '[0-9]+' || echo "26")"
VERSION_NAME="$(grep -E 'versionName\s*=' "$GRADLE_FILE" | head -1 | sed -E 's/.*"([^"]+)".*/\1/' || echo "${TAG#v}")"
VERSION_CODE="$(grep -E 'versionCode\s*=' "$GRADLE_FILE" | head -1 | grep -Eo '[0-9]+' || echo "?")"

# Only this fork's tags. Upstream's v1.3.x tags came along with the history, and they
# sort above our v1.2.0-x64.N, so an unfiltered lookup produced a "Since v1.3.2" section
# comparing against a commit this fork never released.
PREV_TAG=""
if git rev-parse "$TAG" >/dev/null 2>&1; then
  PREV_TAG="$(git tag -l 'v*-x64.*' --sort=-version:refname | grep -Fxv "$TAG" | head -1 || true)"
fi

COMMIT_COUNT="$(git log --since="30 days ago" --oneline --no-merges 2>/dev/null | wc -l | tr -d ' ')"
SINCE_DATE="$(date -u -d '30 days ago' '+%Y-%m-%d' 2>/dev/null || date -u -v-30d '+%Y-%m-%d' 2>/dev/null || echo '30 days ago')"

CHANGELOG="$(
  git log --since="30 days ago" --pretty=format:"- %s (\`%h\`)" --no-merges 2>/dev/null \
    | sed 's/Co-authored-by: Cursor *$//' \
    | sed '/^$/d' \
    || true
)"
if [ -z "$CHANGELOG" ]; then
  CHANGELOG="- No commits in the last 30 days."
fi

apk_size() { [ -f "$1" ] && du -h "$1" | cut -f1 || true; }
RELEASE_APK_SIZE="$(apk_size "$RELEASE_APK")"
DEBUG_APK_SIZE="$(apk_size "$DEBUG_APK")"

# Which Vulkan drivers the bundled payload actually carries. This is the part of the
# port most likely to change between builds, and it is not visible from the APK name.
VULKAN_ASSET="$(sed -n 's/.*VULKAN_ASSET *= *"\([^"]*\)".*/\1/p' "$HOST_LIBS_KT" 2>/dev/null | head -1 || true)"
ICD_LIST=""
PAYLOAD_SIZE=""
if [ -n "$VULKAN_ASSET" ] && [ -f "app/src/modernX64/assets/$VULKAN_ASSET" ]; then
  PAYLOAD_SIZE="$(du -h "app/src/modernX64/assets/$VULKAN_ASSET" | cut -f1)"
  # paste -d takes a cycling list of delimiter characters, so ', ' would alternate comma
  # and space; join on commas and space them out afterwards.
  ICD_LIST="$(tar -I zstd -tf "app/src/modernX64/assets/$VULKAN_ASSET" 2>/dev/null \
    | sed -n 's#.*icd\.d/\(.*\)\.json#\1#p' | sort | paste -sd, - | sed 's/,/, /g' || true)"
fi

{
  echo "# GameNative x64 ${TAG#v}"
  echo ""
  cat "$INTRO_FILE"
  echo ""
  echo "## What's included"
  echo ""
  echo "| File | Description |"
  echo "|------|-------------|"
  echo "| **app-modernX64-release.apk**${RELEASE_APK_SIZE:+ (~$RELEASE_APK_SIZE)} | Signed release build for **x86_64** tablets. Use for installs and in-place updates. |"
  if [ -n "$DEBUG_APK_SIZE" ]; then
    echo "| **app-modernX64-debug.apk** (~${DEBUG_APK_SIZE}) | Debug build with logging enabled. For testing only. |"
  fi
  echo ""
  echo "> These are **x86_64** builds. They will not install on arm64 devices — use upstream"
  echo "> [GameNative](https://github.com/utkarshdalal/GameNative) for arm64."
  echo ""

  if [ -n "$ICD_LIST" ]; then
    echo "### Bundled graphics stack"
    echo ""
    echo "| Field | Value |"
    echo "|-------|-------|"
    echo "| **Vulkan payload** | \`${VULKAN_ASSET}\`${PAYLOAD_SIZE:+ (~$PAYLOAD_SIZE compressed)} |"
    echo "| **Drivers (ICDs)** | ${ICD_LIST} |"
    echo ""
    echo "The payload is extracted on first launch and carries hardware Vulkan for Intel"
    echo "(ANV) and AMD (RADV) plus a software fallback (lavapipe), so no separate Termux"
    echo "or driver setup is needed."
    echo ""
  fi

  echo "### Automatic updates (Obtainium)"
  echo ""
  echo "To get notified when new \`v*\` tags ship, add this repo in [Obtainium](https://github.com/ImranR98/Obtainium):"
  echo ""
  echo "| Setting | Value |"
  echo "|---------|-------|"
  echo "| **Source** | GitHub |"
  echo "| **Repository** | \`${REPO}\` |"
  echo "| **Release filter** | \`v*\` tags |"
  echo "| **APK filter** | \`app-modernX64-release.apk\` |"
  echo ""
  echo "### Build info"
  echo ""
  echo "| Field | Value |"
  echo "|-------|-------|"
  echo "| **Release tag** | \`${TAG}\` |"
  echo "| **Gradle version** | \`${VERSION_NAME}\` (code ${VERSION_CODE}) |"
  echo "| **Application ID** | \`app.gamenative\` |"
  echo "| **Variant** | \`modernX64\` (x86_64 only) |"
  echo "| **Minimum Android** | API ${MIN_SDK}+ |"
  echo ""

  if [ -n "$PREV_TAG" ]; then
    echo "## Since ${PREV_TAG}"
    echo ""
    echo "Compare on GitHub: [${PREV_TAG}...${TAG}](https://github.com/${REPO}/compare/${PREV_TAG}...${TAG})"
    echo ""
  fi

  echo "## Changes in the last 30 days"
  echo ""
  echo "_Since ${SINCE_DATE} · ${COMMIT_COUNT} commits_"
  echo ""
  echo "$CHANGELOG"
  echo ""
  echo "---"
  echo ""
  echo "_Built from [\`${TAG}\`](https://github.com/${REPO}/releases/tag/${TAG}) · [All commits](https://github.com/${REPO}/commits/${TAG}) · Fork of [utkarshdalal/GameNative](https://github.com/utkarshdalal/GameNative) · License: GPL-3.0_"
}
