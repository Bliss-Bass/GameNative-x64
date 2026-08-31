/*
 * Debug shim: report what xcb hands a guest client, for the times the server's side of the wire
 * looks correct and the client still behaves as if it received something else.
 *
 * Wrapping works here because the traced program is an executable: a preload takes precedence for
 * its own calls, unlike the dlopen'd Vulkan ICDs whose DT_NEEDED wins over LD_PRELOAD.
 *
 * Build (host):
 *   $NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/x86_64-linux-android26-clang \
 *       -shared -fPIC -o libxcbtrace.so xcbtrace.c -llog
 *
 * Use: add it to LD_PRELOAD ahead of anything else.
 */
#include <android/log.h>
#include <dlfcn.h>
#include <stdint.h>
#include <stdio.h>

#define LOG(...) __android_log_print(ANDROID_LOG_DEBUG, "xcbtrace", __VA_ARGS__)

static void *real(const char *name) {
    static void *handle;
    if (handle == NULL) handle = RTLD_NEXT;
    return dlsym(handle, name);
}

__attribute__((constructor)) static void announce(void) {
    LOG("loaded");
}

/* xcb has several ways to hand over an event and Mesa's WSI thread does not use the same one the
 * program does, so all of them report or the quiet one is where the time goes. */
void *xcb_wait_for_event(void *c) {
    static void *(*fn)(void *);
    if (fn == NULL) fn = real("xcb_wait_for_event");
    LOG("wait_for_event");
    return fn(c);
}

void *xcb_poll_for_special_event(void *c, void *se) {
    static void *(*fn)(void *, void *);
    static unsigned long calls;
    if (fn == NULL) fn = real("xcb_poll_for_special_event");
    void *event = fn(c, se);
    if (++calls <= 8 || calls % 100000 == 0) LOG("poll_special #%lu -> %p", calls, event);
    return event;
}

void *xcb_wait_for_special_event(void *c, void *se) {
    static void *(*fn)(void *, void *);
    if (fn == NULL) fn = real("xcb_wait_for_special_event");
    LOG("wait_for_special_event");
    return fn(c, se);
}

void *xcb_poll_for_event(void *c) {
    static void *(*fn)(void *);
    static unsigned long calls, nonnull;
    if (fn == NULL) fn = real("xcb_poll_for_event");

    void *event = fn(c);
    calls++;
    if (event != NULL) nonnull++;

    /* The interesting case is a client that never runs out of events, so report the first few and
     * then only every thousandth: a tight loop would otherwise bury the log. */
    if (calls <= 8) LOG("poll #%lu call -> %p", calls, event);
    if (nonnull <= 8 || nonnull % 1000 == 0) {
        const uint8_t *b = event;
        if (event != NULL) {
            LOG("poll #%lu -> type=%u detail=%u seq=%u len=%u (calls=%lu)",
                nonnull, b[0] & 0x7fu, b[1], (unsigned)(b[2] | (b[3] << 8)),
                (unsigned)(b[4] | (b[5] << 8) | (b[6] << 16) | ((unsigned)b[7] << 24)), calls);
        }
    }
    if (event == NULL && calls % 10000 == 0) LOG("poll -> NULL (calls=%lu)", calls);
    return event;
}

int xcb_connection_has_error(void *c) {
    static int (*fn)(void *);
    if (fn == NULL) fn = real("xcb_connection_has_error");
    int err = fn(c);
    if (err != 0) LOG("connection_has_error -> %d", err);
    return err;
}
