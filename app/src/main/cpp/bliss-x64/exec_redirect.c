/*
 * Preloaded into Wine processes on x86_64 hosts.
 *
 * Apps targeting API 29+ may not execve() files under their data directory (SELinux denies
 * execute_no_trans on app_data_file), but the system linker may still map them. Wine re-execs
 * its own loader, spawns wineserver and starts every Windows child through exec, so those calls
 * are rerouted through /system/bin/linker64 here. Images where a direct exec is allowed (system
 * partitions, permissive policies) keep the direct exec.
 *
 * The same policy denies execmod, so file pages Wine has relocated in place (every PE image it
 * maps) cannot be made executable; mprotect() moves those pages to anonymous memory first.
 */
#define _GNU_SOURCE
#include <android/log.h>
#include <dlfcn.h>
#include <elf.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <spawn.h>
#include <stdarg.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/syscall.h>
#include <unistd.h>

#define TAG "ExecRedirect"
#define SELF_EXE_ENV "REDIRECT_EXEC__PROC_SELF_EXE"
#define DEFAULT_PATH "/system/bin:/system/xbin"
#define MAX_SCRIPT_DEPTH 4

extern char **environ;

typedef int (*execve_fn)(const char *, char *const[], char *const[]);
typedef int (*spawn_fn)(pid_t *, const char *, const posix_spawn_file_actions_t *,
                        const posix_spawnattr_t *, char *const[], char *const[]);
typedef ssize_t (*readlink_fn)(const char *, char *, size_t);
typedef ssize_t (*readlinkat_fn)(int, const char *, char *, size_t);
typedef char *(*realpath_fn)(const char *, char *);

static void *next_symbol(const char *name)
{
    void *sym = dlsym(RTLD_NEXT, name);
    if (!sym) __android_log_print(ANDROID_LOG_ERROR, TAG, "dlsym(%s) failed", name);
    return sym;
}

#define REAL(type, name) \
    static type real_fn; \
    if (!real_fn) real_fn = (type)next_symbol(name)

enum image_kind { IMAGE_OTHER, IMAGE_ELF_DYNAMIC, IMAGE_ELF_STATIC, IMAGE_SCRIPT };

struct image_info {
    enum image_kind kind;
    bool elf32;
    char interp[PATH_MAX];
    char interp_arg[PATH_MAX];
};

static bool on_system_partition(const char *path)
{
    static const char *const prefixes[] = {
        "/system/", "/apex/", "/vendor/", "/product/", "/system_ext/", "/odm/", "/bin/",
    };
    for (size_t i = 0; i < sizeof(prefixes) / sizeof(prefixes[0]); i++) {
        if (!strncmp(path, prefixes[i], strlen(prefixes[i]))) return true;
    }
    return false;
}

static void parse_shebang(char *line, struct image_info *info)
{
    char *p = line + 2;
    char *nl = strchr(p, '\n');
    if (!nl) return;
    *nl = 0;
    while (*p == ' ' || *p == '\t') p++;
    char *end = p;
    while (*end && *end != ' ' && *end != '\t') end++;
    if (end == p || (size_t)(end - p) >= sizeof(info->interp)) return;
    memcpy(info->interp, p, end - p);
    info->interp[end - p] = 0;

    while (*end == ' ' || *end == '\t') end++;
    char *arg_end = end + strlen(end);
    while (arg_end > end && (arg_end[-1] == ' ' || arg_end[-1] == '\t' || arg_end[-1] == '\r')) arg_end--;
    if ((size_t)(arg_end - end) < sizeof(info->interp_arg)) {
        memcpy(info->interp_arg, end, arg_end - end);
        info->interp_arg[arg_end - end] = 0;
    }
    info->kind = IMAGE_SCRIPT;
}

