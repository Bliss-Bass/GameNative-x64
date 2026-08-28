#!/usr/bin/env bash
# Cross-compile Mesa hardware Vulkan ICDs (ANV for Intel, RADV for AMD) for
# bionic x86_64 with X11 WSI, for the Wine guest on ax86 tablets.
#
# The device's own /vendor/lib64/hw/vulkan.*.so cannot be reused: it is an
# Android-platform build that exports only HMI and has no libxcb/libX11, so it
# offers no X11 surfaces. Termux only publishes a software (lavapipe) ICD, hence
# this build.
#
# Neither ANV nor RADV needs LLVM, which is what keeps the payload to tens of MB
# rather than lavapipe's ~139MB of libLLVM. Nouveau is deliberately excluded: NVK
# requires a Rust/bindgen toolchain.
#
# Depends on the X11 client stack from build-x86_64-bionic-libs.sh; run that first.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BUILD_DIR="$ROOT/native/mesa-vulkan-android"
SRC_DIR="$BUILD_DIR/.src"
# Shared with build-x86_64-bionic-libs.sh so Mesa can find libxcb/libX11 headers.
PREFIX="${PREFIX:-$ROOT/native/bionic-libs-android/root-x86_64}"
OUT_LIB="$PREFIX/usr/lib"
STAGE="$BUILD_DIR/stage"
# Static, so the shimmed AOSP symbols do not become another runtime dependency.
COMPAT_LIB="$BUILD_DIR/libmesa_bionic_compat.a"

MESA_VERSION="${MESA_VERSION:-26.0.6}"
LIBDRM_VERSION="2.4.125"
XSHMFENCE_VERSION="1.3.2"
PCIACCESS_VERSION="0.18.1"
# Must match the installed SPIRV-LLVM-Translator (LLVMSPIRVLib) major version.
HOST_LLVM_VERSION="${HOST_LLVM_VERSION:-19}"

# Device-side location that ICD manifests must point at; must match
# HostBionicLibs.hostVulkanLibDir().
DEVICE_LIB_DIR="${DEVICE_LIB_DIR:-/data/user/0/app.gamenative/files/host_vk_x86_64/usr/lib}"

NDK_ROOT="${NDK_ROOT:-${ANDROID_NDK_HOME:-${ANDROID_NDK:-}}}"
if [[ -z "$NDK_ROOT" ]]; then
    for candidate in \
        "${ANDROID_HOME:-$HOME/Android/Sdk}/ndk/28.2.13676358" \
        "${ANDROID_HOME:-$HOME/Android/Sdk}/ndk/27.0.12077973"; do
        [[ -d "$candidate" ]] && NDK_ROOT="$candidate" && break
    done
fi
[[ -d "$NDK_ROOT" ]] || { echo "NDK not found; set NDK_ROOT" >&2; exit 1; }

API=26
HOST=x86_64-linux-android
TOOLCHAIN="$NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/bin"
SYSROOT="$NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/sysroot"

export CC="$TOOLCHAIN/${HOST}${API}-clang"
export CXX="$TOOLCHAIN/${HOST}${API}-clang++"
export AR="$TOOLCHAIN/llvm-ar"
export RANLIB="$TOOLCHAIN/llvm-ranlib"
export STRIP="$TOOLCHAIN/llvm-strip"
# For the autotools packages below; meson gets these through the cross file.
export CFLAGS="-O2 -fPIC -D__USE_GNU -I$PREFIX/usr/include"
export CPPFLAGS="-I$PREFIX/usr/include"
export LDFLAGS="-L$OUT_LIB"
export PKG_CONFIG_PATH="$PREFIX/usr/lib/pkgconfig:$PREFIX/usr/share/pkgconfig"
export PKG_CONFIG_LIBDIR="$PKG_CONFIG_PATH"
export PKG_CONFIG_SYSROOT_DIR=""

mkdir -p "$SRC_DIR" "$OUT_LIB" "$STAGE"

log() { printf '\n=== %s ===\n' "$*"; }

fetch() {
    local dest="$1" url="$2"
    [[ -f "$dest" ]] && return 0
    echo "fetch $url"
    curl -fsSL --retry 3 -o "$dest" "$url"
}

