#!/usr/bin/env bash
# Run a bionic x86_64 cross-build inside the pinned toolchain container.
#
#   scripts/native-build-container.sh scripts/build-x86_64-mesa-vulkan.sh
#   scripts/native-build-container.sh --rebuild-image scripts/build-x86_64-bionic-libs.sh
#   scripts/native-build-container.sh --shell
#
# A container is required rather than optional: these builds silently absorb host
# toolchain differences (NDK layout, meson version, which llvm-config is first on
# PATH), so a host fallback would hand back a payload that differs per machine.
#
# Conventions follow bootable/aaropa/build/lib.sh: podman preferred, docker accepted,
# rootless, and never any sudo.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CONTAINERFILE="$ROOT/scripts/Containerfile.x86_64-native"
IMAGE_BASE="gamenative-x86_64-native"

log()  { printf '\033[1;34m==>\033[0m %s\n' "$*"; }
die()  { printf '\033[1;31mERROR:\033[0m %s\n' "$*" >&2; exit 1; }

detect_runtime() {
    if command -v podman >/dev/null 2>&1; then echo podman; return 0; fi
    # Unlike podman, a docker binary can be present with an unreachable daemon.
    if command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then
        echo docker; return 0
    fi
    return 1
}

# Report everything missing at once with a single apt line, rather than failing on
# each tool in turn. Never runs sudo.
check_deps() {
    local runtime="$1" missing=() pkgs=()
    if [ "$runtime" = podman ]; then
        # Rootless podman needs these to map uids and set up networking; without them
        # the failure surfaces as an opaque OCI error.
        for pair in "newuidmap:uidmap" "newgidmap:uidmap" "slirp4netns:slirp4netns"; do
            command -v "${pair%%:*}" >/dev/null 2>&1 || {
                missing+=("${pair%%:*}"); pkgs+=("${pair##*:}")
            }
        done
    fi
    [ ${#missing[@]} -eq 0 ] && return 0
    printf 'missing: %s\n' "${missing[*]}" >&2
    printf 'install with:\n  sudo apt-get install -y %s\n' \
        "$(printf '%s\n' "${pkgs[@]}" | sort -u | tr '\n' ' ')" >&2
    return 1
}

REBUILD=false
WANT_SHELL=false
ARGS=()
while [ $# -gt 0 ]; do
    case "$1" in
        --rebuild-image) REBUILD=true ;;
        --shell)         WANT_SHELL=true ;;
        --)              shift; ARGS+=("$@"); break ;;
        *)               ARGS+=("$1") ;;
    esac
    shift
done

RUNTIME="$(detect_runtime)" || die "podman or a working docker is required (see --help in the header)"
check_deps "$RUNTIME" || die "container runtime prerequisites missing"

[ -f "$CONTAINERFILE" ] || die "no Containerfile at $CONTAINERFILE"

# Before the image build: that pulls ~700MB on a cold cache, which is a rude way to
# find out the command line was wrong.
if [ "$WANT_SHELL" != true ] && [ ${#ARGS[@]} -eq 0 ]; then
    die "usage: $0 [--rebuild-image] <script> [args...] | --shell"
fi

# Tag by Containerfile content, so editing the toolchain definition rebuilds instead of
# silently reusing an image built from the old pins.
TAG="$IMAGE_BASE:$(sha256sum "$CONTAINERFILE" | cut -c1-12)"

image_present() {
    # podman has `image exists`; docker only has inspect.
    "$RUNTIME" image exists "$TAG" 2>/dev/null && return 0
    "$RUNTIME" image inspect "$TAG" >/dev/null 2>&1
}

if [ "$REBUILD" = true ] || ! image_present; then
    log "building $TAG (first run downloads the NDK, ~700MB)"
    "$RUNTIME" build -f "$CONTAINERFILE" -t "$TAG" "$ROOT/scripts"
else
    log "using $TAG"
fi

RUN_OPTS=(--rm -v "$ROOT:/src:z" -w /src)
if [ "$RUNTIME" = docker ]; then
    # Rootless podman already maps container root onto the invoking user, so build
    # outputs land owned by them. Docker does not, and would leave root-owned files in
    # the work tree, so run as the caller there.
    RUN_OPTS+=(--user "$(id -u):$(id -g)")
fi
[ -t 0 ] && RUN_OPTS+=(-it)

if [ "$WANT_SHELL" = true ]; then
    log "interactive shell in $TAG"
    exec "$RUNTIME" run "${RUN_OPTS[@]}" "$TAG" bash
fi

log "running: ${ARGS[*]}"
exec "$RUNTIME" run "${RUN_OPTS[@]}" "$TAG" bash -c 'exec "$@"' _ "${ARGS[@]}"
