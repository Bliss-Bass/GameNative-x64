/*
 * SysV shared memory for guest processes, backed by the app's SM0 broker.
 *
 * Android has no usable SysV IPC, so a guest that calls shmget gets nothing it can share with the
 * in-app X server. Preloading this library redirects the four calls MIT-SHM needs to the broker in
 * SysVSHMRequestHandler, which hands back an ashmem fd and an id the X server already knows. That
 * shared id is the whole point: without it the server is asked to attach an id it never issued, and
 * the mismatch only surfaces later as BadSHMSegment in the middle of a frame.
 *
 * Protocol on the ANDROID_SYSVSHM_SERVER socket, little-endian throughout:
 *   0 + u32 size   -> i32 shmid
 *   1 + i32 shmid  -> one byte, with the fd as SCM_RIGHTS ancillary data
 *   2 + i32 shmid  -> nothing
 */
#include <android/log.h>
#include <errno.h>
#include <pthread.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/shm.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>

#define LOG_TAG "sysvshm"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) \
    do { if (debug_enabled()) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__); } while (0)

#define REQUEST_SHMGET 0
#define REQUEST_GET_FD 1
#define REQUEST_DELETE 2

#define MAX_SEGMENTS 64

typedef struct {
    int shmid;
    size_t size;
    void *addr;
    /*
     * Mesa marks the segment for deletion right after attaching, long before the X server has been
     * asked to attach to it. Honouring that immediately would close the broker's fd underneath the
     * server, so deletion waits for the last detach -- which is what SysV semantics promise anyway.
     */
    bool rmid_requested;
} segment;

static pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;
static segment segments[MAX_SEGMENTS];
static int segment_count = 0;
static int broker_fd = -1;

static bool debug_enabled(void) {
    static int enabled = -1;
    if (enabled < 0) {
        const char *value = getenv("ANDROID_SYSVSHM_DEBUG");
        enabled = (value != NULL && value[0] != '\0' && value[0] != '0') ? 1 : 0;
    }
    return enabled == 1;
}

/* Callers hold `lock`. */
static segment *find_by_id(int shmid) {
    for (int i = 0; i < segment_count; i++) {
        if (segments[i].shmid == shmid) return &segments[i];
    }
    return NULL;
}

static segment *find_by_addr(const void *addr) {
    for (int i = 0; i < segment_count; i++) {
        if (segments[i].addr == addr) return &segments[i];
    }
    return NULL;
}

static void forget(segment *entry) {
    int index = (int)(entry - segments);
    segments[index] = segments[--segment_count];
}

static bool write_all(int fd, const void *data, size_t length) {
    const uint8_t *bytes = data;
    size_t written = 0;
    while (written < length) {
        ssize_t count = write(fd, bytes + written, length - written);
        if (count > 0) {
            written += (size_t)count;
        } else if (count < 0 && errno == EINTR) {
            continue;
        } else {
            return false;
        }
    }
    return true;
}

static bool read_all(int fd, void *data, size_t length) {
    uint8_t *bytes = data;
    size_t got = 0;
    while (got < length) {
        ssize_t count = read(fd, bytes + got, length - got);
        if (count > 0) {
            got += (size_t)count;
        } else if (count < 0 && errno == EINTR) {
            continue;
        } else {
            return false;
        }
    }
    return true;
}

/* Callers hold `lock`. */
static int broker(void) {
    if (broker_fd >= 0) return broker_fd;

    const char *path = getenv("ANDROID_SYSVSHM_SERVER");
    if (path == NULL || path[0] == '\0') {
        LOGE("ANDROID_SYSVSHM_SERVER is unset; shared memory is unavailable");
        return -1;
    }

    struct sockaddr_un address;
    memset(&address, 0, sizeof(address));
    address.sun_family = AF_UNIX;
    if (strlen(path) >= sizeof(address.sun_path)) {
        LOGE("socket path too long: %s", path);
        return -1;
    }
    strcpy(address.sun_path, path);

    int fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (fd < 0) {
        LOGE("socket() failed: %s", strerror(errno));
        return -1;
    }
    if (connect(fd, (struct sockaddr *)&address, sizeof(address)) < 0) {
        LOGE("connect(%s) failed: %s", path, strerror(errno));
        close(fd);
        return -1;
    }

    broker_fd = fd;
    LOGD("connected to %s", path);
    return broker_fd;
}

