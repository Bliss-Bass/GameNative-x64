#!/usr/bin/env bash
# Cross-compile x86_64 Android bionic libs Wine needs when imagefs/usr/lib is ARM-only.
# Produces a tzst with usr/lib/*.so + usr/etc/fonts (fontconfig configs copied from imagefs layout).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BUILD_DIR="$ROOT/native/bionic-libs-android"
SRC_DIR="$BUILD_DIR/.src"
PREFIX="$BUILD_DIR/root-x86_64"
OUT_LIB="$PREFIX/usr/lib"
OUT_ETC="$PREFIX/usr/etc"
ASSET_OUT="$ROOT/app/src/modernX64/assets/bionic-libs-x86_64-20260828.tzst"

NDK_ROOT="${NDK_ROOT:-${ANDROID_NDK_HOME:-${ANDROID_NDK:-}}}"
if [[ -z "$NDK_ROOT" ]]; then
    for candidate in \
        "${ANDROID_HOME:-}/ndk/27.0.12077973" \
        "${ANDROID_HOME:-}/ndk/27.3.13750724"; do
        [[ -d "$candidate" ]] && NDK_ROOT="$candidate" && break
    done
fi
[[ -d "$NDK_ROOT" ]] || { echo "NDK not found" >&2; exit 1; }

API=26
HOST=x86_64-linux-android
TOOLCHAIN="$NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/bin"
export CC="$TOOLCHAIN/${HOST}${API}-clang"
export CXX="$TOOLCHAIN/${HOST}${API}-clang++"
export AR="$TOOLCHAIN/llvm-ar"
export RANLIB="$TOOLCHAIN/llvm-ranlib"
export STRIP="$TOOLCHAIN/llvm-strip"
export CFLAGS="-O2 -fPIC -I$PREFIX/usr/include"
export CPPFLAGS="-I$PREFIX/usr/include"
export LDFLAGS="-L$OUT_LIB -pthread"
export PKG_CONFIG_PATH="$PREFIX/usr/lib/pkgconfig:$PREFIX/usr/share/pkgconfig"
export PATH="$PREFIX/usr/bin:$PATH"

mkdir -p "$SRC_DIR" "$OUT_LIB" "$OUT_ETC/fonts/conf.d"

fetch() {
    local dest="$1"; shift
    local url
    for url in "$@"; do
        echo "fetch $url"
        # --retry-all-errors because freedesktop.org answers bursts from shared CI address
        # ranges with HTTP 418 from its bot protection. Plain --retry only covers errors
        # curl considers transient, so a 418 aborted the build on the first try even
        # though the same URL succeeds moments later.
        curl -fsSL --retry 5 --retry-delay 3 --retry-all-errors -o "$dest" "$url" && return 0
    done
    return 1
}

build_autotools() {
  local archive="$1" url="$2"
  shift 2
  cd "$SRC_DIR"
  [[ -f "$archive" ]] || fetch "$archive" "$url"
  local dir="${archive%%.tar.*}"
  rm -rf "$dir" && tar xf "$archive" && cd "$dir"
  local host_build=1
  for arg in "$@"; do
    [[ "$arg" == --host=* ]] && host_build=0
  done
  if [[ $host_build -eq 1 ]]; then
    # Proto/header packages must configure with the host toolchain.
    CC=gcc CXX=g++ AR=ar RANLIB=ranlib ./configure "$@"
  else
    ./configure "$@"
  fi
  if [[ "$archive" == libX11-* ]]; then
    # Bionic pthread is in libc; -lpthread does not exist on Android NDK.
    find . -name Makefile -exec sed -i 's/-lpthread/-pthread/g' {} +
  fi
  make -j"$(nproc)" && make install
}

