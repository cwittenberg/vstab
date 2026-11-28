// Lets a program that calls epoll_pwait2 run on an Android that does not allow
// it, by answering the refusal instead of dying on it.
//
// The wall: an app may make only the system calls bionic exposes in
// SYSCALLS.TXT, and epoll_pwait2 (441) appears there in android15. On the
// android13 and android14 branches it is absent, so a program that calls it is
// refused -- and the refusal is not an error it can recover from. That is what
// kills the Claude Code CLI, whose runtime reaches for it as soon as its event
// loop starts. The CLI here is the musl build its extension brings (patch 0009
// asks the marketplace for it, because the glibc build is refused even earlier,
// on set_robust_list and rseq). It sits in the app's storage, where Android
// refuses execve, so claude-launch.c starts musl's loader on it and hands the
// loader this shim as --preload=: only the CLI's own process carries it, none of
// its children.
//
// What makes a shim possible at all is that Android refuses with
// SECCOMP_RET_TRAP rather than a kill: the syscall does not run, SIGSYS is
// delivered to the calling thread, and a handler that returns resumes the thread
// after the trapping instruction with whatever it left in x0. Measured on an API
// 33 emulator inside this app before any of this was written: the signal is
// catchable and the emulated call answered 0. So the handler below emulates
// epoll_pwait2 with epoll_pwait, which every Android does expose, and hands the
// result back as the syscall's own return value.
//
// Freestanding on purpose. It is preloaded into a musl process by musl's own
// loader, so it must not drag bionic's libc in behind it: there are no library
// dependencies here, and it is linked with -nostdlib. System calls use `svc #0`;
// the errno accessor resolves to the musl already loaded by the process. The
// kernel ABI structures it uses (siginfo, ucontext, epoll_event, timespec) are the same
// for either libc, which is why the NDK headers can describe them.
//
// The one behaviour it does not preserve is resolution. epoll_pwait2 takes a
// timespec and epoll_pwait takes whole milliseconds, so a timeout is rounded up
// to the next millisecond rather than truncated: a caller polling with a
// sub-millisecond timeout gets a slightly longer wait, where truncation to zero
// would turn its wait into a spin.
#include <errno.h>
#include <asm/unistd.h>
#include <linux/signal.h>
#include <signal.h>
#include <stddef.h>
#include <sys/epoll.h>
#include <time.h>
#include <ucontext.h>

#if defined(__aarch64__)
#define SYS_epoll_pwait   22
#define SYS_rt_sigaction  134
#elif defined(__x86_64__)
#define SYS_epoll_pwait   281
#define SYS_rt_sigaction  13
#else
#error Unsupported Claude runtime architecture
#endif

#ifndef __NR_epoll_pwait2
#define __NR_epoll_pwait2 441
#endif

#ifndef SYS_SECCOMP
#define SYS_SECCOMP 1
#endif

extern int *__errno_location(void);

static inline long sys6(long nr, long a, long b, long c, long d, long e, long f) {
#if defined(__aarch64__)
    register long x8 __asm__("x8") = nr;
    register long x0 __asm__("x0") = a;
    register long x1 __asm__("x1") = b;
    register long x2 __asm__("x2") = c;
    register long x3 __asm__("x3") = d;
    register long x4 __asm__("x4") = e;
    register long x5 __asm__("x5") = f;
    __asm__ volatile("svc #0"
                     : "+r"(x0)
                     : "r"(x1), "r"(x2), "r"(x3), "r"(x4), "r"(x5), "r"(x8)
                     : "memory", "cc");
    return x0;
#else
    register long r10 __asm__("r10") = d;
    register long r8 __asm__("r8") = e;
    register long r9 __asm__("r9") = f;
    long result;
    __asm__ volatile("syscall" : "=a"(result)
        : "a"(nr), "D"(a), "S"(b), "d"(c), "r"(r10), "r"(r8), "r"(r9)
        : "rcx", "r11", "memory", "cc");
    return result;
#endif
}

#if defined(__x86_64__)
// x86-64 requires an explicit signal-return trampoline.
__asm__(".text\n.global vstab_claude_restore\n.hidden vstab_claude_restore\n"
        "vstab_claude_restore:\nmov $15, %rax\nsyscall\n");