/* Callers hold `lock`. The connection is dropped on any I/O failure so the next call redials. */
static void broker_failed(const char *what) {
    LOGE("%s failed on the broker socket", what);
    if (broker_fd >= 0) {
        close(broker_fd);
        broker_fd = -1;
    }
}

static bool request(uint8_t code, uint32_t argument) {
    int fd = broker();
    if (fd < 0) return false;

    uint8_t message[5] = {
        code,
        (uint8_t)(argument),
        (uint8_t)(argument >> 8),
        (uint8_t)(argument >> 16),
        (uint8_t)(argument >> 24),
    };
    if (!write_all(fd, message, sizeof(message))) {
        broker_failed("write");
        return false;
    }
    return true;
}

/* Callers hold `lock`. Returns the fd for a segment, or -1. */
static int request_fd(int shmid) {
    if (!request(REQUEST_GET_FD, (uint32_t)shmid)) return -1;

    uint8_t payload = 0;
    struct iovec iov = { .iov_base = &payload, .iov_len = sizeof(payload) };
    union {
        struct cmsghdr align;
        char bytes[CMSG_SPACE(sizeof(int))];
    } control;
    memset(&control, 0, sizeof(control));

    struct msghdr message;
    memset(&message, 0, sizeof(message));
    message.msg_iov = &iov;
    message.msg_iovlen = 1;
    message.msg_control = control.bytes;
    message.msg_controllen = sizeof(control.bytes);

    ssize_t count;
    do {
        count = recvmsg(broker_fd, &message, 0);
    } while (count < 0 && errno == EINTR);

    if (count <= 0) {
        broker_failed("recvmsg");
        return -1;
    }

    struct cmsghdr *header = CMSG_FIRSTHDR(&message);
    if (header == NULL || header->cmsg_level != SOL_SOCKET || header->cmsg_type != SCM_RIGHTS ||
        header->cmsg_len != CMSG_LEN(sizeof(int))) {
        LOGE("no fd came back for shmid %d", shmid);
        return -1;
    }

    int fd;
    memcpy(&fd, CMSG_DATA(header), sizeof(fd));
    return fd;
}

int shmget(key_t key, size_t size, int flags) {
    (void)key;
    (void)flags;

    LOGD("-> shmget(size=%zu)", size);

    if (size == 0 || size > UINT32_MAX) {
        errno = EINVAL;
        return -1;
    }

    pthread_mutex_lock(&lock);

    if (segment_count >= MAX_SEGMENTS) {
        pthread_mutex_unlock(&lock);
        LOGE("no room left for another segment (%d in use)", segment_count);
        errno = ENOSPC;
        return -1;
    }

    int shmid = -1;
    if (request(REQUEST_SHMGET, (uint32_t)size)) {
        uint8_t reply[4];
        if (read_all(broker_fd, reply, sizeof(reply))) {
            shmid = (int)((uint32_t)reply[0] | ((uint32_t)reply[1] << 8) |
                          ((uint32_t)reply[2] << 16) | ((uint32_t)reply[3] << 24));
        } else {
            broker_failed("read");
        }
    }

    if (shmid <= 0) {
        pthread_mutex_unlock(&lock);
        errno = ENOMEM;
        return -1;
    }

    segments[segment_count++] = (segment){ .shmid = shmid, .size = size };
    pthread_mutex_unlock(&lock);

    LOGD("shmget(%zu) -> %d", size, shmid);
    return shmid;
}