static void inspect_image(const char *path, struct image_info *info)
{
    memset(info, 0, sizeof(*info));
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return;

    char head[512];
    ssize_t n = pread(fd, head, sizeof(head) - 1, 0);
    if (n >= 4 && !memcmp(head, ELFMAG, SELFMAG)) {
        info->kind = IMAGE_ELF_STATIC;
        info->elf32 = head[EI_CLASS] == ELFCLASS32;
        if (!info->elf32 && n >= (ssize_t)sizeof(Elf64_Ehdr)) {
            Elf64_Ehdr eh;
            memcpy(&eh, head, sizeof(eh));
            for (int i = 0; i < eh.e_phnum; i++) {
                Elf64_Phdr ph;
                if (pread(fd, &ph, sizeof(ph), eh.e_phoff + (off_t)i * eh.e_phentsize) != sizeof(ph)) break;
                if (ph.p_type == PT_INTERP) { info->kind = IMAGE_ELF_DYNAMIC; break; }
            }
        } else if (info->elf32 && n >= (ssize_t)sizeof(Elf32_Ehdr)) {
            Elf32_Ehdr eh;
            memcpy(&eh, head, sizeof(eh));
            for (int i = 0; i < eh.e_phnum; i++) {
                Elf32_Phdr ph;
                if (pread(fd, &ph, sizeof(ph), eh.e_phoff + (off_t)i * eh.e_phentsize) != sizeof(ph)) break;
                if (ph.p_type == PT_INTERP) { info->kind = IMAGE_ELF_DYNAMIC; break; }
            }
        }
    } else if (n >= 2 && head[0] == '#' && head[1] == '!') {
        head[n] = 0;
        parse_shebang(head, info);
    }
    close(fd);
}

static size_t count_args(char *const argv[])
{
    size_t n = 0;
    if (argv) while (argv[n]) n++;
    return n;
}

/*
 * Copies envp with SELF_EXE_ENV pointing at the program being started, so the child can answer
 * /proc/self/exe lookups with its own path instead of the linker's. Callers provide the storage
 * so this stays allocation-free in vfork()ed children.
 */
static void build_env(char *const envp[], const char *exe, char **out, char *entry, size_t entry_size)
{
    size_t prefix_len = strlen(SELF_EXE_ENV "=");
    size_t j = 0;
    if (envp) {
        for (size_t i = 0; envp[i]; i++) {
            if (!strncmp(envp[i], SELF_EXE_ENV "=", prefix_len)) continue;
            out[j++] = envp[i];
        }
    }
    char abs[PATH_MAX];
    const char *resolved = exe;
    if (exe[0] != '/' && getcwd(abs, sizeof(abs))) {
        size_t len = strlen(abs);
        snprintf(abs + len, sizeof(abs) - len, "/%s", exe);
        resolved = abs;
    }
    snprintf(entry, entry_size, SELF_EXE_ENV "=%s", resolved);
    out[j++] = entry;
    out[j] = NULL;
}

static int redirected_execve(const char *path, char *const argv[], char *const envp[], int depth);

static int exec_via_interpreter(const char *path, const struct image_info *info, char *const argv[],
                                char *const envp[], int depth)
{
    size_t argc = count_args(argv);
    char *args[argc + 4];
    size_t j = 0;
    args[j++] = (char *)info->interp;
    if (info->interp_arg[0]) args[j++] = (char *)info->interp_arg;
    args[j++] = (char *)path;
    for (size_t i = 1; i < argc; i++) args[j++] = argv[i];
    args[j] = NULL;
    return redirected_execve(info->interp, args, envp, depth + 1);
}

static int exec_via_linker(const char *path, const struct image_info *info, char *const argv[],
                           char *const envp[])
{
    REAL(execve_fn, "execve");
    const char *linker = info->elf32 ? "/system/bin/linker" : "/system/bin/linker64";
    size_t argc = count_args(argv);
    size_t envc = count_args(envp);
    char *args[argc + 3];
    char *env[envc + 2];
    char entry[PATH_MAX + sizeof(SELF_EXE_ENV) + 2];

    args[0] = (char *)linker;
    args[1] = (char *)path;
    for (size_t i = 1; i < argc; i++) args[i + 1] = argv[i];
    args[argc < 1 ? 2 : argc + 1] = NULL;
    build_env(envp, path, env, entry, sizeof(entry));

    __android_log_print(ANDROID_LOG_INFO, TAG, "exec %s via %s", path, linker);
    return real_fn ? real_fn(linker, args, env) : (errno = ENOSYS, -1);
}