# --- meson cross file -------------------------------------------------------
# Mesa is told the host is Android, but the WSI platform is x11: that is exactly
# the combination Termux ships, so it is a supported configuration upstream.
# $1: value for host_machine.system, defaulting to 'android'. Some packages gate
# platform backends on this and have no 'android' branch even though the code is
# plain sysfs/POSIX, so those are configured as 'linux'.
write_cross_file() {
    local system="${1:-android}"
    local cross="$BUILD_DIR/$system-x86_64.cross"
    cat > "$cross" <<EOF
[binaries]
c = '$CC'
cpp = '$CXX'
ar = '$AR'
strip = '$STRIP'
ranlib = '$RANLIB'
pkg-config = 'pkg-config'
llvm-config = 'false'
cmake = 'false'

[built-in options]
c_args = ['-O2', '-fPIC', '-D__USE_GNU', '-I$PREFIX/usr/include']
cpp_args = ['-O2', '-fPIC', '-D__USE_GNU', '-I$PREFIX/usr/include']
c_link_args = ['-L$OUT_LIB', '-landroid-shmem', '-llog', '$COMPAT_LIB']
cpp_link_args = ['-L$OUT_LIB', '-landroid-shmem', '-llog', '$COMPAT_LIB']

[properties]
sys_root = '$SYSROOT'
pkg_config_libdir = '$PKG_CONFIG_PATH'
needs_exe_wrapper = true

[host_machine]
system = '$system'
cpu_family = 'x86_64'
cpu = 'x86_64'
endian = 'little'
EOF
    echo "$cross"
}

# --- libandroid-shmem -------------------------------------------------------
# Bionic has no SysV shared memory, but Mesa's X11 WSI references shmget/shmat
# at link time even when MIT-SHM is disabled at runtime via MESA_VK_WSI_DEBUG.
build_android_shmem() {
    [[ -f "$OUT_LIB/libandroid-shmem.so" ]] && { echo "libandroid-shmem present"; return 0; }
    log "libandroid-shmem"
    cd "$SRC_DIR"
    rm -rf android-shmem
    git clone --depth 1 https://github.com/termux/libandroid-shmem.git android-shmem
    cd android-shmem
    # Installs sys/shm.h too, which Mesa's X11 WSI includes unconditionally.
    # The NDK's paths.h has no _PATH_TMP. The value only names where SysV key
    # symlinks would go, and MESA_VK_WSI_DEBUG keeps MIT-SHM off at runtime, so
    # this library is linked purely to resolve shmget/shmat.
    make CC="$CC" AR="$AR" CFLAGS='-fpic -std=c11 -D_PATH_TMP="\"/data/local/tmp/\""' \
        libandroid-shmem.so
    make PREFIX="$PREFIX/usr" install
}

