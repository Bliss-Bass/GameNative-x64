#!/system/bin/sh
# Run a native x86_64 Vulkan program inside a GameNative container environment, against
# the app's X server and SysV shm broker, with the WSI mode chosen per run. This exists so
# the presentation paths can be exercised in seconds instead of by launching a game.
#
# Usage, on the device:
#   run-as app.gamenative sh -c 'APKLIB=<native lib dir> \
#       sh files/vktest/guest_vk_run.sh sw files/vktest/vkcube --c 60'
#
# The mode is passed straight to MESA_VK_WSI_DEBUG: "sw,noshm" is the socket copy path that
# ships today, "sw" adds MIT-SHM. APKLIB is the installed app's native library directory,
# which the app itself cannot glob (/data/app is not listable), so pass it in:
#   APK=$(adb shell pm path app.gamenative | sed 's/package://'); dirname $APK)/lib/x86_64
#
# A container session must already be up, so that the X server socket, the broker socket and
# the staged host libraries all exist. Any container will do; a small custom game entry
# (an imported folder with winemine.exe in it) boots one in about half a minute.
set -e

F=/data/data/app.gamenative/files
APKLIB=${APKLIB:?set APKLIB to the native library dir of the installed app}
ICD=$F/host_vk_x86_64/usr/share/vulkan/icd.d

MODE="$1"
shift

export HOME=$F/imagefs/home/xuser
export USER=xuser
export TMPDIR=$F/imagefs/tmp
export XDG_RUNTIME_DIR=$F/imagefs/tmp
export DISPLAY=$F/imagefs/tmp/.X11-unix/X0
export PATH=$F/imagefs/usr/bin
# Note imagefs/usr/lib is deliberately absent: the staged rootfs is aarch64 even on x86_64,
# so adding it only produces "is for EM_AARCH64" link failures. Put extra x86_64 libraries
# a test binary needs in files/vktest/lib instead.
export LD_LIBRARY_PATH=$F/host_vk_x86_64/usr/lib:/system/lib64:$F/host_libs_x86_64/usr/lib:$APKLIB:$F/vktest/lib
export LD_PRELOAD=$APKLIB/libandroid-sysvshm.so
export ANDROID_SYSVSHM_SERVER=$F/imagefs/tmp/.sysvshm/SM0
export ANDROID_SYSVSHM_DEBUG=1
export VK_DRIVER_FILES=$ICD/intel_icd.x86_64.json:$ICD/lvp_icd.x86_64.json:$ICD/radeon_icd.x86_64.json
export VK_ICD_FILENAMES=$VK_DRIVER_FILES
export MESA_VK_WSI_DEBUG="$MODE"
export MESA_VK_WSI_PRESENT_MODE=fifo
export LC_ALL=en_US.utf8

echo "MESA_VK_WSI_DEBUG=$MESA_VK_WSI_DEBUG running: $*"
exec /system/bin/linker64 "$@"