build_x11_stack() {
  # Keyed on the library built last, so an existing tree is topped up rather than
  # skipped when this list grows.
  if [[ -f "$OUT_LIB/libXrandr.so" ]]; then
    echo "X11 stack already built"
    return 0
  fi

  echo "=== Building X11 client libs for winex11.drv ==="

  # Headers only (build tools run on host).
  build_autotools xorgproto-2024.1.tar.xz \
    https://www.x.org/archive/individual/proto/xorgproto-2024.1.tar.xz \
    --prefix="$PREFIX/usr"

  build_autotools libXau-1.0.11.tar.gz \
    https://xorg.freedesktop.org/archive/individual/lib/libXau-1.0.11.tar.gz \
    --host="$HOST" --prefix="$PREFIX/usr" --disable-static

  build_autotools libXdmcp-1.1.4.tar.gz \
    https://xorg.freedesktop.org/archive/individual/lib/libXdmcp-1.1.4.tar.gz \
    --host="$HOST" --prefix="$PREFIX/usr" --disable-static

  build_autotools xcb-proto-1.17.0.tar.xz \
    https://xorg.freedesktop.org/archive/individual/proto/xcb-proto-1.17.0.tar.xz \
    --prefix="$PREFIX/usr"

  build_autotools xtrans-1.6.0.tar.xz \
    https://xorg.freedesktop.org/archive/individual/lib/xtrans-1.6.0.tar.xz \
    --prefix="$PREFIX/usr"

  build_autotools libxcb-1.16.1.tar.xz \
    https://xorg.freedesktop.org/archive/individual/lib/libxcb-1.16.1.tar.xz \
    --host="$HOST" --prefix="$PREFIX/usr" --disable-static --without-doxygen

  build_autotools libX11-1.8.10.tar.xz \
    https://xorg.freedesktop.org/archive/individual/lib/libX11-1.8.10.tar.xz \
    --host="$HOST" --prefix="$PREFIX/usr" --disable-static \
    --disable-xf86bigfont --disable-specs \
    --enable-xthreads --enable-malloc0returnsnull=no

  build_autotools libXext-1.3.6.tar.xz \
    https://xorg.freedesktop.org/archive/individual/lib/libXext-1.3.6.tar.xz \
    --host="$HOST" --prefix="$PREFIX/usr" --disable-static \
    --enable-malloc0returnsnull=no

  build_autotools libXfixes-6.0.1.tar.xz \
    https://xorg.freedesktop.org/archive/individual/lib/libXfixes-6.0.1.tar.xz \
    --host="$HOST" --prefix="$PREFIX/usr" --disable-static \
    --enable-malloc0returnsnull=no

  build_autotools libXrender-0.9.11.tar.xz \
    https://xorg.freedesktop.org/archive/individual/lib/libXrender-0.9.11.tar.xz \
    --host="$HOST" --prefix="$PREFIX/usr" --disable-static \
    --enable-malloc0returnsnull=no

  # Mesa's X11 WSI requires xrandr for the xlib-lease path, so the hardware ICD
  # build in build-x86_64-mesa-vulkan.sh does not configure without it.
  build_autotools libXrandr-1.5.4.tar.xz \
    https://xorg.freedesktop.org/archive/individual/lib/libXrandr-1.5.4.tar.xz \
    --host="$HOST" --prefix="$PREFIX/usr" --disable-static \
    --enable-malloc0returnsnull=no
}

install_vulkan_loader_symlink() {
  # Wine dlopen("libvulkan.so.1"); Android ships libvulkan.so without the .1 soname.
  rm -f "$OUT_LIB/libvulkan.so.1"
  ln -sf /system/lib64/libvulkan.so "$OUT_LIB/libvulkan.so.1"
}

# --- zlib ---
if [[ ! -f "$OUT_LIB/libz.so" ]]; then
    cd "$SRC_DIR"
    [[ -f zlib-1.3.1.tar.gz ]] || fetch zlib-1.3.1.tar.gz \
        https://github.com/madler/zlib/releases/download/v1.3.1/zlib-1.3.1.tar.gz \
        https://zlib.net/fossils/zlib-1.3.1.tar.gz
    rm -rf zlib-1.3.1 && tar xf zlib-1.3.1.tar.gz && cd zlib-1.3.1
    CHOST=$HOST ./configure --prefix="$PREFIX/usr"
    make -j"$(nproc)" && make install
    # freetype links with -lz; ensure shared lib is present.
    if [[ ! -f "$OUT_LIB/libz.so" && -f "$OUT_LIB/libz.so.1" ]]; then
        ln -sf libz.so.1 "$OUT_LIB/libz.so"
    fi