static int redirected_execve(const char *path, char *const argv[], char *const envp[], int depth)
{
    REAL(execve_fn, "execve");
    if (!real_fn) { errno = ENOSYS; return -1; }

    int ret = real_fn(path, argv, envp);
    if (errno != EACCES || on_system_partition(path) || depth > MAX_SCRIPT_DEPTH) return ret;

    struct image_info info;
    inspect_image(path, &info);
    if (info.kind == IMAGE_ELF_DYNAMIC) exec_via_linker(path, &info, argv, envp);
    else if (info.kind == IMAGE_SCRIPT) exec_via_interpreter(path, &info, argv, envp, depth);
    /* Static images cannot run under the linker; report the original denial so callers
       such as Wine's preloader fallback move on to the next candidate. */
    errno = EACCES;
    return -1;
}

int execve(const char *path, char *const argv[], char *const envp[])
{
    return redirected_execve(path, argv, envp, 0);
}

int execv(const char *path, char *const argv[])
{
    return redirected_execve(path, argv, environ, 0);
}

static int search_path_exec(const char *file, char *const argv[], char *const envp[])
{
    if (!file || !*file) { errno = ENOENT; return -1; }
    if (strchr(file, '/')) return redirected_execve(file, argv, envp, 0);

    const char *search = getenv("PATH");
    if (!search || !*search) search = DEFAULT_PATH;
    size_t file_len = strlen(file);
    bool saw_eacces = false;
    const char *p = search;
    while (true) {
        const char *colon = strchr(p, ':');
        size_t dir_len = colon ? (size_t)(colon - p) : strlen(p);
        char candidate[PATH_MAX];
        if (dir_len + file_len + 2 <= sizeof(candidate)) {
            if (dir_len) {
                memcpy(candidate, p, dir_len);
                candidate[dir_len] = '/';
                memcpy(candidate + dir_len + 1, file, file_len + 1);
            } else {
                memcpy(candidate, file, file_len + 1);
            }
            redirected_execve(candidate, argv, envp, 0);
            if (errno == EACCES) saw_eacces = true;
            else if (errno != ENOENT && errno != ENOTDIR) return -1;
        }
        if (!colon) break;
        p = colon + 1;
    }
    errno = saw_eacces ? EACCES : ENOENT;
    return -1;
}

int execvp(const char *file, char *const argv[])
{
    return search_path_exec(file, argv, environ);
}

int execvpe(const char *file, char *const argv[], char *const envp[])
{
    return search_path_exec(file, argv, envp);
}

#define COLLECT_VARARGS(first, ap, args)                                  \
    size_t n_ = 1;                                                        \
    va_start(ap, first);                                                  \
    while (va_arg(ap, char *)) n_++;                                      \
    va_end(ap);                                                           \
    char *args[n_ + 1];                                                   \
    va_start(ap, first);                                                  \
    args[0] = (char *)(first);                                            \
    for (size_t i_ = 1; i_ <= n_; i_++) args[i_] = va_arg(ap, char *);

int execl(const char *path, const char *arg0, ...)
{
    va_list ap;
    COLLECT_VARARGS(arg0, ap, args);
    va_end(ap);
    return redirected_execve(path, args, environ, 0);
}

int execlp(const char *file, const char *arg0, ...)
{
    va_list ap;
    COLLECT_VARARGS(arg0, ap, args);
    va_end(ap);
    return search_path_exec(file, args, environ);
}

int execle(const char *path, const char *arg0, ...)
{
    va_list ap;
    COLLECT_VARARGS(arg0, ap, args);
    char *const *envp = va_arg(ap, char *const *);
    va_end(ap);
    return redirected_execve(path, args, envp, 0);
}

/*
 * posix_spawn reports a failed exec only through the child's exit status, so the decision is
 * made up front: dynamic images under the app's own directories always start through the linker.
 */
