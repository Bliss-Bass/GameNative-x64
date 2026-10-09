/*
 * 32-bit Windows code under Wine's WoW64 reads its TEB through %fs. Wine gives each thread a GDT
 * descriptor for that with set_thread_area through int $0x80, which fails on kernels booted with
 * ia32_emulation=false; app seccomp also blocks the 64-bit set_thread_area and modify_ldt. With
 * FSGSBASE the same effect needs no descriptor: load the flat user data selector into %fs and
 * write the 32-bit TEB address into the fs base. When int $0x80 is unavailable, the x86_64
 * ntdll.so is patched as it loads to work that way:
 *
 *  - alloc_fs_sel() reports GDT entry 5 (__USER_DS, 0x2b) instead of calling set_thread_area.
 *  - Every place that loads the 32-bit %fs selector before returning to Windows code is detoured
 *    to also set the fs base to TEB + WowTebOffset.
 *
 * The detours run on the syscall frame stack, so they save registers in unused cpu_data slots of
 * the 64-bit TEB instead of pushing them.
 *
 * wow64cpu.dll reloads %fs from the saved context before its iretq into 32-bit code (thread start,
 * exception and context returns), which would reset the base to 0. %fs is already correct there,
 * since every return from ntdll to Windows code went through a detour, so that reload is turned
 * into a nop in the DLL files before Wine maps them. Wine reads PE sections instead of mapping
 * them, which leaves the files as the only practical place to change.
 */
#define _GNU_SOURCE
#include <android/log.h>
#include <asm/hwcap2.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <limits.h>
#include <link.h>
#include <setjmp.h>
#include <signal.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/auxv.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

#define TAG "Wow64Fs"
#define MODE_ENV "EXEC_REDIRECT_WOW64_FS" /* "off", "force", or unset for auto */
#define USER32_CS 0x23
#define USER64_CS 0x33
#define USER_DS_ENTRY 5
#define JMP_LEN 5
#define MAX_SITES 8

/* TEB offsets: Self 0x30, WowTebOffset 0x180c; 0x360/0x368 are unused amd64 cpu_data slots. */
__asm__(".pushsection .rodata\n"
        ".globl wow64_fs_base_start\n"
        ".hidden wow64_fs_base_start\n"
        ".globl wow64_fs_base_end\n"
        ".hidden wow64_fs_base_end\n"
        "wow64_fs_base_start:\n"
        "    movq %rax, %gs:0x360\n"
        "    movq %rdx, %gs:0x368\n"
        "    movq %gs:0x30, %rax\n"
        "    movslq 0x180c(%rax), %rdx\n"
        "    leaq (%rax,%rdx), %rax\n"
        "    wrfsbase %rax\n"
        "    movq %gs:0x368, %rdx\n"
        "    movq %gs:0x360, %rax\n"
        "wow64_fs_base_end:\n"
        ".popsection\n");
extern const uint8_t wow64_fs_base_start[], wow64_fs_base_end[];

enum site_kind { SITE_COPY, SITE_RIP_LOAD };

struct site {
    uint8_t *at;
    size_t len;
    enum site_kind kind;
};

struct text_range {
    uint8_t *start;
    uint8_t *end;
    char path[PATH_MAX];
};

static sigjmp_buf probe_env;

static void probe_fault(int sig)
{
    (void)sig;
    siglongjmp(probe_env, 1);
}

static bool run_probe(bool (*fn)(void *), void *arg)
{
    static const int sigs[] = { SIGSEGV, SIGBUS, SIGILL, SIGSYS };
    struct sigaction sa, old[4];
    memset(&sa, 0, sizeof(sa));
    sa.sa_handler = probe_fault;
    sigemptyset(&sa.sa_mask);
    for (int i = 0; i < 4; i++) sigaction(sigs[i], &sa, &old[i]);
    bool ok = false;
    if (!sigsetjmp(probe_env, 1)) ok = fn(arg);
    for (int i = 0; i < 4; i++) sigaction(sigs[i], &old[i], NULL);
    return ok;
}

static bool probe_int80(void *arg)
{
    (void)arg;
    long r;
    __asm__ volatile("int $0x80" : "=a"(r) : "a"(20L) : "memory", "r8", "r9", "r10", "r11");
    return r == getpid();
}