extern void vstab_claude_restore(void);
#define SA_RESTORER 0x04000000
#endif

/**
 * What the kernel wants from rt_sigaction, which is not what libc's sigaction
 * takes: the mask is the kernel's own 64-bit set and its size is passed
 * separately. Declared here because a freestanding object has no libc to ask.
 */
struct kernel_sigaction {
    void (*handler)(int, siginfo_t *, void *);
    unsigned long flags;
    void (*restorer)(void);
    unsigned long mask;
};

#if defined(__x86_64__)
// Bionic uses the *at family for path operations on x86-64, so Android's
// syscall filter does not expose the older entry points used by musl and Bun.
// These preserve the same kernel operation, paths, flags and stat structure;
// they neither change filesystem permissions nor execute the refused call.
static long legacy_path_call(int nr, const long *a, int *handled) {
    const long cwd = -100; // AT_FDCWD
    *handled = 1;
    switch (nr) {
        case __NR_open:
            return sys6(__NR_openat, cwd, a[0], a[1], a[2], 0, 0);
        case __NR_creat:
            return sys6(__NR_openat, cwd, a[0], 64 | 1 | 512, a[1], 0, 0);
        case __NR_stat:
            return sys6(__NR_newfstatat, cwd, a[0], a[1], 0, 0, 0);
        case __NR_lstat:
            return sys6(__NR_newfstatat, cwd, a[0], a[1], 256, 0, 0);
        case __NR_access:
            return sys6(__NR_faccessat, cwd, a[0], a[1], 0, 0, 0);
        case __NR_mkdir:
            return sys6(__NR_mkdirat, cwd, a[0], a[1], 0, 0, 0);
        case __NR_rmdir:
            return sys6(__NR_unlinkat, cwd, a[0], 512, 0, 0, 0);
        case __NR_unlink:
            return sys6(__NR_unlinkat, cwd, a[0], 0, 0, 0, 0);
        case __NR_rename:
            return sys6(__NR_renameat2, cwd, a[0], cwd, a[1], 0, 0);
        case __NR_renameat:
            return sys6(__NR_renameat2, a[0], a[1], a[2], a[3], 0, 0);
        case __NR_link:
            return sys6(__NR_linkat, cwd, a[0], cwd, a[1], 0, 0);
        case __NR_symlink:
            return sys6(__NR_symlinkat, a[0], cwd, a[1], 0, 0, 0);
        case __NR_readlink:
            return sys6(__NR_readlinkat, cwd, a[0], a[1], a[2], 0, 0);
        case __NR_chmod:
            return sys6(__NR_fchmodat, cwd, a[0], a[1], 0, 0, 0);
        case __NR_chown:
            return sys6(__NR_fchownat, cwd, a[0], a[1], a[2], 0, 0);
        case __NR_lchown:
            return sys6(__NR_fchownat, cwd, a[0], a[1], a[2], 256, 0);
        case __NR_pipe:
            return sys6(__NR_pipe2, a[0], 0, 0, 0, 0, 0);
        case __NR_poll: {
            const int milliseconds = (int)a[2];
            struct timespec timeout = {milliseconds / 1000,
                                       (milliseconds % 1000) * 1000000L};
            return sys6(__NR_ppoll, a[0], a[1],
                        milliseconds < 0 ? 0 : (long)&timeout, 0, 8, 0);
        }
        case __NR_pause:
            return sys6(__NR_ppoll, 0, 0, 0, 0, 8, 0);
        case __NR_signalfd:
            return sys6(__NR_signalfd4, a[0], a[1], a[2], 0, 0, 0);
        case __NR_dup2:
            if (a[0] == a[1]) {
                long valid = sys6(__NR_fcntl, a[0], 1, 0, 0, 0, 0);
                return valid < 0 ? valid : a[0];
            }
            return sys6(__NR_dup3, a[0], a[1], 0, 0, 0, 0);
        case __NR_accept:
            return sys6(__NR_accept4, a[0], a[1], a[2], 0, 0, 0);
        case __NR_eventfd:
            return sys6(__NR_eventfd2, a[0], 0, 0, 0, 0, 0);
        case __NR_epoll_create:
            return a[0] <= 0 ? -EINVAL : sys6(__NR_epoll_create1, 0, 0, 0, 0, 0, 0);
        case __NR_epoll_wait:
            return sys6(__NR_epoll_pwait, a[0], a[1], a[2], a[3], 0, 8);
        case __NR_inotify_init:
            return sys6(__NR_inotify_init1, 0, 0, 0, 0, 0, 0);
        default:
            *handled = 0;
            return -ENOSYS;
    }
}
#endif

