/* SPDX-License-Identifier: MIT */
#define VSTAB_FILES_NO_MAIN
#include "../vstab-android-files.c"
#include <assert.h>

static int no_entries(void *context, const char *name, const struct stat *value, off_t offset, enum fuse_fill_dir_flags flags) {
    (void)value; (void)offset; (void)flags;
    if (strcmp(name, ".") && strcmp(name, "..")) (*(int *)context)++;
    return 0;
}
int main(int argc, char **argv) {
    assert(argc == 2); assert(configuration(argv[1]) == 0);
    assert(curl_global_init(CURL_GLOBAL_DEFAULT) == CURLE_OK); assert(refresh_roots(true) == 0);
    unsigned char data[CHUNK + 19]; for (size_t i = 0; i < sizeof(data); i++) data[i] = (unsigned char)(i * 31U);
    char decoded[sizeof(data)];
    for (size_t n = 0; n < 514; n++) { char *encoded = encode64(data, n); assert(encoded);
        assert(decode64(encoded, decoded, n) == (int)n); assert(!memcmp(data, decoded, n)); free(encoded); }
    for (const char **p = (const char *[]){"A===", "====", "AA=A", "~AAA", "AAAAAA", NULL}; *p; p++) assert(decode64(*p, decoded, sizeof(decoded)) < 0);
    struct stat attributes;
    assert(fs_getattr("/", &attributes, NULL) == 0 && S_ISDIR(attributes.st_mode));
    int entries = 0; assert(fs_readdir("/", &entries, no_entries, 0, NULL, 0) == 0 && entries == 2);
    assert(fs_mkdir("/app/projects/client-test", 0700) == 0);
    struct fuse_file_info file = {.flags = O_RDWR | O_CREAT | O_EXCL};
    const char *name = "/app/projects/client-test/percent % & unicode-λ.bin";
    assert(fs_create(name, 0600, &file) == 0);
    struct fuse_file_info duplicate = {.flags = O_RDWR | O_CREAT | O_EXCL}; assert(fs_create(name, 0600, &duplicate) == -EEXIST);
    assert(fs_write(name, (const char *)data, sizeof(data), 3, &file) == (int)sizeof(data));
    assert(fs_read(name, decoded, sizeof(decoded), 3, &file) == (int)sizeof(data)); assert(!memcmp(data, decoded, sizeof(data)));
    assert(fs_getattr(name, &attributes, &file) == 0 && attributes.st_size == (off_t)sizeof(data) + 3);
    assert(attributes.st_uid == 1000 && attributes.st_gid == 1000);
    assert((attributes.st_mode & 0777) == 0600);
    assert(fs_truncate(name, 19, &file) == 0); assert(fs_flush(name, &file) == 0); assert(fs_fsync(name, 0, &file) == 0);
    assert(fs_release(name, &file) == 0);
    const char *renamed = "/app/projects/client-test/renamed.bin";
    assert(fs_rename(name, renamed, 0) == 0); assert(fs_chmod(renamed, 0640, NULL) == 0);
    assert(fs_getattr(renamed, &attributes, NULL) == 0 && (attributes.st_mode & 0777) == 0640 && attributes.st_size == 19);
    assert(fs_getattr(name, &attributes, NULL) == -ENOENT);
    struct fuse_file_info readonly = {.flags = O_WRONLY | O_CREAT}; assert(fs_create("/folders/read-only/new", 0600, &readonly) == -EROFS);
    assert(fs_unlink(renamed) == 0); assert(fs_rmdir("/app/projects/client-test") == 0);
    assert(fs_getattr("/app/projects/../private", &attributes, NULL) == -EINVAL);
    assert(operations.symlink("target", "/app/projects/link") == -EOPNOTSUPP);
    curl_global_cleanup(); puts("PASS: native FUSE callbacks, HTTP protocol, binary chunks, modes, locks and read-only roots.");
    return 0;
}
