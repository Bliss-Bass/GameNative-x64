#!/usr/bin/env bash
# Build upstream (Termux) PRoot for bionic x86_64 and stage it into modernX64 jniLibs.
#
# This is the PRoot the Linux-apps feature uses, and it is deliberately *not* the fork
# vendored under app/src/main/cpp/proot. That fork was stripped down for Wine: it has no
# extension subsystem at all, so it cannot offer -0/--root-id (fake root). Without fake
# root `apt` cannot unpack a package, which is the whole point of shipping a userland.
# Upstream also brings link2symlink and sysvipc, which a Debian rootfs on Android needs.
#
# The Wine path on x86_64 does not use PRoot -- Wine is rebuilt against bionic and exec'd
# through the Android linker -- so nothing else consumes these binaries.
#
# Produces, under app/src/modernX64/jniLibs/x86_64/:
#   libproot-linux.so         the tracer, exec'd as a program despite the .so name
#                             (Android only allows executables from the app's lib dir)
#   libproot-linux-loader.so  the stub PRoot injects to load guest ELFs
#
# Run through the container so the toolchain is pinned:
#   scripts/native-build-container.sh scripts/build-x86_64-proot-linux.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK="$ROOT/native/proot-linux-x86_64"
JNI_OUT="$ROOT/app/src/modernX64/jniLibs/x86_64"

# Pinned so a rebuild does not silently pick up upstream changes.
PROOT_REPO="https://github.com/termux/proot.git"
PROOT_REF="${PROOT_REF:-v5.1.107.76}"
TALLOC_VERSION="${TALLOC_VERSION:-2.4.2}"
TALLOC_SHA256="85ecf9e465e20f98f9950a52e9a411e14320bc555fa257d87697b7e7a9b1d8a6"

API=24
TRIPLE=x86_64-linux-android

log() { printf '\033[1;34m==>\033[0m %s\n' "$*"; }
die() { printf '\033[1;31mERROR:\033[0m %s\n' "$*" >&2; exit 1; }

NDK_ROOT="${NDK_ROOT:-${ANDROID_NDK_HOME:-${ANDROID_NDK:-}}}"
[ -d "$NDK_ROOT" ] || die "NDK not found (set NDK_ROOT); run via scripts/native-build-container.sh"

TC="$NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64"
export CC="$TC/bin/${TRIPLE}${API}-clang"
export AR="$TC/bin/llvm-ar"
export RANLIB="$TC/bin/llvm-ranlib"
export LD="$CC"
[ -x "$CC" ] || die "missing $CC"

mkdir -p "$WORK"

# --- libtalloc -------------------------------------------------------------------
# Samba's waf build cannot run target binaries when cross-compiling, so it asks for the
# answers up front. Only the checks talloc itself performs are listed; waf errors out
# naming any answer it wanted and did not get, which is how this list was derived.
TALLOC_SRC="$WORK/talloc-$TALLOC_VERSION"
TALLOC_PREFIX="$WORK/talloc-install"

if [ ! -f "$TALLOC_PREFIX/lib/libtalloc.a" ]; then
    if [ ! -d "$TALLOC_SRC" ]; then
        log "fetching talloc $TALLOC_VERSION"
        curl -fsSL --retry 3 -o "$WORK/talloc.tar.gz" \
            "https://download.samba.org/pub/talloc/talloc-$TALLOC_VERSION.tar.gz"
        echo "$TALLOC_SHA256  $WORK/talloc.tar.gz" | sha256sum -c - \
            || die "talloc checksum mismatch"
        tar -xzf "$WORK/talloc.tar.gz" -C "$WORK"
    fi

    cat >"$WORK/cross-answers.txt" <<'EOF'