static int redirected_spawn(spawn_fn real, pid_t *pid, const char *path,
                            const posix_spawn_file_actions_t *actions, const posix_spawnattr_t *attr,
                            char *const argv[], char *const envp[], int depth)
{
    if (!envp) envp = environ;
    if (on_system_partition(path) || depth > MAX_SCRIPT_DEPTH)
        return real(pid, path, actions, attr, argv, envp);

    struct image_info info;
    inspect_image(path, &info);
    if (info.kind == IMAGE_SCRIPT) {
        size_t argc = count_args(argv);
        char *args[argc + 4];
        size_t j = 0;
        args[j++] = info.interp;
        if (info.interp_arg[0]) args[j++] = info.interp_arg;
        args[j++] = (char *)path;
        for (size_t i = 1; i < argc; i++) args[j++] = argv[i];
        args[j] = NULL;
        return redirected_spawn(real, pid, info.interp, actions, attr, args, envp, depth + 1);
    }
    if (info.kind != IMAGE_ELF_DYNAMIC) return real(pid, path, actions, attr, argv, envp);

    const char *linker = info.elf32 ? "/system/bin/linker" : "/system/bin/linker64";
    size_t argc = count_args(argv);
    size_t envc = count_args(envp);
    char *args[argc + 3];
    char *env[envc + 2];
    char entry[PATH_MAX + sizeof(SELF_EXE_ENV) + 2];
    args[0] = (char *)linker;
    args[1] = (char *)path;
    for (size_t i = 1; i < argc; i++) args[i + 1] = argv[i];
    args[argc < 1 ? 2 : argc + 1] = NULL;
    build_env(envp, path, env, entry, sizeof(entry));

    __android_log_print(ANDROID_LOG_INFO, TAG, "spawn %s via %s", path, linker);
    return real(pid, linker, actions, attr, args, env);
}

int posix_spawn(pid_t *pid, const char *path, const posix_spawn_file_actions_t *actions,
                const posix_spawnattr_t *attr, char *const argv[], char *const envp[])
{
    REAL(spawn_fn, "posix_spawn");
    if (!real_fn) return ENOSYS;
    return redirected_spawn(real_fn, pid, path, actions, attr, argv, envp, 0);
}

int posix_spawnp(pid_t *pid, const char *file, const posix_spawn_file_actions_t *actions,
                 const posix_spawnattr_t *attr, char *const argv[], char *const envp[])
{
    REAL(spawn_fn, "posix_spawn");
    if (!real_fn) return ENOSYS;
    if (!file || !*file) return ENOENT;
    if (strchr(file, '/')) return redirected_spawn(real_fn, pid, file, actions, attr, argv, envp, 0);

    const char *search = getenv("PATH");
    if (!search || !*search) search = DEFAULT_PATH;
    size_t file_len = strlen(file);
    const char *p = search;
    while (true) {
        const char *colon = strchr(p, ':');
        size_t dir_len = colon ? (size_t)(colon - p) : strlen(p);
        char candidate[PATH_MAX];
        if (dir_len + file_len + 2 <= sizeof(candidate)) {
            memcpy(candidate, p, dir_len);
            candidate[dir_len] = '/';
            memcpy(candidate + dir_len + 1, file, file_len + 1);
            if (dir_len && !access(candidate, X_OK))
                return redirected_spawn(real_fn, pid, candidate, actions, attr, argv, envp, 0);
        }
        if (!colon) break;
        p = colon + 1;
    }
    return ENOENT;
}

/* /proc/self/exe names the linker in redirected processes; report the program instead. */
static const char *self_exe_override(void)
{
    const char *exe = getenv(SELF_EXE_ENV);
    if (!exe || !*exe) return NULL;
    char actual[PATH_MAX];
    /* Raw syscall: bionic's readlink() calls readlinkat(), which lands back in this file. */
    ssize_t n = syscall(__NR_readlinkat, AT_FDCWD, "/proc/self/exe", actual, sizeof(actual) - 1);
    if (n <= 0) return NULL;
    actual[n] = 0;
    const char *base = strrchr(actual, '/');
    base = base ? base + 1 : actual;
    return (!strcmp(base, "linker64") || !strcmp(base, "linker")) ? exe : NULL;
}

static bool is_self_exe(const char *path)
{
    return path && (!strcmp(path, "/proc/self/exe") || !strcmp(path, "/proc/thread-self/exe"));
}

