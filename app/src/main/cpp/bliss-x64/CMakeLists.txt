cmake_minimum_required(VERSION 3.22.1)
project(BlissGameNativeX64)

# Bliss x86_64 JNI — builds open-source GameNative natives without adrenotools.
# Invoked by scripts/build-x86_64-jni.sh (NDK standalone toolchain).

get_filename_component(GN_CPP "${CMAKE_CURRENT_LIST_DIR}/.." ABSOLUTE)

set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O2 -Wno-unused-function -Wimplicit-function-declaration")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O2 -std=c++17")
set(CMAKE_SHARED_LINKER_FLAGS_RELEASE "${CMAKE_SHARED_LINKER_FLAGS_RELEASE} -s")

add_subdirectory(${GN_CPP}/virglrenderer ${CMAKE_BINARY_DIR}/virglrenderer)
add_subdirectory(${GN_CPP}/patchelf ${CMAKE_BINARY_DIR}/patchelf)
add_subdirectory(${GN_CPP}/xconnectorpatch ${CMAKE_BINARY_DIR}/xconnectorpatch)

# proot: upstream tree is ARM-only (arch.h); modern Bionic uses linker64 without proot.
# evshim: full build needs vendored SDL2 headers (app/src/main/cpp/third_party/SDL2/).
# Host stub covers WinHandler JNI until SDL headers are vendored for Wine virtual joysticks.
add_library(evshim SHARED ${CMAKE_CURRENT_LIST_DIR}/evshim_host.c)
target_link_libraries(evshim log)
target_link_options(evshim PRIVATE -Wl,-z,max-page-size=16384)

add_library(winlator SHARED
    ${GN_CPP}/winlator/sysvshared_memory.c
    ${GN_CPP}/winlator/xconnector_epoll.c
    ${GN_CPP}/winlator/alsa_client.c
    ${GN_CPP}/winlator/patchelf_wrapper.cpp)

target_link_libraries(winlator log android jnigraphics aaudio EGL GLESv2 GLESv3)
target_link_options(winlator PRIVATE -Wl,-z,max-page-size=16384)

add_custom_command(
    OUTPUT ${CMAKE_CURRENT_BINARY_DIR}/window_vert.h
    COMMAND glslangValidator -V --vn window_vert_code -x
            -o ${CMAKE_CURRENT_BINARY_DIR}/window_vert.h
            ${GN_CPP}/winlator/window.vert
    DEPENDS ${GN_CPP}/winlator/window.vert)

add_custom_command(
    OUTPUT ${CMAKE_CURRENT_BINARY_DIR}/window_frag.h
    COMMAND glslangValidator -V --vn window_frag_code -x
            -o ${CMAKE_CURRENT_BINARY_DIR}/window_frag.h
            ${GN_CPP}/winlator/window.frag
    DEPENDS ${GN_CPP}/winlator/window.frag)

add_library(vulkan_renderer SHARED
    ${GN_CPP}/winlator/vulkan_jni.cpp
    ${GN_CPP}/winlator/VulkanRendererContext.cpp
    ${GN_CPP}/winlator/VulkanRendererScanout.cpp
    ${CMAKE_CURRENT_BINARY_DIR}/window_vert.h
    ${CMAKE_CURRENT_BINARY_DIR}/window_frag.h)

target_include_directories(vulkan_renderer PRIVATE ${CMAKE_CURRENT_BINARY_DIR})
target_compile_options(vulkan_renderer PRIVATE -Wall -Wextra -fvisibility=hidden -std=c++17)
target_compile_definitions(vulkan_renderer PRIVATE BLISS_HOST_X86_64=1)
target_link_libraries(vulkan_renderer log android vulkan dl atomic)
target_link_options(vulkan_renderer PRIVATE -Wl,-z,max-page-size=16384)

add_library(extras SHARED
    ${GN_CPP}/extras/gpu_image.c
    ${GN_CPP}/extras/vulkan.c)
target_link_libraries(extras log android EGL GLESv2 GLESv3)
target_link_options(extras PRIVATE -Wl,-z,max-page-size=16384)

# X11 drawable raster + Vulkan device queries (loaded as winlator_11 by Drawable/GPUHelper).
add_library(winlator_11 SHARED
    ${GN_CPP}/asurfacerenderer/drawable.c
    ${GN_CPP}/winlator/gpu_helper.c)
target_link_libraries(winlator_11 log android jnigraphics vulkan dl)
target_link_options(winlator_11 PRIVATE -Wl,-z,max-page-size=16384)

# Preloaded into guest processes as well as loaded by SysVSharedMemory, so it carries both the JNI
# entry points and the shmget/shmat interposers that give MIT-SHM ids the X server recognises.
add_library(android-sysvshm SHARED
    ${GN_CPP}/winlator/sysvshared_memory.c
    ${GN_CPP}/winlator/sysvshm_interposer.c)
set_target_properties(android-sysvshm PROPERTIES OUTPUT_NAME "android-sysvshm")
target_link_libraries(android-sysvshm log android)
target_link_options(android-sysvshm PRIVATE -Wl,-z,max-page-size=16384)

# The same code again under the name Mesa's ICDs were linked against.
#
# LD_PRELOAD is not enough for them: libvulkan_intel.so carries a DT_NEEDED on
# libandroid-shmem.so, and bionic resolves a symbol referenced from a dlopen'd library out of that
# library's own group before it looks at the preloads, so Termux's implementation wins and hands
# out shmids the X server never issued. Staging this over the shipped libandroid-shmem.so puts our
# broker behind the name Mesa already asks for.
add_library(android-shmem SHARED
    ${GN_CPP}/winlator/sysvshared_memory.c
    ${GN_CPP}/winlator/sysvshm_interposer.c)
set_target_properties(android-shmem PROPERTIES OUTPUT_NAME "android-shmem")
target_link_libraries(android-shmem log android)
target_link_options(android-shmem PRIVATE -Wl,-z,max-page-size=16384)
