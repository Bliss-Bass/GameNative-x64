#!/usr/bin/env bash
# Provision a guest Vulkan stack (Khronos loader + Mesa lavapipe ICD) for the
# x86_64 Wine guest on ax86 tablets, and optionally push it to a device.
#
# WHY THIS EXISTS
# ---------------
# Wine's winex11.drv can only give DXVK a Vulkan surface if the guest Vulkan
# driver exposes VK_KHR_xlib_surface / VK_KHR_xcb_surface. On Android:
#   * /system/lib64/libvulkan.so (Android loader) exposes android_surface only.
#   * /vendor/lib64/hw/vulkan.*.so (Mesa HAL) has X11 WSI compiled in but
#     exports only the `HMI` HAL symbol, so it cannot act as a Khronos ICD.
#   * GameNative's ARM answer (Vortek) is an arm64-only prebuilt with no source.
# So the guest needs its own bionic x86_64 loader + ICD. Termux publishes both.
#
# OUTPUT LAYOUT (mirrors what the app expects under files/host_vk_x86_64)
#   usr/lib/libvulkan.so.1                 Khronos loader
#   usr/lib/libvulkan_lvp.so               Mesa lavapipe ICD (+ dependency closure)
#   usr/share/vulkan/icd.d/lvp_icd.x86_64.json
#
# USAGE
#   ./scripts/provision-x86_64-vulkan.sh build            # stage into build dir
#   ./scripts/provision-x86_64-vulkan.sh push <serial>    # stage + push to device
#   ./scripts/provision-x86_64-vulkan.sh tarball          # stage + make .tzst
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK="$REPO_ROOT/native/vulkan-x86_64"
STAGE="$WORK/stage"
DEBS="$WORK/debs"
UNPACK="$WORK/unpack"
PKG_INDEX="$WORK/Packages"

REPO_URL="https://packages.termux.dev/apt/termux-main"
INDEX_URL="$REPO_URL/dists/stable/main/binary-x86_64/Packages"

APP_ID="app.gamenative"
# Must match HostBionicLibs.hostVulkanRoot(). Not host_libs_x86_64: that tree is
# deleted and re-extracted from assets on every launch.
DEVICE_DIR="/data/data/$APP_ID/files/host_vk_x86_64"

# Seed packages; the rest of the closure is resolved from ELF NEEDED entries.
SEED_PKGS=(vulkan-loader-generic mesa-vulkan-icd-swrast)

# Provided by bionic / the Android image, never bundled.
SYSTEM_LIBS=" libc.so libm.so libdl.so liblog.so libandroid.so libstdc++.so libvulkan.so "

log() { printf '\033[1;34m==>\033[0m %s\n' "$*"; }

fetch_index() {
    mkdir -p "$WORK"
    [ -s "$PKG_INDEX" ] || curl -fsSL "$INDEX_URL" -o "$PKG_INDEX"
}

# Termux ships one library per package, so map soname -> package by convention
# plus a few known exceptions.
pkg_for_soname() {
    case "$1" in
        libLLVM*)          echo libllvm ;;
        libandroid-shmem*) echo libandroid-shmem ;;
        libandroid-support*) echo libandroid-support ;;
        libX11-xcb*|libX11*) echo libx11 ;;
        libxcb*)           echo libxcb ;;
        libXau*)           echo libxau ;;
        libXdmcp*)         echo libxdmcp ;;
        libXext*)          echo libxext ;;
        libXfixes*)        echo libxfixes ;;
        libXrender*)       echo libxrender ;;
        libxshmfence*)     echo libxshmfence ;;
        libwayland*)       echo libwayland ;;
        libdrm*)           echo libdrm ;;
        libffi*)           echo libffi ;;
        libxml2*)          echo libxml2 ;;
        libicu*)           echo libicu ;;
        libiconv*)         echo libiconv ;;
        libz.so*)          echo zlib ;;
        libzstd*)          echo zstd ;;
        libc++_shared*)    echo libc++ ;;
        *)                 return 1 ;;
    esac
}

fetch_pkg() {
    local p="$1" fn
    [ -f "$DEBS/$p.deb" ] && return 0
    fn=$(grep -A25 "^Package: $p\$" "$PKG_INDEX" | grep -m1 '^Filename:' | awk '{print $2}')
    if [ -z "$fn" ]; then echo "  ! no such package: $p" >&2; return 1; fi
    mkdir -p "$DEBS"
    curl -fsSL "$REPO_URL/$fn" -o "$DEBS/$p.deb"
    echo "  + $p"
}

