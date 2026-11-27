/* MIT License. Copyright (c) 2026 vstab contributors.
 * Read-only APK entry point for npm/npx JavaScript; no executable app-data file.
 */
#include <errno.h>
#include <libgen.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

int main(int argc, char **argv) {
    const char *node = getenv("VSTAB_NODE_PATH");
    const char *usr = getenv("VSTAB_USR");
    if (!node || node[0] != '/' || !usr || usr[0] != '/') {
        fputs("vstab npm launcher: native runtime environment is missing\n", stderr);
        return 126;
    }
    const char *name = strrchr(argv[0], '/');
    name = name ? name + 1 : argv[0];
    int npx = strcmp(name, "npx") == 0 || strcmp(name, "libnpx.so") == 0;
    char script[PATH_MAX];
    if (snprintf(script, sizeof(script), "%s/lib/node_modules/npm/bin/%s-cli.js",
                 usr, npx ? "npx" : "npm") >= (int)sizeof(script)) return 126;
    char **args = calloc((size_t)argc + 2, sizeof(char *));
    if (!args) return 126;
    args[0] = (char *)node;
    args[1] = script;
    for (int index = 1; index < argc; ++index) args[index + 1] = argv[index];
    execv(node, args);
    fprintf(stderr, "vstab npm launcher: %s\n", strerror(errno));
    return 126;
}