void *shmat(int shmid, const void *addr, int flags) {
    (void)addr;

    LOGD("-> shmat(%d, flags=%d)", shmid, flags);

    pthread_mutex_lock(&lock);

    segment *entry = find_by_id(shmid);
    if (entry == NULL) {
        pthread_mutex_unlock(&lock);
        LOGE("shmat(%d) for a segment we did not create", shmid);
        errno = EINVAL;
        return (void *)-1;
    }
    if (entry->addr != NULL) {
        void *existing = entry->addr;
        pthread_mutex_unlock(&lock);
        return existing;
    }

    int fd = request_fd(shmid);
    if (fd < 0) {
        pthread_mutex_unlock(&lock);
        errno = EINVAL;
        return (void *)-1;
    }

    int protection = (flags & SHM_RDONLY) ? PROT_READ : (PROT_READ | PROT_WRITE);
    void *mapped = mmap(NULL, entry->size, protection, MAP_SHARED, fd, 0);
    /* The mapping keeps the memory alive, so the fd has done its job either way. */
    close(fd);

    if (mapped == MAP_FAILED) {
        pthread_mutex_unlock(&lock);
        LOGE("mmap of shmid %d (%zu bytes) failed: %s", shmid, entry->size, strerror(errno));
        errno = ENOMEM;
        return (void *)-1;
    }

    entry->addr = mapped;
    pthread_mutex_unlock(&lock);

    LOGD("shmat(%d) -> %p (%zu bytes)", shmid, mapped, entry->size);
    return mapped;
}

int shmdt(const void *addr) {
    LOGD("-> shmdt(%p)", addr);

    pthread_mutex_lock(&lock);

    segment *entry = find_by_addr(addr);
    if (entry == NULL) {
        pthread_mutex_unlock(&lock);
        LOGE("shmdt(%p) for an address we did not map", addr);
        errno = EINVAL;
        return -1;
    }

    munmap(entry->addr, entry->size);
    entry->addr = NULL;

    int shmid = entry->shmid;
    if (entry->rmid_requested) {
        request(REQUEST_DELETE, (uint32_t)shmid);
        forget(entry);
    }

    pthread_mutex_unlock(&lock);

    LOGD("shmdt(%p) for shmid %d", addr, shmid);
    return 0;
}

/*
 * Mesa's ICDs do not call shmget at all: the Termux patches they were built with rename the calls to
 * these prefixed entry points, which is why interposing the standard names alone changed nothing. The
 * names have to exist here too, or the ICD fails to load and the guest gets no Vulkan device.
 */
int libandroid_shmget(key_t key, size_t size, int flags) {
    return shmget(key, size, flags);
}

void *libandroid_shmat(int shmid, const void *addr, int flags) {
    return shmat(shmid, addr, flags);
}

int libandroid_shmdt(const void *addr) {
    return shmdt(addr);
}

int shmctl(int shmid, int cmd, struct shmid_ds *buf) {
    LOGD("-> shmctl(%d, cmd=%d)", shmid, cmd);

    pthread_mutex_lock(&lock);

    segment *entry = find_by_id(shmid);
    if (entry == NULL) {
        pthread_mutex_unlock(&lock);
        LOGE("shmctl(%d, cmd=%d) for a segment we do not know", shmid, cmd);
        errno = EINVAL;
        return -1;
    }

    int result = 0;
    switch (cmd) {
        case IPC_RMID:
            if (entry->addr == NULL) {
                request(REQUEST_DELETE, (uint32_t)shmid);
                forget(entry);
            } else {
                entry->rmid_requested = true;
            }
            break;
        case IPC_STAT:
            if (buf == NULL) {
                errno = EFAULT;
                result = -1;
            } else {
                memset(buf, 0, sizeof(*buf));
                buf->shm_segsz = entry->size;
                buf->shm_nattch = entry->addr != NULL ? 1 : 0;
            }
            break;
        default:
            errno = EINVAL;
            result = -1;
            break;
    }

    pthread_mutex_unlock(&lock);

    LOGD("shmctl(%d, cmd=%d) -> %d", shmid, cmd, result);
    return result;
}

int libandroid_shmctl(int shmid, int cmd, struct shmid_ds *buf) {
    return shmctl(shmid, cmd, buf);
}
