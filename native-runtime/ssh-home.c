/* MIT License. Copyright (c) 2026 vstab contributors.
 * OpenSSH derives ~ from passwd, while Android returns Termux's build prefix.
 * Only the APK's SSH launchers preload this shim; honor the same private HOME
 * used by Git, terminals and the editor instead of another app's directory.
 */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <pwd.h>
#include <stdlib.h>
#include <sys/types.h>
#include <unistd.h>

static struct passwd *with_home(struct passwd *value) {
    static _Thread_local struct passwd entry;
    const char *home = getenv("HOME");
    if (!value || value->pw_uid != getuid() || !home || home[0] != '/') return value;
    entry = *value;
    entry.pw_dir = (char *)home;
    const char *shell = getenv("SHELL");
    if (shell && shell[0] == '/') entry.pw_shell = (char *)shell;
    return &entry;
}

struct passwd *getpwuid(uid_t uid) {
    struct passwd *(*original)(uid_t) = dlsym(RTLD_NEXT, "getpwuid");
    return with_home(original ? original(uid) : NULL);
}

struct passwd *getpwnam(const char *name) {
    struct passwd *(*original)(const char *) = dlsym(RTLD_NEXT, "getpwnam");
    return with_home(original ? original(name) : NULL);
}