# --- AOSP compat shims ------------------------------------------------------
# Mesa keys tracing/logging off the *target* being Android and includes AOSP
# platform headers that the NDK does not ship. Only these are reachable with
# platforms=x11; the hardware/* and system/window.h includes belong to the
# Android WSI platform, which is off. sync_merge is likewise an AOSP libsync
# symbol, but it is a thin SYNC_IOC_MERGE wrapper over an available uapi struct.
#
# These land in $PREFIX/usr/include, which is already on the include path.
build_aosp_shims() {
    log "AOSP compat shims"
    mkdir -p "$PREFIX/usr/include/cutils" "$PREFIX/usr/include/log"

    cat > "$PREFIX/usr/include/cutils/trace.h" <<'EOF'
#ifndef _MESA_COMPAT_CUTILS_TRACE_H
#define _MESA_COMPAT_CUTILS_TRACE_H
/* atrace lives in libcutils, which is not part of the NDK. */
#define ATRACE_TAG_GRAPHICS (1 << 1)
static inline void atrace_init(void) {}
static inline unsigned long atrace_get_enabled_tags(void) { return 0; }
static inline void atrace_begin_body(const char *name) { (void)name; }
static inline void atrace_end_body(void) {}
static inline void atrace_begin(unsigned long tag, const char *name) {
   (void)tag; (void)name;
}
static inline void atrace_end(unsigned long tag) { (void)tag; }
#endif
EOF

    # vk_android_native_buffer.h is included unconditionally on Android targets,
    # and at ANDROID_API_LEVEL >= 28 it expects buffer_handle_t from the platform.
    cat > "$PREFIX/usr/include/cutils/native_handle.h" <<'EOF'
#ifndef _MESA_COMPAT_CUTILS_NATIVE_HANDLE_H
#define _MESA_COMPAT_CUTILS_NATIVE_HANDLE_H
typedef struct native_handle {
   int version;
   int numFds;
   int numInts;
   int data[0];
} native_handle_t;
typedef const native_handle_t *buffer_handle_t;
#endif
EOF

    cat > "$PREFIX/usr/include/log/log.h" <<'EOF'
#ifndef _MESA_COMPAT_LOG_LOG_H
#define _MESA_COMPAT_LOG_LOG_H
#include <android/log.h>
#ifndef LOG_TAG
#define LOG_TAG NULL
#endif
#define LOG_PRI(priority, tag, ...) \
   ((void)__android_log_print((priority), (tag), __VA_ARGS__))
#define ALOGE(...) LOG_PRI(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define ALOGW(...) LOG_PRI(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define ALOGI(...) LOG_PRI(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define ALOGD(...) LOG_PRI(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#endif
EOF

    cat > "$PREFIX/usr/include/cutils/properties.h" <<'EOF'
#ifndef _MESA_COMPAT_CUTILS_PROPERTIES_H
#define _MESA_COMPAT_CUTILS_PROPERTIES_H
#include <string.h>
#include <sys/system_properties.h>
#define PROPERTY_VALUE_MAX PROP_VALUE_MAX
#define PROPERTY_KEY_MAX PROP_NAME_MAX
/* libcutils' property_get falls back to default_value when unset; both it and
 * __system_property_get return the length written. */
static inline int property_get(const char *key, char *value,
                               const char *default_value) {
   int len = __system_property_get(key, value);
   if (len <= 0 && default_value)
      len = (int)strlcpy(value, default_value, PROPERTY_VALUE_MAX);
   return len;
}
#endif
EOF

    local src="$BUILD_DIR/mesa_bionic_compat.c"
    cat > "$src" <<'EOF'
#include <linux/sync_file.h>
#include <string.h>
#include <sys/ioctl.h>

int sync_merge(const char *name, int fd1, int fd2);

int sync_merge(const char *name, int fd1, int fd2)
{
   struct sync_merge_data data;
   memset(&data, 0, sizeof(data));
   data.fd2 = fd2;
   strncpy(data.name, name, sizeof(data.name) - 1);

   if (ioctl(fd1, SYNC_IOC_MERGE, &data) < 0)
      return -1;

   return data.fence;
}
EOF
    "$CC" -O2 -fPIC -c "$src" -o "$BUILD_DIR/mesa_bionic_compat.o"
    "$AR" rcs "$COMPAT_LIB" "$BUILD_DIR/mesa_bionic_compat.o"
    echo "built $COMPAT_LIB"
}

# --- libpciaccess -----------------------------------------------------------
# libdrm's Intel component needs it; the linux_sysfs backend reads the same
# /sys/class/drm PCI nodes the app can already see.
build_pciaccess() {
    [[ -f "$OUT_LIB/libpciaccess.so" ]] && { echo "libpciaccess present"; return 0; }
    log "libpciaccess $PCIACCESS_VERSION"
    cd "$SRC_DIR"
    fetch "libpciaccess-$PCIACCESS_VERSION.tar.xz" \
        "https://xorg.freedesktop.org/archive/individual/lib/libpciaccess-$PCIACCESS_VERSION.tar.xz"
    rm -rf "libpciaccess-$PCIACCESS_VERSION"
    tar xf "libpciaccess-$PCIACCESS_VERSION.tar.xz"
    cd "libpciaccess-$PCIACCESS_VERSION"
    meson setup build --cross-file "$(write_cross_file linux)" \
        --prefix "$PREFIX/usr" --libdir lib --buildtype release \
        -Ddefault_library=shared -Dzlib=disabled
    ninja -C build install
}

# --- libdrm -----------------------------------------------------------------
build_libdrm() {
    [[ -f "$OUT_LIB/libdrm.so" ]] && { echo "libdrm present"; return 0; }
    log "libdrm $LIBDRM_VERSION"
    cd "$SRC_DIR"
    fetch "libdrm-$LIBDRM_VERSION.tar.xz" \
        "https://dri.freedesktop.org/libdrm/libdrm-$LIBDRM_VERSION.tar.xz"
    rm -rf "libdrm-$LIBDRM_VERSION"
    tar xf "libdrm-$LIBDRM_VERSION.tar.xz"
    cd "libdrm-$LIBDRM_VERSION"
    meson setup build --cross-file "$(write_cross_file)" \
        --prefix "$PREFIX/usr" --libdir lib --buildtype release \
        -Ddefault_library=shared \
        -Dintel=enabled -Damdgpu=enabled \
        -Dradeon=disabled -Dnouveau=disabled -Dvmwgfx=disabled \
        -Dcairo-tests=disabled -Dman-pages=disabled -Dvalgrind=disabled \
        -Dtests=false -Dudev=false
    ninja -C build install
}