/* Far-returns into a 32-bit stub on USER32_CS, which jumps straight back to 64-bit code. */
static bool probe_cs32(void *arg)
{
    uint8_t *page = arg;
    uint32_t back = (uint32_t)(uintptr_t)(page + 0x20);
    uint32_t slot = (uint32_t)(uintptr_t)(page + 0x40);
    uint8_t code32[] = { 0xb8, 42, 0, 0, 0, 0xea, 0, 0, 0, 0, USER64_CS, 0 }; /* mov eax,42; ljmp */
    uint8_t code64[] = { 0x48, 0x8b, 0x24, 0x25, 0, 0, 0, 0, 0xc3 };          /* mov rsp,[slot]; ret */
    memcpy(code32 + 6, &back, 4);
    memcpy(code64 + 4, &slot, 4);
    memcpy(page, code32, sizeof(code32));
    memcpy(page + 0x20, code64, sizeof(code64));

    long result;
    __asm__ volatile("call 2f\n\t"
                     "jmp 3f\n\t"
                     "2:\n\t"
                     "movq %%rsp, (%[slot])\n\t"
                     "pushq %[sel]\n\t"
                     "pushq %[target]\n\t"
                     "lretq\n\t"
                     "3:\n\t"
                     : "=a"(result)
                     : [slot] "r"((uintptr_t)(page + 0x40)), [sel] "r"((uint64_t)USER32_CS),
                       [target] "r"((uintptr_t)page)
                     : "memory", "rcx", "rdx", "rsi", "rdi", "r8", "r9", "r10", "r11");
    return result == 42;
}

static bool cs32_usable(void)
{
    uint8_t *page = mmap(NULL, 4096, PROT_READ | PROT_WRITE | PROT_EXEC,
                         MAP_PRIVATE | MAP_ANONYMOUS | MAP_32BIT, -1, 0);
    if (page == MAP_FAILED) return false;
    bool ok = run_probe(probe_cs32, page);
    munmap(page, 4096);
    return ok;
}

static int find_text(struct dl_phdr_info *info, size_t size, void *data)
{
    (void)size;
    struct text_range *text = data;
    const char *name = info->dlpi_name;
    size_t len = name ? strlen(name) : 0;
    static const char suffix[] = "x86_64-unix/ntdll.so";
    if (len < sizeof(suffix) - 1 || strcmp(name + len - (sizeof(suffix) - 1), suffix)) return 0;
    for (int i = 0; i < info->dlpi_phnum; i++) {
        const ElfW(Phdr) *ph = &info->dlpi_phdr[i];
        if (ph->p_type != PT_LOAD || !(ph->p_flags & PF_X)) continue;
        text->start = (uint8_t *)(info->dlpi_addr + ph->p_vaddr);
        text->end = text->start + ph->p_memsz;
        snprintf(text->path, sizeof(text->path), "%.*s", (int)(len - (sizeof(suffix) - 1)), name);
        return 1;
    }
    return 0;
}

/* Wine guards each 32-bit %fs load with "je" over exactly that instruction. */
static bool skipped_by_je(const uint8_t *at, size_t len, const struct text_range *text)
{
    if (at - 2 >= text->start && at[-2] == 0x74 && at[-1] == len) return true;
    int32_t disp;
    if (at - 6 < text->start || at[-6] != 0x0f || at[-5] != 0x84) return false;
    memcpy(&disp, at - 4, 4);
    return disp == (int32_t)len;
}

static bool add_site(struct site *sites, int *n, uint8_t *at, size_t len, enum site_kind kind)
{
    if (*n >= MAX_SITES) return false;
    sites[(*n)++] = (struct site){ at, len, kind };
    return true;
}

static uint8_t *scan(const struct text_range *text, uint8_t **alloc_call, struct site *sites, int *n_sites)
{
    static const uint8_t set_thread_area[] = { 0xb8, 0xf3, 0x00, 0x00, 0x00, 0xcd, 0x80 };
    static const uint8_t gs_load[] = { 0x65, 0x8e, 0x24, 0x25, 0x38, 0x03, 0x00, 0x00 };
    int alloc_hits = 0, gs_hits = 0, reg_hits = 0, rip_hits = 0;
    *n_sites = 0;
    for (uint8_t *p = text->start; p + 9 <= text->end; p++) {
        if (!memcmp(p, set_thread_area, sizeof(set_thread_area))) {
            *alloc_call = p;
            alloc_hits++;
        } else if (!memcmp(p, gs_load, sizeof(gs_load))) {
            /* mov %gs:0x338,%fs: the per-thread selector in amd64_thread_data */
            if (skipped_by_je(p, sizeof(gs_load), text) && add_site(sites, n_sites, p, sizeof(gs_load), SITE_COPY))
                gs_hits++;
        } else if (p[0] == 0x8e && (p[1] & 0xf8) == 0xa0 && p[1] != 0xa4 && !memcmp(p + 2, "\x38\x03\x00\x00", 4)) {
            /* mov 0x338(%reg),%fs, optionally with a REX.B prefix */
            uint8_t *at = p;
            size_t len = 6;
            if (p > text->start && p[-1] == 0x41 && skipped_by_je(p - 1, 7, text)) { at = p - 1; len = 7; }
            else if (!skipped_by_je(p, 6, text)) continue;
            if (add_site(sites, n_sites, at, len, SITE_COPY)) reg_hits++;
        } else if (p[0] == 0x66 && p[1] == 0x8b && p[2] == 0x05 && p[7] == 0x8e && p[8] == 0xe0) {
            /* mov fs32_sel(%rip),%ax; mov %eax,%fs in the signal handler exit path */
            if (add_site(sites, n_sites, p, 9, SITE_RIP_LOAD)) rip_hits++;
        }
    }
    if (alloc_hits != 1 || gs_hits != 2 || reg_hits != 1 || rip_hits != 1) {
        __android_log_print(ANDROID_LOG_ERROR, TAG,
                            "unrecognised ntdll.so (alloc %d, gs %d, reg %d, signal %d)",
                            alloc_hits, gs_hits, reg_hits, rip_hits);
        return NULL;
    }
    return *alloc_call;
}