/**
 * Answers one refused epoll_pwait2 and lets the thread carry on.
 *
 * Anything else refused is answered ENOSYS, which is the one reply a runtime
 * probing for a syscall can fall back from, and the handler stays installed.
 *
 * What this replaces read better than it behaved: it put SIGSYS back to SIG_DFL
 * and returned, on the belief that the refusal would be raised again and end the
 * process the way the platform meant it to. It is not raised again. Read from
 * the kernel sources rather than measured here: for SECCOMP_RET_TRAP,
 * kernel/seccomp.c rolls the registers back, raises the signal and skips the
 * call, and arm64's syscall_rollback is `regs[0] = orig_x0`, so the instruction
 * is not retried and a bare return hands the caller its own first argument as
 * the syscall's result. The disposition change also disarmed this handler for
 * the rest of the process, so the next epoll_pwait2, the call this file exists
 * for, would kill the process instead of being answered.
 */
static void on_sigsys(int sig, siginfo_t *info, void *ctx) {
    (void)sig;
    ucontext_t *uc = (ucontext_t *)ctx;
#if defined(__aarch64__)
    unsigned long *regs = (unsigned long *)uc->uc_mcontext.regs;
#define RETURN_REGISTER regs[0]
#define ARGUMENT(n) regs[n]
#else
    greg_t *regs = uc->uc_mcontext.gregs;
#define RETURN_REGISTER regs[REG_RAX]
    const int arguments[] = {REG_RDI, REG_RSI, REG_RDX, REG_R10, REG_R8, REG_R9};
#define ARGUMENT(n) regs[arguments[n]]
#endif

    if (info->si_code != SYS_SECCOMP) {
        // Sent with kill, tgkill or sigqueue, not raised by a refusal. No call was
        // skipped, si_syscall is not set for it, and x0 is whatever the
        // interrupted code holds there, such as kill's own result. Nothing to
        // answer, so nothing is touched.
        return;
    }

    if (info->si_syscall != __NR_epoll_pwait2) {
        // Not ours, and the call did not run. Answer it rather than leaving the
        // caller with whatever the rollback left in x0, and leave the handler in
        // place so the one call this file is for is still answered afterwards.
#if defined(__x86_64__)
        const long args[] = {ARGUMENT(0), ARGUMENT(1), ARGUMENT(2),
                             ARGUMENT(3), ARGUMENT(4), ARGUMENT(5)};
        int handled;
        RETURN_REGISTER = (unsigned long)legacy_path_call(info->si_syscall, args, &handled);
#else
        RETURN_REGISTER = (unsigned long)-ENOSYS;
#endif
        return;
    }

    int fd = (int)ARGUMENT(0);
    struct epoll_event *events = (struct epoll_event *)ARGUMENT(1);
    int maxevents = (int)ARGUMENT(2);
    const struct timespec *ts = (const struct timespec *)ARGUMENT(3);
    const void *sigmask = (const void *)ARGUMENT(4);
    unsigned long setsize = (unsigned long)ARGUMENT(5);

    // A null timespec is "wait forever", which epoll_pwait spells -1. Rounded
    // up, for the reason the file header gives.
    int timeout_ms = -1;
    if (ts) {
        if (ts->tv_sec < 0 || ts->tv_nsec < 0 || ts->tv_nsec >= 1000000000L) {
            RETURN_REGISTER = (unsigned long)-EINVAL;
            return;
        }
        if (ts->tv_sec > 0x7fffffffL / 1000L) {
            timeout_ms = 0x7fffffff;
        } else {
            long ms = ts->tv_sec * 1000L + (ts->tv_nsec + 999999L) / 1000000L;
            timeout_ms = ms > 0x7fffffffL ? 0x7fffffff : (int)ms;
        }
    }

    long r = sys6(SYS_epoll_pwait, fd, (long)events, maxevents, timeout_ms,
                  (long)sigmask, (long)setsize);
    RETURN_REGISTER = (unsigned long)r;
}

