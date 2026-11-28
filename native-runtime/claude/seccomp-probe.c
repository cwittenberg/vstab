#define _GNU_SOURCE
#if !defined(__x86_64__)
#error This host regression probe uses the x86_64 Linux syscall numbers.
#endif
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <stdio.h>
#include <string.h>
#include <sys/epoll.h>
#include <sys/prctl.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <time.h>
#include <unistd.h>

struct filter { unsigned short code; unsigned char jt, jf; unsigned int k; };
struct program { unsigned short len; struct filter *filter; };
static volatile int seen;
static void signal_handler(int signal) { seen = signal; }
static int check(int expected, const struct timespec *timeout) {
    struct epoll_event event;
    errno = 0;
    long result = syscall(441, -1, &event, 1, timeout, 0, 8);
    if (result != -1 || errno != expected) {
        fprintf(stderr, "epoll result=%ld errno=%d expected=%d\n", result, errno, expected);
        return 1;
    }
    return 0;
}

int main(void) {
    const unsigned int denied[] = {441, 140, 2, 4, 6, 7, 21, 22, 33, 34, 43, 82,
        83, 84, 85, 86, 87, 88, 89, 90, 92, 94, 213, 232, 253, 264, 282, 284};
    struct filter filter[2 * sizeof(denied) / sizeof(denied[0]) + 2];
    unsigned int n = 0;
    filter[n++] = (struct filter){0x20, 0, 0, 0};
    for (unsigned int i = 0; i < sizeof(denied) / sizeof(denied[0]); ++i) {
        filter[n++] = (struct filter){0x15, 0, 1, denied[i]};
        filter[n++] = (struct filter){0x06, 0, 0, 0x00030000};
    }
    filter[n++] = (struct filter){0x06, 0, 0, 0x7fff0000};
    struct program program = {n, filter};
    if (prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) || prctl(PR_SET_SECCOMP, 2, &program)) return 2;
    struct timespec invalid = {0, 1000000000};
    if (check(EBADF, 0) || check(EINVAL, &invalid)) return 3;
    errno = 0;
    if (syscall(140, 0, 0) != -1 || errno != ENOSYS) return 4;
    if (check(EBADF, 0)) return 5;
    struct sigaction desired = {0}, old = {0};
    desired.sa_handler = signal_handler;
    sigemptyset(&desired.sa_mask);
    if (sigaction(SIGUSR1, &desired, &old)) return 6;
    if (raise(SIGUSR1) || seen != SIGUSR1) return 7;
    if (sigaction(SIGUSR1, &old, 0)) return 8;
    desired.sa_handler = SIG_IGN;
    if (sigaction(SIGSYS, &desired, &old) || check(EBADF, 0)) return 9;
    if (raise(SIGSYS)) return 10;
    if (check(EBADF, 0)) return 11;
    char folder[256], original[300], renamed[300], soft[300], hard[300];
    snprintf(folder, sizeof(folder), "/tmp/vstab-claude-seccomp-%ld", (long)getpid());
    snprintf(original, sizeof(original), "%s/source", folder);
    snprintf(renamed, sizeof(renamed), "%s/renamed", folder);
    snprintf(soft, sizeof(soft), "%s/symlink", folder);
    snprintf(hard, sizeof(hard), "%s/hardlink", folder);
#define REQUIRE(call) do { if (!(call)) { perror(#call); return __LINE__; } } while (0)
    REQUIRE(syscall(SYS_mkdir, folder, 0700) == 0);
    int fd = syscall(SYS_open, original, O_CREAT | O_RDWR, 0644);
    REQUIRE(fd >= 0);
    REQUIRE(write(fd, "claude", 6) == 6);
    REQUIRE(close(fd) == 0);
    struct stat st;
    REQUIRE(syscall(SYS_stat, original, &st) == 0 && st.st_size == 6);
    REQUIRE(syscall(SYS_access, original, R_OK) == 0);
    REQUIRE(syscall(SYS_symlink, original, soft) == 0);
    REQUIRE(syscall(SYS_lstat, soft, &st) == 0 && S_ISLNK(st.st_mode));
    char link[300] = {0};
    REQUIRE(syscall(SYS_readlink, soft, link, sizeof(link)) == (long)strlen(original));
    REQUIRE(!strcmp(link, original));
    REQUIRE(syscall(SYS_link, original, hard) == 0);
    REQUIRE(syscall(SYS_stat, original, &st) == 0 && st.st_nlink == 2);
    REQUIRE(syscall(SYS_chmod, original, 0600) == 0);
    REQUIRE(syscall(SYS_stat, original, &st) == 0 && (st.st_mode & 0777) == 0600);
    REQUIRE(syscall(SYS_chown, original, getuid(), getgid()) == 0);
    REQUIRE(syscall(SYS_lchown, soft, getuid(), getgid()) == 0);
    REQUIRE(syscall(SYS_rename, original, renamed) == 0);
    errno = 0;
    REQUIRE(syscall(SYS_access, original, F_OK) == -1 && errno == ENOENT);
    REQUIRE(syscall(SYS_renameat, AT_FDCWD, renamed, AT_FDCWD, original) == 0);
    REQUIRE(syscall(SYS_unlink, original) == 0);
    REQUIRE(syscall(SYS_unlink, soft) == 0);
    REQUIRE(syscall(SYS_unlink, hard) == 0);
    REQUIRE(syscall(SYS_rmdir, folder) == 0);
    int pipefd[2];
    REQUIRE(syscall(SYS_pipe, pipefd) == 0);
    REQUIRE(syscall(SYS_dup2, pipefd[0], pipefd[0]) == pipefd[0]);
    struct pollfd ready = {pipefd[0], POLLIN, 0};
    REQUIRE(syscall(SYS_poll, &ready, 1, 0) == 0);
    REQUIRE(write(pipefd[1], "x", 1) == 1);
    REQUIRE(syscall(SYS_poll, &ready, 1, 0) == 1 && ready.revents == POLLIN);
    REQUIRE(close(pipefd[0]) == 0 && close(pipefd[1]) == 0);
    int epoll = syscall(SYS_epoll_create, 1);
    REQUIRE(epoll >= 0);
    REQUIRE(close(epoll) == 0);
    puts("PASS: x86_64 musl adapter: real filesystem operations under legacy syscall traps, epoll fallback, invalid timeout, unknown syscall, handler retention, other signals and software SIGSYS");
    return 0;
}