static bool rel32_reachable(const uint8_t *from, const uint8_t *to)
{
    intptr_t d = (intptr_t)to - (intptr_t)from;
    return d > INT32_MIN / 2 && d < INT32_MAX / 2;
}

static uint8_t *map_near(const struct text_range *text, size_t size)
{
    for (uintptr_t step = 1; step <= 256; step++) {
        for (int dir = -1; dir <= 1; dir += 2) {
            uintptr_t base = (uintptr_t)(dir < 0 ? text->start : text->end);
            uintptr_t hint = ((base + dir * (intptr_t)(step << 22)) & ~(uintptr_t)0xfff);
            void *p = mmap((void *)hint, size, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
            if (p == MAP_FAILED) continue;
            if (rel32_reachable(p, text->start) && rel32_reachable(p, text->end)) return p;
            munmap(p, size);
        }
    }
    return NULL;
}

static uint8_t *emit_jmp(uint8_t *at, const uint8_t *target)
{
    int32_t rel = (int32_t)(target - (at + JMP_LEN));
    at[0] = 0xe9;
    memcpy(at + 1, &rel, 4);
    return at + JMP_LEN;
}

static uint8_t *emit_detour(uint8_t *out, const struct site *site)
{
    if (site->kind == SITE_RIP_LOAD) {
        int32_t disp;
        memcpy(&disp, site->at + 3, 4);
        const uint8_t *var = site->at + 7 + disp;
        int32_t moved = (int32_t)(var - (out + 7));
        memcpy(out, site->at, 3);
        memcpy(out + 3, &moved, 4);
        memcpy(out + 7, site->at + 7, 2);
        out += 9;
    } else {
        memcpy(out, site->at, site->len);
        out += site->len;
    }
    size_t body = (size_t)(wow64_fs_base_end - wow64_fs_base_start);
    memcpy(out, wow64_fs_base_start, body);
    return emit_jmp(out + body, site->at + site->len);
}

static bool write_text(uint8_t *at, const uint8_t *bytes, size_t len)
{
    uintptr_t page = (uintptr_t)sysconf(_SC_PAGESIZE);
    uintptr_t start = (uintptr_t)at & ~(page - 1);
    size_t span = (((uintptr_t)at + len + page - 1) & ~(page - 1)) - start;
    if (mprotect((void *)start, span, PROT_READ | PROT_WRITE)) return false;
    memcpy(at, bytes, len);
    /* Resolves to the execmod-safe mprotect in exec_redirect.c. */
    return !mprotect((void *)start, span, PROT_READ | PROT_EXEC);
}

/* mov 0x90(%r13),%fs (context->SegFs) becomes a 7-byte nop. */
static void patch_wow64cpu_file(const char *path)
{
    static const uint8_t fs_reload[] = { 0x41, 0x8e, 0xa5, 0x90, 0x00, 0x00, 0x00 };
    static const uint8_t nop7[] = { 0x0f, 0x1f, 0x80, 0x00, 0x00, 0x00, 0x00 };
    int fd = open(path, O_RDWR | O_CLOEXEC);
    if (fd < 0) return;
    struct stat st;
    uint8_t *data = NULL;
    if (fstat(fd, &st) || st.st_size <= 0 || st.st_size > (4 << 20) || !(data = malloc(st.st_size)) ||
        pread(fd, data, st.st_size, 0) != st.st_size) {
        free(data);
        close(fd);
        return;
    }
    off_t at = -1;
    int hits = 0;
    for (off_t i = 0; i + (off_t)sizeof(fs_reload) <= st.st_size; i++) {
        if (!memcmp(data + i, fs_reload, sizeof(fs_reload))) { at = i; hits++; }
    }
    free(data);
    if (hits == 1 && pwrite(fd, nop7, sizeof(nop7), at) == (ssize_t)sizeof(nop7))
        __android_log_print(ANDROID_LOG_INFO, TAG, "%s: context %%fs reload removed", path);
    else if (hits > 1)
        __android_log_print(ANDROID_LOG_ERROR, TAG, "%s: unrecognised layout (%d matches)", path, hits);
    close(fd);
}

static void patch_wow64cpu(const char *wine_lib_dir)
{
    char path[PATH_MAX];
    snprintf(path, sizeof(path), "%sx86_64-windows/wow64cpu.dll", wine_lib_dir);
    patch_wow64cpu_file(path);
    const char *prefix = getenv("WINEPREFIX");
    if (prefix && *prefix) {
        snprintf(path, sizeof(path), "%s/drive_c/windows/system32/wow64cpu.dll", prefix);
        patch_wow64cpu_file(path);
    }
}

static void patch_ntdll(void)
{
    struct text_range text;
    memset(&text, 0, sizeof(text));
    if (!dl_iterate_phdr(find_text, &text)) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "x86_64 ntdll.so text not found");
        return;
    }
    struct site sites[MAX_SITES];
    int n_sites;
    uint8_t *alloc_call;
    if (!scan(&text, &alloc_call, sites, &n_sites)) return;

    size_t page = (size_t)sysconf(_SC_PAGESIZE);
    uint8_t *tramp = map_near(&text, page);
    if (!tramp) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "no trampoline page within reach of ntdll.so");
        return;
    }
    uint8_t *out = tramp;
    uint8_t *entries[MAX_SITES];
    for (int i = 0; i < n_sites; i++) {
        entries[i] = out;
        out = emit_detour(out, &sites[i]);
    }
    if (mprotect(tramp, page, PROT_READ | PROT_EXEC)) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "trampoline page not executable");
        munmap(tramp, page);
        return;
    }

    /* movl $USER_DS_ENTRY,(%rsp): alloc_fs_sel() then returns the entry it reads back from there. */
    static const uint8_t report_entry[] = { 0xc7, 0x04, 0x24, USER_DS_ENTRY, 0x00, 0x00, 0x00 };
    bool ok = write_text(alloc_call, report_entry, sizeof(report_entry));
    for (int i = 0; ok && i < n_sites; i++) {
        uint8_t jmp[16];
        int32_t rel = (int32_t)(entries[i] - (sites[i].at + JMP_LEN));
        jmp[0] = 0xe9;
        memcpy(jmp + 1, &rel, 4);
        memset(jmp + JMP_LEN, 0x90, sites[i].len - JMP_LEN);
        ok = write_text(sites[i].at, jmp, sites[i].len);
    }
    if (ok) {
        __android_log_print(ANDROID_LOG_INFO, TAG, "32-bit %%fs uses fs base writes (%d sites)", n_sites);
        patch_wow64cpu(text.path);
    } else
        __android_log_print(ANDROID_LOG_ERROR, TAG, "patching ntdll.so failed part way");
}

