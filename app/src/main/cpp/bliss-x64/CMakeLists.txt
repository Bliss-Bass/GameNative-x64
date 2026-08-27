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
# evshim: requires vendored SDL2 headers (app/src/main/cpp/third_party/SDL2/).
# Enable once arch.h x86_64 port and SDL2 headers are present.
# add_subdirectory(${GN_CPP}/proot ${CMAKE_BINARY_DIR}/proot)
# add_subdirectory(${GN_CPP}/evshim ${CMAKE_BINARY_DIR}/evshim)

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

add_library(extras SHARED ${GN_CPP}/extras/gpu_image.c)
target_link_libraries(extras log android EGL GLESv2 GLESv3)
target_link_options(extras PRIVATE -Wl,-z,max-page-size=16384)