fi

# --- bzip2 ---
if [[ ! -f "$OUT_LIB/libbz2.so" ]]; then
    cd "$SRC_DIR"
    [[ -f bzip2-1.0.8.tar.gz ]] || fetch bzip2-1.0.8.tar.gz https://sourceware.org/pub/bzip2/bzip2-1.0.8.tar.gz
    rm -rf bzip2-1.0.8 && tar xf bzip2-1.0.8.tar.gz && cd bzip2-1.0.8
    make -f Makefile-libbz2_so CC="$CC" AR="$AR" RANLIB="$RANLIB" CFLAGS="$CFLAGS"
    cp -a libbz2.so* "$OUT_LIB/"
    ln -sf libbz2.so.1.0.8 "$OUT_LIB/libbz2.so"
    ln -sf libbz2.so.1.0.8 "$OUT_LIB/libbz2.so.1"
    mkdir -p "$PREFIX/usr/include"
    cp bzlib.h "$PREFIX/usr/include/"
fi

# --- libpng ---
if [[ ! -f "$OUT_LIB/libpng16.so" ]]; then
    cd "$SRC_DIR"
    [[ -f libpng-1.6.43.tar.xz ]] || fetch libpng-1.6.43.tar.xz https://download.sourceforge.net/libpng/libpng-1.6.43.tar.xz
    rm -rf libpng-1.6.43 && tar xf libpng-1.6.43.tar.xz && cd libpng-1.6.43
    ./configure --host=$HOST --prefix="$PREFIX/usr" --disable-static
    make -j"$(nproc)" && make install
fi

# --- brotli ---
if [[ ! -f "$OUT_LIB/libbrotlidec.so" ]]; then
    cd "$SRC_DIR"
    [[ -f v1.1.0.tar.gz ]] || fetch v1.1.0.tar.gz https://github.com/google/brotli/archive/refs/tags/v1.1.0.tar.gz
    rm -rf brotli-1.1.0 && tar xf v1.1.0.tar.gz && cd brotli-1.1.0
    mkdir -p build && cd build
    cmake .. -DCMAKE_TOOLCHAIN_FILE="$NDK_ROOT/build/cmake/android.toolchain.cmake" \
        -DANDROID_ABI=x86_64 -DANDROID_PLATFORM=android-$API \
        -DCMAKE_INSTALL_PREFIX="$PREFIX/usr" -DBUILD_SHARED_LIBS=ON
    make -j"$(nproc)" && make install
fi

# --- freetype ---
if [[ ! -f "$OUT_LIB/libfreetype.so" ]]; then
    cd "$SRC_DIR"
    [[ -f freetype-2.13.2.tar.xz ]] || fetch freetype-2.13.2.tar.xz https://download.savannah.gnu.org/releases/freetype/freetype-2.13.2.tar.xz
    rm -rf freetype-2.13.2 && tar xf freetype-2.13.2.tar.xz && cd freetype-2.13.2
    ./configure --host=$HOST --prefix="$PREFIX/usr" --disable-static \
        --with-zlib=yes --with-bzip2=yes --with-png=yes --with-brotli=yes \
        BZIP2_CFLAGS="-I$PREFIX/usr/include" BZIP2_LIBS="-L$OUT_LIB -lbz2"
    make -j"$(nproc)" && make install
fi

# --- expat ---
if [[ ! -f "$OUT_LIB/libexpat.so" ]]; then
    cd "$SRC_DIR"
    [[ -f expat-2.6.2.tar.gz ]] || fetch expat-2.6.2.tar.gz https://github.com/libexpat/libexpat/releases/download/R_2_6_2/expat-2.6.2.tar.gz
    rm -rf expat-2.6.2 && tar xf expat-2.6.2.tar.gz && cd expat-2.6.2
    ./configure --host=$HOST --prefix="$PREFIX/usr" --disable-static
    make -j"$(nproc)" && make install
