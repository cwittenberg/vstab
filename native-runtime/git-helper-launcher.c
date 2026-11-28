/* MIT License. Copyright (c) 2026 vstab contributors.
 * Android permits APK native entry points to read shell helpers as data.
 * The app moves each original script to <entry>.script before linking entry
 * to this native launcher. No shell command is composed from its arguments.
 */
#include <errno.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

int main(int argc, char **argv) {
    char script[PATH_MAX];
    int length;
    if (argv[0][0] == '/') length = snprintf(script, sizeof(script), "%s.script", argv[0]);
    else {
        const char *core = getenv("GIT_EXEC_PATH");
        if (!core || core[0] != '/' || strchr(argv[0], '/')) return 126;
        length = snprintf(script, sizeof(script), "%s/%s.script", core, argv[0]);
    }
    if (length < 0 || length >= (int)sizeof(script)) return 126;
    if (setenv("VSTAB_GIT_HELPER_SCRIPT", script, 1) != 0) return 126;
    char **args = calloc((size_t)argc + 4, sizeof(char *));
    if (!args) return 126;
    args[0] = "/system/bin/sh";
    args[1] = "-c";
    // Sourcing the data file preserves the original $0. Git's own shell
    // helpers use it for usage text and self-invocation (for example -h).
    // This shell program is constant; paths and arguments remain data.
    args[2] = ". \"$VSTAB_GIT_HELPER_SCRIPT\"";
    for (int i = 0; i < argc; ++i) args[i + 3] = argv[i];
    execv(args[0], args);
    fprintf(stderr, "vstab Git helper: %s\n", strerror(errno));
    return 126;
}