Checking uname sysname type: "Linux"
Checking uname machine type: "x86_64"
Checking uname release type: "5.10"
Checking uname version type: "#1"
Checking simple C program: OK
rpath library support: OK
-Wl,--version-script support: FAIL
Checking getconf LFS_CFLAGS: NO
Checking for large file support without additional flags: OK
Checking for -D_FILE_OFFSET_BITS=64: OK
Checking for -D_LARGE_FILES: OK
Checking correct behavior of strtoll: OK
Checking for working strptime: OK
Checking for C99 vsnprintf: OK
Checking for HAVE_SHARED_MMAP: OK
Checking for HAVE_MREMAP: OK
Checking for HAVE_INCOHERENT_MMAP: FAIL
Checking for HAVE_SECURE_MKSTEMP: OK
Checking for known incompatible readdir bug: FAIL
Checking whether we can use Linux thread-specific credentials: OK
Checking for the maximum value of the 'time_t' type: NO
Checking whether the WRFILE -keytab is supported: OK
Checking for kernel change notify support: OK
Checking for Linux kernel oplocks: OK
Checking for kernel share modes: OK
Checking whether POSIX capabilities are available: OK
Checking if the C compiler understands -mfpmath=387: OK
EOF

    log "configuring talloc (cross, bionic $TRIPLE api $API)"
    (
        cd "$TALLOC_SRC"
        # --disable-python: the bindings need a target Python; nothing here uses them.
        # --disable-rpath: rpath is meaningless in an APK's lib dir.
        ./configure \
            --cross-compile \
            --cross-answers="$WORK/cross-answers.txt" \
            --hostcc=gcc \
            --disable-python \
            --disable-rpath \
            --without-gettext \
            --prefix="$TALLOC_PREFIX" \
            --builtin-libraries=replace \
            --bundled-libraries=NONE
        # waf only emits a versioned shared library, and Android cannot package a
        # libtalloc.so.2 soname. The configure step above is still what makes this work:
        # it produced a config.h describing bionic, so talloc's own sources compile
        # straight into a static archive with no further probing.
        "$CC" -c -O2 -fPIC \
            -DHAVE_CONFIG_H -D__STDC_WANT_LIB_EXT1__=1 -include bin/default/config.h \
            -I. -Ibin/default -Ilib/replace \
            talloc.c -o "$WORK/talloc.o"
        "$CC" -c -O2 -fPIC \
            -DHAVE_CONFIG_H -D__STDC_WANT_LIB_EXT1__=1 -include bin/default/config.h \
            -I. -Ibin/default -Ilib/replace \
            lib/replace/replace.c -o "$WORK/replace.o"

        mkdir -p "$TALLOC_PREFIX/lib" "$TALLOC_PREFIX/include"
        "$AR" rcs "$TALLOC_PREFIX/lib/libtalloc.a" "$WORK/talloc.o" "$WORK/replace.o"
        "$RANLIB" "$TALLOC_PREFIX/lib/libtalloc.a"
        cp talloc.h "$TALLOC_PREFIX/include/"
    )
fi
[ -f "$TALLOC_PREFIX/lib/libtalloc.a" ] || die "talloc build produced no static library"

# --- proot -----------------------------------------------------------------------
PROOT_SRC="$WORK/proot"
if [ ! -d "$PROOT_SRC/.git" ]; then
    log "cloning proot $PROOT_REF"
    git clone --depth 1 --branch "$PROOT_REF" "$PROOT_REPO" "$PROOT_SRC"
fi

# ashmem_memfd.c calls strcmp/memset without including <string.h>; glibc's headers pull
# it in transitively and bionic's do not. Implicit declarations are fatal here, and for
# memset an implicit int return would truncate a pointer, so this is not warning noise.
if ! grep -q '<string.h>' "$PROOT_SRC/src/extension/ashmem_memfd/ashmem_memfd.c"; then
    log "patching missing string.h include"
    sed -i '0,/^#include/s//#include <string.h>\n#include/' \
        "$PROOT_SRC/src/extension/ashmem_memfd/ashmem_memfd.c"
fi

log "building proot"
make -C "$PROOT_SRC/src" distclean >/dev/null 2>&1 || true
# Exported rather than passed as make arguments: the makefile builds both variables up
# with +=, and a command-line assignment would replace its own -I. and -ltalloc instead.
export CPPFLAGS="-I$TALLOC_PREFIX/include"
export LDFLAGS="-L$TALLOC_PREFIX/lib"
make -C "$PROOT_SRC/src" -j"$(nproc)" CC="$CC" LD="$CC" AR="$AR" proot loader

mkdir -p "$JNI_OUT"
install -m 755 "$PROOT_SRC/src/proot" "$JNI_OUT/libproot-linux.so"
install -m 755 "$PROOT_SRC/src/loader/loader" "$JNI_OUT/libproot-linux-loader.so"

log "verifying"
for lib in libproot-linux.so libproot-linux-loader.so; do
    readelf -h "$JNI_OUT/$lib" | grep -qE 'Machine:.*X86-64' || die "$lib is not x86_64"
    printf '  %s (%s)\n' "$lib" "$(du -h "$JNI_OUT/$lib" | cut -f1)"
done
log "done"