static void setup_wow64_fs(void)
{
    const char *mode = getenv(MODE_ENV);
    if (mode && !strcmp(mode, "off")) return;
    bool forced = mode && !strcmp(mode, "force");
    if (!forced && run_probe(probe_int80, NULL)) {
        __android_log_print(ANDROID_LOG_INFO, TAG, "kernel runs 32-bit syscalls; ntdll.so left as is");
        return;
    }
    if (!(getauxval(AT_HWCAP2) & HWCAP2_FSGSBASE) || !cs32_usable()) {
        __android_log_print(ANDROID_LOG_ERROR, TAG,
                            "32-bit Windows programs are not supported on this device "
                            "(no ia32 syscalls, and %s)",
                            (getauxval(AT_HWCAP2) & HWCAP2_FSGSBASE) ? "no 32-bit code segment" : "no FSGSBASE");
        return;
    }
    patch_ntdll();
}

typedef void *(*dlopen_fn)(const char *, int);

void *dlopen(const char *file, int flags)
{
    static dlopen_fn real_fn;
    static bool done;
    if (!real_fn) real_fn = (dlopen_fn)dlsym(RTLD_NEXT, "dlopen");
    if (!real_fn) return NULL;
    void *handle = real_fn(file, flags);
    if (handle && !done && file) {
        const char *base = strrchr(file, '/');
        if (!strcmp(base ? base + 1 : file, "ntdll.so")) {
            done = true;
            setup_wow64_fs();
        }
    }
    return handle;
}