static ssize_t copy_link(const char *value, char *buf, size_t size)
{
    size_t len = strlen(value);
    if (len > size) len = size;
    memcpy(buf, value, len);
    return (ssize_t)len;
}

ssize_t readlink(const char *path, char *buf, size_t size)
{
    if (is_self_exe(path)) {
        const char *exe = self_exe_override();
        if (exe) return copy_link(exe, buf, size);
    }
    REAL(readlink_fn, "readlink");
    return real_fn ? real_fn(path, buf, size) : (errno = ENOSYS, -1);
}

ssize_t readlinkat(int dirfd, const char *path, char *buf, size_t size)
{
    if (is_self_exe(path)) {
        const char *exe = self_exe_override();
        if (exe) return copy_link(exe, buf, size);
    }
    REAL(readlinkat_fn, "readlinkat");
    return real_fn ? real_fn(dirfd, path, buf, size) : (errno = ENOSYS, -1);
}

char *realpath(const char *path, char *resolved)
{
    REAL(realpath_fn, "realpath");
    if (!real_fn) { errno = ENOSYS; return NULL; }
    if (is_self_exe(path)) {
        const char *exe = self_exe_override();
        if (exe) return real_fn(exe, resolved);
    }
    return real_fn(path, resolved);
}

static bool maps_line_is_shared(const char *line, uintptr_t start, uintptr_t end)
{
    char *p;
    uintptr_t lo = strtoull(line, &p, 16);
    if (*p != '-') return false;
    uintptr_t hi = strtoull(p + 1, &p, 16);
    while (*p == ' ') p++;
    return lo < end && hi > start && strlen(p) >= 4 && p[3] != 'p';
}

/* True when every mapping overlapping [start, end) is private; shared ones must keep their backing. */
static bool range_is_private(uintptr_t start, uintptr_t end)
{
    int fd = open("/proc/self/maps", O_RDONLY | O_CLOEXEC);
    if (fd < 0) return false;
    char buf[8192];
    size_t have = 0;
    bool shared = false;
    for (;;) {
        ssize_t n = read(fd, buf + have, sizeof(buf) - 1 - have);
        if (n > 0) have += (size_t)n;
        buf[have] = 0;
        char *line = buf;
        char *nl;
        while ((nl = memchr(line, '\n', buf + have - line))) {
            *nl = 0;
            shared |= maps_line_is_shared(line, start, end);
            line = nl + 1;
        }
        size_t rest = (size_t)(buf + have - line);
        if (n <= 0) {
            if (rest) shared |= maps_line_is_shared(line, start, end);
            break;
        }
        if (rest == sizeof(buf) - 1) rest = 0;
        memmove(buf, line, rest);
        have = rest;
    }
    close(fd);
    return !shared;
}

typedef int (*mprotect_fn)(void *, size_t, int);

int mprotect(void *addr, size_t len, int prot)
{
    REAL(mprotect_fn, "mprotect");
    if (!real_fn) { errno = ENOSYS; return -1; }
    int ret = real_fn(addr, len, prot);
    if (ret == 0 || errno != EACCES || !(prot & PROT_EXEC) || !len) return ret;

    uintptr_t page = (uintptr_t)sysconf(_SC_PAGESIZE);
    uintptr_t start = (uintptr_t)addr & ~(page - 1);
    uintptr_t end = ((uintptr_t)addr + len + page - 1) & ~(page - 1);
    size_t span = end - start;
    if (!range_is_private(start, end) || real_fn((void *)start, span, PROT_READ) != 0) {
        errno = EACCES;
        return -1;
    }

    void *copy = mmap(NULL, span, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (copy == MAP_FAILED) { errno = EACCES; return -1; }
    memcpy(copy, (void *)start, span);
    void *fresh = mmap((void *)start, span, PROT_READ | PROT_WRITE,
                       MAP_PRIVATE | MAP_ANONYMOUS | MAP_FIXED, -1, 0);
    if (fresh == MAP_FAILED) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "remap %p+%zx failed: %d", (void *)start, span, errno);
        munmap(copy, span);
        errno = EACCES;
        return -1;
    }
    memcpy(fresh, copy, span);
    munmap(copy, span);
    return real_fn((void *)start, span, prot);
}