# --- libxshmfence -----------------------------------------------------------
# Required by the DRI3 path in Mesa's X11 WSI.
build_xshmfence() {
    [[ -f "$OUT_LIB/libxshmfence.so" ]] && { echo "libxshmfence present"; return 0; }
    log "libxshmfence $XSHMFENCE_VERSION"
    cd "$SRC_DIR"
    fetch "libxshmfence-$XSHMFENCE_VERSION.tar.xz" \
        "https://xorg.freedesktop.org/archive/individual/lib/libxshmfence-$XSHMFENCE_VERSION.tar.xz"
    rm -rf "libxshmfence-$XSHMFENCE_VERSION"
    tar xf "libxshmfence-$XSHMFENCE_VERSION.tar.xz"
    cd "libxshmfence-$XSHMFENCE_VERSION"
    # Bionic dropped glibc's legacy <values.h>; the futex backend wants only MAXINT.
    if [[ ! -f "$PREFIX/usr/include/values.h" ]]; then
        mkdir -p "$PREFIX/usr/include"
        printf '#pragma once\n#include <limits.h>\n#define MAXINT INT_MAX\n' \
            > "$PREFIX/usr/include/values.h"
    fi
    # Bionic has memfd_create but no shm_open; futex fences avoid both.
    ./configure --host="$HOST" --prefix="$PREFIX/usr" --disable-static \
        --with-shared-memory-dir=/data/local/tmp
    make -j"$(nproc)" && make install
}

# --- Mesa -------------------------------------------------------------------
TOOLS_PREFIX="$BUILD_DIR/native-tools"

fetch_mesa() {
    cd "$SRC_DIR"
    fetch "mesa-$MESA_VERSION.tar.xz" \
        "https://archive.mesa3d.org/mesa-$MESA_VERSION.tar.xz"
    if [[ ! -d "mesa-$MESA_VERSION" ]]; then
        tar xf "mesa-$MESA_VERSION.tar.xz"
        # Meson wrap subprojects would try to download during a cross build.
        rm -rf "mesa-$MESA_VERSION/subprojects"
    fi

    # The VK_KHR_display WSI backend is gated only on KMS/DRM being present, but
    # it calls pthread_cancel/pthread_setcanceltype, which bionic does not
    # implement. The guest presents through X11 and never uses that platform, so
    # exclude it on Android rather than faking thread cancellation.
    local cond="if system_has_kms_drm and not with_platform_android"
    local f
    for f in src/vulkan/meson.build src/vulkan/wsi/meson.build; do
        local p="$SRC_DIR/mesa-$MESA_VERSION/$f"
        grep -q "host_machine.system() != 'android'" "$p" && continue
        sed -i "s|^$cond\$|$cond and host_machine.system() != 'android'|" "$p"
        echo "patched $f (drop VK_KHR_display)"
    done
}

# ANV is in Mesa's with_driver_using_cl list, so building it requires the OpenCL-C
# kernel compilers. Those are build-machine tools and cannot be cross-compiled, so
# they come from a native build of the *same* source tree (they must version-match)
# and the cross build then consumes them with `=system`.
build_native_tools() {
    if [[ -x "$TOOLS_PREFIX/bin/intel_clc" && -x "$TOOLS_PREFIX/bin/mesa_clc" ]]; then
        echo "native CLC tools present"
        return 0
    fi
    log "native CLC tools (mesa_clc, vtn_bindgen2, intel_clc)"
    fetch_mesa
    cd "$SRC_DIR/mesa-$MESA_VERSION"
    rm -rf build-native

    # mesa_clc needs LLVM and SPIRV-LLVM-Translator from the *same* LLVM release,
    # and the newest llvm-config on PATH usually outranks the translator's version.
    local native_file="$BUILD_DIR/native-llvm.ini"
    cat > "$native_file" <<EOF
[binaries]
llvm-config = '/usr/bin/llvm-config-$HOST_LLVM_VERSION'
EOF

    env -u CC -u CXX -u AR -u RANLIB -u STRIP -u CFLAGS -u CPPFLAGS -u LDFLAGS \
        -u PKG_CONFIG_PATH -u PKG_CONFIG_LIBDIR -u PKG_CONFIG_SYSROOT_DIR \
        meson setup build-native --native-file "$native_file" \
            --prefix "$TOOLS_PREFIX" --buildtype release \
            -Dvulkan-drivers=intel -Dgallium-drivers= \
            -Dplatforms= -Dglx=disabled -Degl=disabled -Dgbm=disabled \
            -Dopengl=false -Dgles1=disabled -Dgles2=disabled -Dglvnd=disabled \
            -Dllvm=enabled -Dshared-llvm=enabled \
            -Dmesa-clc=enabled -Dprecomp-compiler=enabled \
            -Dinstall-mesa-clc=true -Dinstall-precomp-compiler=true \
            -Dvideo-codecs= -Dvulkan-layers= -Dtools= -Dbuild-tests=false
    env -u CC -u CXX -u AR -u RANLIB -u STRIP ninja -C build-native install
}