unpack_into_stage() {
    rm -rf "$UNPACK"; mkdir -p "$UNPACK" "$STAGE/usr/lib" "$STAGE/usr/share/vulkan/icd.d"
    local d n
    for d in "$DEBS"/*.deb; do
        n=$(basename "$d" .deb)
        mkdir -p "$UNPACK/$n"
        ( cd "$UNPACK/$n" && ar x "$d" && tar xf data.tar.* )
    done
    find "$UNPACK" -path '*/files/usr/lib/*' -name '*.so*' \( -type f -o -type l \) \
        -exec cp -Pn {} "$STAGE/usr/lib/" \; 2>/dev/null || true
    find "$UNPACK" -path '*/share/vulkan/icd.d/*.json' \
        -exec cp -n {} "$STAGE/usr/share/vulkan/icd.d/" \; 2>/dev/null || true
}

# Repeatedly add packages until every NEEDED soname is satisfied.
resolve_closure() {
    local round missing n pkg added
    for round in 1 2 3 4 5 6 7 8; do
        unpack_into_stage
        added=0
        missing=$(
            cd "$STAGE/usr/lib"
            for f in *; do
                [ -f "$f" ] || continue
                readelf -d "$f" 2>/dev/null | awk '/NEEDED/{gsub(/[][]/,"",$5); print $5}'
            done | sort -u | while read -r n; do
                [ -e "$n" ] && continue
                case "$SYSTEM_LIBS" in *" $n "*) continue ;; esac
                echo "$n"
            done
        )
        [ -z "$missing" ] && { log "closure complete (round $round)"; return 0; }
        while read -r n; do
            [ -z "$n" ] && continue
            if pkg=$(pkg_for_soname "$n"); then
                fetch_pkg "$pkg" && added=1
            else
                echo "  ! unmapped soname: $n" >&2
            fi
        done <<< "$missing"
        [ "$added" -eq 0 ] && { echo "cannot resolve: $missing" >&2; return 1; }
    done
    echo "closure did not converge" >&2; return 1
}

# Termux hardcodes /data/data/com.termux paths; repoint at our install dir.
rewrite_icd_paths() {
    local j lib
    for j in "$STAGE"/usr/share/vulkan/icd.d/*.json; do
        lib=$(basename "$(grep -o '"library_path"[^,}]*' "$j" | sed 's/.*"\(.*\)"/\1/')")
        printf '{\n    "ICD": {\n        "api_version": "1.3.0",\n        "library_arch": "64",\n        "library_path": "%s/usr/lib/%s"\n    },\n    "file_format_version": "1.0.1"\n}\n' \
            "$DEVICE_DIR" "$lib" > "$j"
        log "icd $(basename "$j") -> $DEVICE_DIR/usr/lib/$lib"
    done
}

# Fold in the hardware ICDs from build-x86_64-mesa-vulkan.sh so one payload carries
# ANV, RADV and lavapipe. Force-copies: its libdrm/xshmfence/libandroid-shmem are
# cross-built against bionic with the shims Mesa needs, and must win over Termux's.
# Runs after the closure so Termux packages cannot no-clobber their way in first.
merge_mesa_icds() {
    local mesa_stage="$REPO_ROOT/native/mesa-vulkan-android/stage"
    if [ ! -d "$mesa_stage/usr/lib" ]; then
        log "no mesa stage at $mesa_stage — software (lavapipe) only"
        log "run scripts/build-x86_64-mesa-vulkan.sh for the ANV/RADV hardware path"
        return 0
    fi
    cp -Pf "$mesa_stage"/usr/lib/*.so* "$STAGE/usr/lib/" 2>/dev/null || true
    cp -f "$mesa_stage"/usr/share/vulkan/icd.d/*.json "$STAGE/usr/share/vulkan/icd.d/" 2>/dev/null || true
    log "merged mesa hardware ICDs: $(cd "$STAGE/usr/share/vulkan/icd.d" && echo *.json)"
}

# Drop anything not reachable from the loader or an ICD.
#
# Termux ships several libraries per package, so the forward closure over-collects:
# pulling libicuuc (for libxml2, for libLLVM) also lands ICU's i18n/io/tu/test libs,
# which nothing here NEEDs. Walking backwards from the real roots instead of denying
# names keeps this correct as upstream packaging shifts.
#
# Symlinks are kept when their target survives, since the loader resolves ICDs and
# sonames through them (libvulkan.so.1 -> libvulkan.so.1.4.x).
prune_to_closure() {
    local lib="$STAGE/usr/lib"
    local keep queue cur n target before after

    # Roots: the loader by soname, plus every ICD the manifests point at.
    queue=$(
        cd "$lib"
        ls libvulkan.so.1* 2>/dev/null
        for j in "$STAGE"/usr/share/vulkan/icd.d/*.json; do
            [ -f "$j" ] || continue
            basename "$(grep -o '"library_path"[^,}]*' "$j" | sed 's/.*"\(.*\)"/\1/')"
        done
    )
    keep=" "
    while [ -n "$queue" ]; do
        cur=$(echo "$queue" | head -1)
        queue=$(echo "$queue" | tail -n +2)
        [ -z "$cur" ] && continue
        case "$keep" in *" $cur "*) continue ;; esac
        [ -e "$lib/$cur" ] || continue
        keep="$keep$cur "
        # Follow the symlink so the versioned target is kept too.
        if [ -L "$lib/$cur" ]; then
            target=$(readlink "$lib/$cur")
            queue="$queue"$'\n'"$(basename "$target")"
        fi
        for n in $(readelf -d "$lib/$cur" 2>/dev/null |
                   awk '/NEEDED/{gsub(/[][]/,"",$5); print $5}'); do
            case "$SYSTEM_LIBS" in *" $n "*) continue ;; esac
            queue="$queue"$'\n'"$n"
        done
    done

    before=$(du -sm "$STAGE" | cut -f1)
    local f
    for f in "$lib"/*; do
        [ -e "$f" ] || continue
        case "$keep" in *" $(basename "$f") "*) continue ;; esac
        rm -f "$f"
    done
    after=$(du -sm "$STAGE" | cut -f1)
    log "pruned to loader/ICD closure: ${before}M -> ${after}M"
}

cmd_build() {
    fetch_index
    log "fetching seed packages"
    local p; for p in "${SEED_PKGS[@]}"; do fetch_pkg "$p"; done
    log "resolving dependency closure"
    resolve_closure
    merge_mesa_icds
    rewrite_icd_paths
    # After the merge, so the hardware ICDs count as roots.
    prune_to_closure
    log "staged $(find "$STAGE/usr/lib" -maxdepth 1 -type f | wc -l) libs, $(du -sh "$STAGE" | cut -f1)"
}

cmd_push() {
    local serial="${1:?usage: push <adb-serial>}"
    cmd_build
    log "pushing to $serial:$DEVICE_DIR (requires adb root)"
    adb -s "$serial" root >/dev/null 2>&1 || true
    sleep 2
    adb -s "$serial" shell "rm -rf /data/local/tmp/host_vk_x86_64"
    adb -s "$serial" push "$STAGE" /data/local/tmp/host_vk_x86_64 >/dev/null
    local uid
    uid=$(adb -s "$serial" shell "stat -c %U /data/data/$APP_ID" | tr -d '\r')
    adb -s "$serial" shell "rm -rf $DEVICE_DIR && mkdir -p $(dirname $DEVICE_DIR) \
        && cp -a /data/local/tmp/host_vk_x86_64 $DEVICE_DIR \
        && chown -R $uid:$uid $DEVICE_DIR \
        && chmod -R 755 $DEVICE_DIR \
        && restorecon -R $DEVICE_DIR 2>/dev/null; \
        rm -rf /data/local/tmp/host_vk_x86_64"
    log "installed; verifying"
    adb -s "$serial" shell "ls $DEVICE_DIR/usr/lib/libvulkan.so.1 $DEVICE_DIR/usr/share/vulkan/icd.d/"
}

cmd_tarball() {
    cmd_build
    # The app looks the payload up by exact filename (HostBionicLibs.VULKAN_ASSET), so
    # callers that stage it into assets/ pass the name they expect rather than having to
    # keep a date in sync by hand.
    local out="$WORK/${VULKAN_ASSET_NAME:-vulkan-x86_64-$(date +%Y%m%d).tzst}"
    log "creating $out"
    tar -C "$STAGE" -cf - usr | zstd -19 -T0 -o "$out" -f
    ls -la "$out"
}

case "${1:-build}" in
    build)   cmd_build ;;
    push)    shift; cmd_push "$@" ;;
    tarball) cmd_tarball ;;
    *) echo "usage: $0 {build|push <serial>|tarball}" >&2; exit 2 ;;
esac
