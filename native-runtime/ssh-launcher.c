/* MIT License. Copyright (c) 2026 vstab contributors.
 * Native SSH entry points apply the app's HOME and execute read-only OpenSSH.
 */
#include <errno.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

int main(int argc, char **argv) {
    (void)argc;
    char executable[PATH_MAX], engine[PATH_MAX], preload[PATH_MAX * 2];
    ssize_t size = readlink("/proc/self/exe", executable, sizeof(executable) - 1);
    if (size < 0 || (size_t)size >= sizeof(executable) - 1) return 126;
    executable[size] = '\0';
    char *slash = strrchr(executable, '/');
    if (!slash) return 126;
    *slash = '\0';
    const char *name = strrchr(argv[0], '/');
    name = name ? name + 1 : argv[0];
    const char *tool = strstr(name, "keygen") ? "keygen" :
                       strstr(name, "agent") ? "agent" :
                       strstr(name, "add") ? "add" : "client";
    if (snprintf(engine, sizeof(engine), "%s/libopenssh-%s.so", executable, tool) >= (int)sizeof(engine)) return 126;
    const char *existing = getenv("LD_PRELOAD");
    if (snprintf(preload, sizeof(preload), "%s/libssh-home.so%s%s", executable,
                 existing && *existing ? ":" : "", existing ? existing : "") >= (int)sizeof(preload)) return 126;
    if (setenv("LD_PRELOAD", preload, 1) != 0) return 126;
    execv(engine, argv);
    fprintf(stderr, "vstab SSH launcher: %s\n", strerror(errno));
    return 126;
}