build_mesa() {
    log "Mesa $MESA_VERSION (vulkan: intel, amd)"
    fetch_mesa
    cd "$SRC_DIR/mesa-$MESA_VERSION"
    export PATH="$TOOLS_PREFIX/bin:$PATH"
    rm -rf build

    meson setup build --cross-file "$(write_cross_file)" \
        --prefix "$PREFIX/usr" --libdir lib --buildtype release \
        -Dplatforms=x11 \
        -Dvulkan-drivers=intel,amd \
        -Dgallium-drivers= \
        -Dopengl=false -Degl=disabled -Dgles1=disabled -Dgles2=disabled \
        -Dglx=disabled -Dgbm=disabled -Dglvnd=disabled \
        -Dllvm=disabled -Dshared-llvm=disabled \
        -Dxmlconfig=disabled -Dvideo-codecs= \
        -Dmesa-clc=system -Dprecomp-compiler=system \
        -Dandroid-libbacktrace=disabled \
        -Dvulkan-layers= -Dtools= -Dbuild-tests=false
    ninja -C build
    DESTDIR="$BUILD_DIR/install" ninja -C build install
}

# --- staging ----------------------------------------------------------------
# ICD manifests are generated with build-host paths; they must name the driver
# relative to the manifest so the device path stays correct wherever it lands.
stage_icds() {
    log "staging ICDs"
    local installed="$BUILD_DIR/install$PREFIX/usr"
    rm -rf "$STAGE"
    mkdir -p "$STAGE/usr/lib" "$STAGE/usr/share/vulkan/icd.d"

    local so
    for so in "$installed"/lib/libvulkan_*.so; do
        [[ -f "$so" ]] || continue
        cp -a "$so" "$STAGE/usr/lib/"
        "$STRIP" --strip-unneeded "$STAGE/usr/lib/$(basename "$so")" || true
    done

    local json
    for json in "$installed"/share/vulkan/icd.d/*.json; do
        [[ -f "$json" ]] || continue
        python3 - "$json" "$STAGE/usr/share/vulkan/icd.d/$(basename "$json")" \
            "$DEVICE_LIB_DIR" <<'PY'
import json, os, sys
src, dst, libdir = sys.argv[1:4]
with open(src) as f:
    m = json.load(f)
m["ICD"]["library_path"] = os.path.join(libdir, os.path.basename(m["ICD"]["library_path"]))
with open(dst, "w") as f:
    json.dump(m, f, indent=4)
PY
    done

    # Runtime deps Mesa pulled in that are not already staged with the loader.
    local dep
    for dep in libdrm.so libdrm_intel.so libdrm_amdgpu.so libxshmfence.so libandroid-shmem.so; do
        for f in "$OUT_LIB/$dep"*; do
            [[ -e "$f" ]] && cp -a "$f" "$STAGE/usr/lib/" || true
        done
    done

    echo "staged:"
    ls -la "$STAGE/usr/lib" "$STAGE/usr/share/vulkan/icd.d"
    du -sh "$STAGE"
}

main() {
    # First: the cross file references $COMPAT_LIB in its link args, so it has to
    # exist before anything is configured.
    build_aosp_shims
    build_android_shmem
    build_pciaccess
    build_libdrm
    build_xshmfence
    build_native_tools
    build_mesa
    stage_icds
    log "done: $STAGE"
}

main "$@"
