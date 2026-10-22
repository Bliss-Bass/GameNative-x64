// vt_exec: run a program as the session leader of a Linux virtual console, or report which
// console is active.
//
//   libvtexec.so /dev/ttyN program [args...]   start program on that console
//   libvtexec.so --watch /dev/ttyN             print the active VT number on every change
//
// GameNative starts its Linux shells for Ctrl+Alt+F4-F6 through this. Java cannot make a child a
// session leader or give it a controlling terminal, and without one the shell has no job control
// and Ctrl+C reaches nothing. The watch mode exists because apps may not read sysfs, so the active
// VT comes from VT_GETSTATE on a console the app is allowed to open. Shipped as a .so only because
// Android lets an app exec files from its native library directory and nowhere else.
#include <errno.h>
#include <fcntl.h>
#include <linux/vt.h>
#include <signal.h>
#include <stdio.h>
#include <string.h>
#include <sys/ioctl.h>
#include <termios.h>
#include <unistd.h>

static int watch(const char* tty) {
    int fd = open(tty, O_RDONLY | O_NOCTTY | O_CLOEXEC);
    if (fd < 0) {
        fprintf(stderr, "vt_exec: open %s: %s\n", tty, strerror(errno));
        return 1;
    }
    // The reader going away is how this is told to stop.
    signal(SIGPIPE, SIG_DFL);
    int last = -1;
    for (;;) {
        struct vt_stat vs;
        if (ioctl(fd, VT_GETSTATE, &vs) < 0) {
            fprintf(stderr, "vt_exec: VT_GETSTATE: %s\n", strerror(errno));
            return 1;
        }
        if (vs.v_active != last) {
            last = vs.v_active;
            if (printf("%d\n", last) < 0 || fflush(stdout) != 0) return 0;
        }
        usleep(250000);
    }
}

int main(int argc, char** argv) {
    if (argc == 3 && strcmp(argv[1], "--watch") == 0) return watch(argv[2]);
    if (argc < 3) {
        fprintf(stderr, "usage: %s /dev/ttyN program [args...] | --watch /dev/ttyN\n", argv[0]);
        return 2;
    }
    if (setsid() < 0) {
        fprintf(stderr, "vt_exec: setsid: %s\n", strerror(errno));
        return 1;
    }
    // Opened after setsid, so the console can become this session's controlling terminal.
    int fd = open(argv[1], O_RDWR | O_CLOEXEC);
    if (fd < 0) {
        fprintf(stderr, "vt_exec: open %s: %s\n", argv[1], strerror(errno));
        return 1;
    }
    if (ioctl(fd, TIOCSCTTY, 0) < 0) {
        fprintf(stderr, "vt_exec: TIOCSCTTY %s: %s\n", argv[1], strerror(errno));
        return 1;
    }
    for (int stdio = 0; stdio < 3; stdio++) {
        if (dup2(fd, stdio) < 0) return 1;
    }
    // A previous session may have left the console without echo or line editing.
    struct termios tio;
    if (tcgetattr(0, &tio) == 0) {
        tio.c_lflag |= ECHO | ICANON | ISIG | ECHOE | ECHOK;
        tio.c_iflag |= ICRNL;
        tio.c_oflag |= OPOST | ONLCR;
        tcsetattr(0, TCSANOW, &tio);
    }
    execv(argv[2], argv + 2);
    fprintf(stderr, "vt_exec: exec %s: %s\n", argv[2], strerror(errno));
    return 127;
}
