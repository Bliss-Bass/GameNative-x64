cmake_minimum_required(VERSION 3.22.1)
project(termux_pty)

# libtermux.so: the PTY setup behind com.termux.terminal.JNI, vendored from Termux
# (Apache-2.0) along with the terminal emulator and view it belongs to.
#
# Built by scripts/build-x86_64-termux-pty.sh and shipped as a prebuilt jniLib, like the
# other native pieces here -- the release packaging flow does not run
# externalNativeBuild. Upstream builds it with ndk-build; the flags below are its
# Android.mk's, minus -Werror, which turns any NDK warning change into a build break.
add_library(termux SHARED termux.c)

target_compile_options(termux PRIVATE
                       -std=c11 -Wall -Wextra -Os -fno-stack-protector)

target_link_options(termux PRIVATE -Wl,--gc-sections)

target_link_libraries(termux log)