/**
 * musl's own `struct sigaction`, which is not the kernel's: the mask comes
 * second and is 128 bytes wide, where the kernel wants flags second and an
 * 8-byte mask last. Declared here because a freestanding object has no headers
 * for the libc it is being loaded beside.
 */
struct musl_sigaction {
    void *handler;
    unsigned long mask[16];
    int flags;
    void (*restorer)(void);
};

/**
 * Keeps the handler above in place.
 *
 * The runtime installs a SIGSYS handler of its own once it is up, and that one
 * treats a refused syscall as fatal: measured, the emulation answers three calls
 * and then the fourth reaches the runtime's handler, which re-raises through
 * kill(). Interposed here rather than fought elsewhere, because this is the only
 * point where the change is visible to us. Every other signal is passed through
 * with the layout translated, so nothing else about the process changes.
 */
__attribute__((visibility("default")))
int shim_sigaction(int sig, const struct musl_sigaction *act, struct musl_sigaction *oact)
    __asm__("sigaction");
int shim_sigaction(int sig, const struct musl_sigaction *act, struct musl_sigaction *oact) {
    // musl reserves 32-34 for its thread implementation and exposes signals 1-64.
    if ((unsigned)sig - 32U < 3 || (unsigned)sig - 1U >= 64) {
        *__errno_location() = EINVAL;
        return -1;
    }
    if (sig == SIGSYS && act) {
        act = NULL; // Keep our handler while still returning the installed action.
    }
    // Both directions are translated. The caller's buffer is musl's shape and
    // the kernel's is not, so handing the kernel `oact` directly would have it
    // write sixteen bytes of its own layout into a hundred and fifty-two byte
    // structure the caller then reads as its own: the handler lands where the
    // mask belongs, and the caller crashes somewhere unrelated. That is a fault
    // this shim would have caused rather than prevented.
    struct kernel_sigaction k = { 0 };
    struct kernel_sigaction old = { 0 };
    if (act) {
        k.handler = (void (*)(int, siginfo_t *, void *))act->handler;
        k.flags = (unsigned long)(unsigned int)act->flags;
        k.restorer = act->restorer;
        k.mask = act->mask[0];
#if defined(__x86_64__)
        // musl normally supplies this in its own sigaction wrapper. We call
        // the kernel directly, so every installed user handler needs it too.
        k.flags |= SA_RESTORER;
        k.restorer = vstab_claude_restore;
#endif
    }
    long r = sys6(SYS_rt_sigaction, sig, act ? (long)&k : 0, oact ? (long)&old : 0, 8, 0, 0);
    if (r < 0) {
        *__errno_location() = (int)-r;
        return -1;
    }
    if (oact && r == 0) {
        for (unsigned i = 0; i < 16; i++) oact->mask[i] = 0;
        oact->handler = (void *)old.handler;
        oact->mask[0] = old.mask;
        oact->flags = (int)old.flags;
        oact->restorer = old.restorer;
    }
    return (int)r;
}

/**
 * Installs the handler before anything else in the process runs.
 *
 * SA_NODEFER so a refusal raised from inside the handler is not held back, and
 * SA_SIGINFO because only si_code says the signal is a refusal and only
 * si_syscall says which call was refused. No restorer: arm64 returns from a
 * signal through the vDSO.
 */
__attribute__((constructor)) static void install(void) {
    struct kernel_sigaction sa = { 0 };
    sa.handler = on_sigsys;
    sa.flags = SA_SIGINFO | SA_NODEFER;
#if defined(__x86_64__)
    sa.flags |= SA_RESTORER;
    sa.restorer = vstab_claude_restore;
#endif
    sa.mask = 0;
    sys6(SYS_rt_sigaction, SIGSYS, (long)&sa, 0, 8, 0, 0);
}