fi

# --- libxml2 ---
if [[ ! -f "$OUT_LIB/libxml2.so" ]]; then
    cd "$SRC_DIR"
    [[ -f libxml2-2.12.7.tar.xz ]] || fetch libxml2-2.12.7.tar.xz https://download.gnome.org/sources/libxml2/2.12/libxml2-2.12.7.tar.xz
    rm -rf libxml2-2.12.7 && tar xf libxml2-2.12.7.tar.xz && cd libxml2-2.12.7
    ./configure --host=$HOST --prefix="$PREFIX/usr" --disable-static --without-python --without-lzma --without-zlib
    make -j"$(nproc)" && make install
fi

# --- fontconfig ---
if [[ ! -f "$OUT_LIB/libfontconfig.so" ]]; then
    cd "$SRC_DIR"
    # Debian's pool carries the identical upstream tarball, and is the fallback that has
    # actually been needed: freedesktop.org 418'd this one from a CI runner. Most of the
    # x.org fetches below have no such alternative, since Debian pins other versions.
    [[ -f fontconfig-2.15.0.tar.xz ]] || fetch fontconfig-2.15.0.tar.xz \
        https://www.freedesktop.org/software/fontconfig/release/fontconfig-2.15.0.tar.xz \
        http://deb.debian.org/debian/pool/main/f/fontconfig/fontconfig_2.15.0.orig.tar.xz
    rm -rf fontconfig-2.15.0 && tar xf fontconfig-2.15.0.tar.xz && cd fontconfig-2.15.0
    ./configure --host=$HOST --prefix="$PREFIX/usr" --disable-static --disable-docs \
        PKG_CONFIG_PATH="$PKG_CONFIG_PATH" \
        FREETYPE_CFLAGS="-I$PREFIX/usr/include/freetype2 -I$PREFIX/usr/include" \
        FREETYPE_LIBS="-L$OUT_LIB -lfreetype" \
        EXPAT_CFLAGS="-I$PREFIX/usr/include" EXPAT_LIBS="-L$OUT_LIB -lexpat"
    make -j"$(nproc)" && make install
fi

if [[ ! -f "$OUT_LIB/libfreetype.so" ]]; then
    echo "ERROR: libfreetype.so missing after build" >&2
    exit 1
fi

build_x11_stack

if [[ ! -f "$OUT_LIB/libXext.so" ]]; then
    echo "ERROR: libXext.so missing after X11 build" >&2
    exit 1
fi

install_vulkan_loader_symlink

# Fontconfig configs — reuse the ARM imagefs tree (font files stay in imagefs/usr/share/fonts).
FONTS_REF="${FONTS_REF:-/tmp/imagefs-fonts-ref}"
if [[ -d "$FONTS_REF" ]]; then
    cp -a "$FONTS_REF/." "$OUT_ETC/fonts/"
else
    cat > "$OUT_ETC/fonts/fonts.conf" <<'EOF'
<?xml version="1.0"?>
<!DOCTYPE fontconfig SYSTEM "fonts.dtd">
<fontconfig>
  <dir>/data/user/0/app.gamenative/files/imagefs/usr/share/fonts</dir>
  <cachedir>/data/user/0/app.gamenative/files/host_libs_x86_64/cache/fontconfig</cachedir>
  <include ignore_missing="yes">conf.d</include>
</fontconfig>
EOF
fi

mkdir -p "$(dirname "$ASSET_OUT")"
rm -f "$ASSET_OUT"
tar -I 'zstd -19' -cf "$ASSET_OUT" -C "$PREFIX" usr

echo "Built bionic libs asset: $ASSET_OUT"
find "$OUT_LIB" -maxdepth 1 -name '*.so*' -exec file {} \; | head -20
