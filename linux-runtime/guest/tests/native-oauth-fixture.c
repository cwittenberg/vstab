/* SPDX-License-Identifier: MIT
 * A native process owning its own loopback callback, independent of any provider. */
#define _GNU_SOURCE
#include <arpa/inet.h>
#include <errno.h>
#include <poll.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/wait.h>
#include <unistd.h>
int main(int argc, char **argv) {
    if (argc != 3) return 2;
    int listener = socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC, 0);
    struct sockaddr_in address = {.sin_family = AF_INET, .sin_addr.s_addr = htonl(INADDR_LOOPBACK)};
    if (listener < 0 || bind(listener, (struct sockaddr *)&address, sizeof(address)) || listen(listener, 1)) return 3;
    socklen_t length = sizeof(address); if (getsockname(listener, (struct sockaddr *)&address, &length)) return 4;
    char url[512];
    snprintf(url, sizeof(url), "https://identity.invalid/authorize?response_type=code&redirect_uri=http%%3A%%2F%%2Flocalhost%%3A%u%%2Fcallback", ntohs(address.sin_port));
    pid_t child = fork(); if (child < 0) return 5;
    if (!child) { execl(argv[1], argv[1], argv[2], url, (char *)NULL); _exit(127); }
    struct pollfd waiting = {.fd = listener, .events = POLLIN};
    if (poll(&waiting, 1, 15000) <= 0) { kill(child, SIGTERM); waitpid(child, NULL, 0); return 6; }
    int client = accept4(listener, NULL, NULL, SOCK_CLOEXEC); if (client < 0) return 7;
    char request[4096]; ssize_t n = read(client, request, sizeof(request) - 1); if (n <= 0) return 8; request[n] = 0;
    if (strncmp(request, "GET /callback?fixture=returned ", 31)) return 9;
    const char *response = "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nOK";
    if (write(client, response, strlen(response)) != (ssize_t)strlen(response)) return 10;
    close(client); close(listener);
    int status = 0; if (waitpid(child, &status, 0) != child || !WIFEXITED(status) || WEXITSTATUS(status)) return 11;
    puts("PASS: native loopback callback after acknowledged browser forwarding."); return 0;
}
