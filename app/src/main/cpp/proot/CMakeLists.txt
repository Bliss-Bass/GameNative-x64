cmake_minimum_required(VERSION 3.22.1)

project(PRoot)

set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O2 -Wno-unused-function -Wimplicit-function-declaration")

add_library(talloc STATIC
            talloc/talloc.c)

include_directories(src
                    talloc)

add_executable(libproot.so
               src/cli/cli.c
               src/cli/proot.c
               src/cli/note.c
               src/execve/enter.c
               src/execve/exit.c
               src/execve/elf.c
               src/execve/auxv.c
               src/path/binding.c
               src/path/glue.c
               src/path/canon.c
               src/path/path.c
               src/path/proc.c
               src/path/temp.c
               src/syscall/seccomp.c
               src/syscall/syscall.c
               src/syscall/chain.c
               src/syscall/enter.c
               src/syscall/exit.c
               src/syscall/sysnum.c
               src/syscall/socket.c
               src/syscall/heap.c
               src/syscall/rlimit.c
               src/tracee/tracee.c
               src/tracee/mem.c
               src/tracee/reg.c
               src/tracee/event.c
               src/tracee/seccomp.c
               src/ptrace/ptrace.c
               src/ptrace/wait.c)

target_link_libraries(libproot.so
                      talloc)

# Despite the .so name (Android only executes files from the app's lib dir), the
# loader is not a library: PRoot execve()s it and the kernel enters it at _start
# with a hand-built stack. It must therefore be static, freestanding, and linked
# at LOADER_ADDRESS. Built as a shared object it loads as ET_DYN and every guest
# exec dies with SIGSEGV.
#
# LOADER_ADDRESS mirrors src/arch.h; keep the two in sync.
set(PROOT_LOADER_ADDRESS 0x600000000000)

add_executable(libproot-loader.so
               src/loader/loader.c)

target_compile_options(libproot-loader.so PRIVATE -fPIC -ffreestanding)

target_link_options(libproot-loader.so PRIVATE
                    -static -nostdlib -nostartfiles
                    -Wl,-e,_start
                    -Wl,-Ttext=${PROOT_LOADER_ADDRESS}
                    # Without a separate read-only segment the linker tries to
                    # cover address 0 through LOADER_ADDRESS in one mapping and
                    # fails with "File too large".
                    -Wl,--rosegment
                    -Wl,--build-id=none
                    -Wl,-z,noexecstack)