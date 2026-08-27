/*
 * Minimal evshim for x86_64 host (Java WinHandler JNI + shared-memory setup).
 * Full evshim (SDL virtual joysticks for Wine) needs third_party/SDL2 headers;
 * build that from app/src/main/cpp/evshim/ once headers are vendored.
 */
#define _GNU_SOURCE
#include <android/log.h>
#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <limits.h>
#include <linux/futex.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>

#define LOG_TAG "evshim"
#define ALOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define ALOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#define SHM_DATA_SIZE 64
#define MAX_GAMEPADS 4

struct gamepad_state {
    int16_t lx, ly, rx, ry, lt, rt;
    uint8_t btn[15];
    uint8_t hat;
    uint16_t low_freq_rumble;
    uint16_t high_freq_rumble;
};

struct gamepad_io {
    atomic_uint seq;
    struct gamepad_state state;
    atomic_uint rumble_seq;
    atomic_uint connected;
};

_Static_assert(sizeof(struct gamepad_io) <= SHM_DATA_SIZE, "gamepad_io exceeds SHM_DATA_SIZE");

static struct gamepad_io *shm[MAX_GAMEPADS];
static size_t g_shm_map_size;
static int g_is_wine;

static void build_gamepad_dir(char *out, size_t size)
{
    const char *base = getenv("EVSHIM_BASE_PATH");
    if (!base || !*base) {
        base = "/data/data/app.gamenative/files";
    }
    snprintf(out, size, "%s/gamepad_shm", base);
}

static int mkdir_gameshm(const char *path)
{
    struct stat st;
    if (stat(path, &st) == 0) {
        return S_ISDIR(st.st_mode) ? 0 : -1;
    }
    if (mkdir(path, 0777) < 0 && errno != EEXIST) {
        return -1;
    }
    return 0;
}

static void setup_shm(int players)
{
    g_shm_map_size = (size_t)sysconf(_SC_PAGESIZE);

    char gamepad_dir[PATH_MAX];
    build_gamepad_dir(gamepad_dir, sizeof(gamepad_dir));
    if (mkdir_gameshm(gamepad_dir) < 0) {
        ALOGE("failed to create/check dir '%s': %s", gamepad_dir, strerror(errno));
        return;
    }

    for (int i = 0; i < players; i++) {
        char path[PATH_MAX];
        if (i == 0) {
            snprintf(path, sizeof(path), "%s/gamepad.mem", gamepad_dir);
        } else {
            snprintf(path, sizeof(path), "%s/gamepad%d.mem", gamepad_dir, i);
        }

        int fd = open(path, O_RDWR | O_CREAT, 0666);
        if (fd < 0) {
            ALOGE("P%d open '%s' failed: %s", i, path, strerror(errno));
            continue;
        }
        if (ftruncate(fd, SHM_DATA_SIZE) < 0) {
            ALOGE("P%d ftruncate failed: %s", i, strerror(errno));
            close(fd);
            continue;
        }

        shm[i] = mmap(NULL, g_shm_map_size, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
        close(fd);
        if (shm[i] == MAP_FAILED) {
            ALOGE("P%d mmap failed: %s", i, strerror(errno));
            shm[i] = NULL;
            continue;
        }

        if (!g_is_wine) {
            memset(shm[i], 0, sizeof(struct gamepad_io));
        }
        ALOGI("P%d shm ready addr=%p", i, (void *)shm[i]);
    }
}

__attribute__((constructor))
static void evshim_host_init(void)
{
    g_is_wine = getenv("EVSHIM_WINE") != NULL;

    int players = g_is_wine ? 1 : MAX_GAMEPADS;
    const char *ep = getenv("EVSHIM_MAX_PLAYERS");
    if (ep) {
        players = atoi(ep);
    }
    if (players > MAX_GAMEPADS) {
        players = MAX_GAMEPADS;
    }

    setup_shm(players);

    if (g_is_wine) {
        ALOGI("Wine preload: SDL virtual-joystick path not in host stub (controller may be limited)");
    } else {
        ALOGI("Java host evshim init (%d player(s))", players);
    }
}

JNIEXPORT void JNICALL
Java_com_winlator_winhandler_WinHandler_notifyStateChanged(JNIEnv *env, jclass cls, jint idx)
{
    (void)env;
    (void)cls;
    if (idx < 0 || idx >= MAX_GAMEPADS || !shm[idx]) {
        ALOGE("notifyStateChanged missing shm for slot=%d", idx);
        return;
    }
    atomic_thread_fence(memory_order_seq_cst);
    atomic_fetch_add_explicit(&shm[idx]->seq, 1u, memory_order_release);
    syscall(SYS_futex, &shm[idx]->seq, FUTEX_WAKE, INT_MAX, NULL, NULL, 0);
}

JNIEXPORT jint JNICALL
Java_com_winlator_winhandler_WinHandler_waitForRumble(JNIEnv *env, jclass cls, jint idx, jint last_seq)
{
    (void)env;
    (void)cls;
    if (idx < 0 || idx >= MAX_GAMEPADS || !shm[idx]) {
        return last_seq;
    }

    uint32_t current_seq = atomic_load_explicit(&shm[idx]->rumble_seq, memory_order_acquire);
    if (current_seq != (uint32_t)last_seq) {
        return (jint)current_seq;
    }

    syscall(SYS_futex, &shm[idx]->rumble_seq, FUTEX_WAIT, current_seq, NULL, NULL, 0);
    return (jint)atomic_load_explicit(&shm[idx]->rumble_seq, memory_order_acquire);
}

JNIEXPORT void JNICALL
Java_com_winlator_winhandler_WinHandler_rumbleTeardown(JNIEnv *env, jclass cls, jint idx)
{
    (void)env;
    (void)cls;
    if (idx < 0 || idx >= MAX_GAMEPADS || !shm[idx]) {
        return;
    }
    atomic_fetch_add_explicit(&shm[idx]->rumble_seq, 1u, memory_order_release);
    syscall(SYS_futex, &shm[idx]->rumble_seq, FUTEX_WAKE, INT_MAX, NULL, NULL, 0);
}
