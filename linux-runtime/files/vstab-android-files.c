/* SPDX-License-Identifier: MIT
 * Root-capability filesystem client for the managed Linux guest.
 * Configuration is a private file, never credentials in process arguments.
 */
#define _GNU_SOURCE
#define FUSE_USE_VERSION 31
#include <fuse3/fuse.h>
#include <curl/curl.h>
#include <json-c/json.h>
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/statvfs.h>
#include <time.h>
#include <unistd.h>

#define CHUNK (128 * 1024)
#define MAX_ROOTS 64
#define MAX_REPLY (2 * 1024 * 1024)
struct root { char id[65], capability[65], prefix[4097]; bool readonly; };
struct remote_file { struct root root; char handle[65]; bool append; };
static char endpoint[256], bearer[96];
static struct root roots[MAX_ROOTS];
static size_t root_count;
static pthread_mutex_t roots_mutex = PTHREAD_MUTEX_INITIALIZER;
static uid_t owner_uid = 1000;
static gid_t owner_gid = 1000;
static time_t roots_checked;

static json_object *field(json_object *object, const char *key) { json_object *value = NULL; return json_object_object_get_ex(object, key, &value) ? value : NULL; }
static const char *string(json_object *object, const char *key) { json_object *value = field(object, key); return value && json_object_is_type(value, json_type_string) ? json_object_get_string(value) : NULL; }
static void set_string(json_object *object, const char *key, const char *value) { json_object_object_add(object, key, json_object_new_string(value)); }
static void set_int(json_object *object, const char *key, int64_t value) { json_object_object_add(object, key, json_object_new_int64(value)); }
static void set_bool(json_object *object, const char *key, bool value) { json_object_object_add(object, key, json_object_new_boolean(value)); }
static uint64_t hash(const char *value) { uint64_t result = UINT64_C(1469598103934665603); while (*value) { result ^= (unsigned char)*value++; result *= UINT64_C(1099511628211); } return result ? result : 1; }

struct reply { char *bytes; size_t size; };
static size_t collect(char *data, size_t size, size_t count, void *argument) {
    struct reply *reply = argument;
    if (size && count > SIZE_MAX / size) return 0;
    size_t length = size * count;
    if (length > MAX_REPLY - reply->size) return 0;
    char *grown = realloc(reply->bytes, reply->size + length + 1);
    if (!grown) return 0;
    reply->bytes = grown; memcpy(grown + reply->size, data, length); reply->size += length; grown[reply->size] = 0;
    return length;
}
static int rpc(json_object *request, json_object **result) {
    *result = NULL;
    CURL *curl = curl_easy_init(); if (!curl) return -ENOMEM;
    struct curl_slist *headers = NULL;
    headers = curl_slist_append(headers, "Content-Type: application/json");
    headers = curl_slist_append(headers, bearer);
    struct reply reply = {0};
    const char *body = json_object_to_json_string_ext(request, JSON_C_TO_STRING_PLAIN);
    curl_easy_setopt(curl, CURLOPT_URL, endpoint);
    curl_easy_setopt(curl, CURLOPT_HTTPHEADER, headers);
    curl_easy_setopt(curl, CURLOPT_POSTFIELDS, body);
    curl_easy_setopt(curl, CURLOPT_POSTFIELDSIZE_LARGE, (curl_off_t)strlen(body));
    curl_easy_setopt(curl, CURLOPT_WRITEFUNCTION, collect);
    curl_easy_setopt(curl, CURLOPT_WRITEDATA, &reply);
    curl_easy_setopt(curl, CURLOPT_CONNECTTIMEOUT_MS, 5000L);
    curl_easy_setopt(curl, CURLOPT_TIMEOUT_MS, 120000L);
    curl_easy_setopt(curl, CURLOPT_NOSIGNAL, 1L);
    curl_easy_setopt(curl, CURLOPT_PROXY, "");
    curl_easy_setopt(curl, CURLOPT_PROTOCOLS_STR, "http");
    curl_easy_setopt(curl, CURLOPT_FOLLOWLOCATION, 0L);
    CURLcode status = curl_easy_perform(curl); long http = 0;
    curl_easy_getinfo(curl, CURLINFO_RESPONSE_CODE, &http);
    curl_easy_cleanup(curl); curl_slist_free_all(headers);
    int error = status == CURLE_OK ? 0 : status == CURLE_OPERATION_TIMEDOUT ? -ETIMEDOUT : -EIO;
    if (!error && http != 200) error = http == 403 ? -EACCES : -EIO;
    json_object *parsed = !error && reply.bytes ? json_tokener_parse(reply.bytes) : NULL;
    free(reply.bytes);
    if (!error && (!parsed || !field(parsed, "ok"))) error = -EPROTO;
    if (!error && !json_object_get_boolean(field(parsed, "ok"))) {
        int code = json_object_get_int(field(parsed, "errno")); error = code > 0 && code < 4096 ? -code : -EIO;
    }
    if (error) { if (parsed) json_object_put(parsed); return error; }
    *result = parsed; return 0;
}
static json_object *request(const struct root *root, const char *op, const char *path) {
    json_object *value = json_object_new_object(); if (!value) return NULL;
    set_string(value, "op", op);
    if (root) { set_string(value, "root", root->id); set_string(value, "capability", root->capability); set_string(value, "path", path ? path : ""); }
    return value;
}
static int execute(json_object *request) { json_object *result; int status = rpc(request, &result); json_object_put(request); if (!status) json_object_put(result); return status; }

static bool hex_secret(const char *value) {
    if (!value || strlen(value) != 64) return false;
    for (const char *p = value; *p; p++) if (!((*p >= '0' && *p <= '9') || (*p >= 'a' && *p <= 'f'))) return false;
    return true;
}
static int load_roots(json_object *list) {
    if (!list || !json_object_is_type(list, json_type_array) || json_object_array_length(list) > MAX_ROOTS) return -EINVAL;
    struct root next[MAX_ROOTS] = {0}; size_t count = json_object_array_length(list);
    for (size_t i = 0; i < count; i++) {
        json_object *item = json_object_array_get_idx(list, i);
        const char *id = string(item, "id"), *capability = string(item, "capability"), *path = string(item, "mountPath");
        if (!id || !*id || strlen(id) > 64 || !hex_secret(capability) || !path || strncmp(path, "/mnt/android/", 13)
                || strlen(path) > 4109 || !path[13] || strstr(path, "//") || strstr(path, "/../") || strstr(path, "/./")
                || !strcmp(path + strlen(path) - 3, "/..") || path[strlen(path) - 1] == '/') return -EINVAL;
        snprintf(next[i].id, sizeof(next[i].id), "%s", id);
        snprintf(next[i].capability, sizeof(next[i].capability), "%s", capability);
        snprintf(next[i].prefix, sizeof(next[i].prefix), "%s", path + 12);
        next[i].readonly = json_object_get_boolean(field(item, "readOnly"));
    }
    memcpy(roots, next, sizeof(next)); root_count = count; return 0;
}
static int refresh_roots(bool force) {
    pthread_mutex_lock(&roots_mutex);
    time_t now = time(NULL); int status = 0;
    if (force || now != roots_checked) {
        json_object *query = request(NULL, "roots", NULL), *result = NULL;
        status = rpc(query, &result); json_object_put(query);
        if (!status) { status = load_roots(field(result, "roots")); json_object_put(result); }
        if (!status) roots_checked = now;
    }
    pthread_mutex_unlock(&roots_mutex); return status;
}
static int route(const char *path, struct root *root, const char **relative) {
    int status = refresh_roots(false); if (status) return status;
    pthread_mutex_lock(&roots_mutex);
    int found = -ENOENT;
    for (size_t i = 0; i < root_count; i++) { size_t length = strlen(roots[i].prefix);
        if (!strncmp(path, roots[i].prefix, length) && (!path[length] || path[length] == '/')) {
            *root = roots[i]; *relative = path + length + (path[length] == '/'); found = 0; break;
        }
    }
    if (found && !strcmp(path, "/")) found = 1;
    if (found < 0) for (size_t i = 0; i < root_count; i++) {
        size_t length = strlen(path);
        if (!strncmp(path, roots[i].prefix, length) && roots[i].prefix[length] == '/') { found = 1; break; }
    }
    pthread_mutex_unlock(&roots_mutex); return found;
}
static void attributes(const struct root *root, json_object *entry, struct stat *value) {
    memset(value, 0, sizeof(*value)); value->st_mode = (mode_t)json_object_get_int(field(entry, "mode"));
    if (root->readonly) value->st_mode &= ~0222;
    value->st_uid = owner_uid; value->st_gid = owner_gid; value->st_nlink = S_ISDIR(value->st_mode) ? 2 : 1;
    value->st_size = json_object_get_int64(field(entry, "size")); value->st_blksize = 4096; value->st_blocks = (value->st_size + 511) / 512;
    int64_t modified = json_object_get_int64(field(entry, "mtime"));
    value->st_mtim.tv_sec = modified / 1000; value->st_mtim.tv_nsec = (modified % 1000) * 1000000;
    value->st_atim = value->st_ctim = value->st_mtim;
    value->st_ino = hash(root->id) ^ (uint64_t)json_object_get_int64(field(entry, "inode")); if (!value->st_ino) value->st_ino = 1;
}
static int fs_getattr(const char *path, struct stat *value, struct fuse_file_info *info) {
    struct remote_file *file = info && info->fh ? (struct remote_file *)(uintptr_t)info->fh : NULL;
    struct root root; const char *relative;
    int status = file ? 0 : route(path, &root, &relative); if (status < 0) return status;
    if (status == 1) { memset(value, 0, sizeof(*value)); value->st_mode = S_IFDIR | 0555; value->st_uid = owner_uid;
        value->st_gid = owner_gid; value->st_nlink = 2; value->st_ino = hash(path); return 0; }
    if (file) root = file->root;
    json_object *query = request(&root, file ? "fstat" : "stat", file ? "" : relative), *result;
    if (file) set_string(query, "handle", file->handle);
    status = rpc(query, &result); json_object_put(query);
    if (!status) { json_object *entry = field(result, "entry"); if (!entry) status = -EPROTO; else attributes(&root, entry, value); json_object_put(result); }
    return status;
}
static int fs_readdir(const char *path, void *buffer, fuse_fill_dir_t fill, off_t offset, struct fuse_file_info *info, enum fuse_readdir_flags flags) {
    (void)info; (void)flags;
    struct root root; const char *relative; int status = route(path, &root, &relative); if (status < 0) return status;
    if (offset < 0) return -EINVAL;
    if (offset == 0 && fill(buffer, ".", NULL, 1, 0)) return 0;
    if (offset <= 1 && fill(buffer, "..", NULL, 2, 0)) return 0;
    if (status == 1) {
        char names[MAX_ROOTS][256] = {{0}}; size_t count = 0;
        pthread_mutex_lock(&roots_mutex);
        for (size_t i = 0; i < root_count; i++) {
            size_t length = !strcmp(path, "/") ? 0 : strlen(path);
            if (strncmp(roots[i].prefix, path, length) || roots[i].prefix[length] != '/') continue;
            const char *start = roots[i].prefix + length + 1, *slash = strchr(start, '/'); size_t n = slash ? (size_t)(slash - start) : strlen(start);
            if (n > 255) { status = -ENAMETOOLONG; break; }
            bool duplicate = false; for (size_t j = 0; j < count; j++) if (strlen(names[j]) == n && !strncmp(names[j], start, n)) duplicate = true;
            if (!duplicate) { memcpy(names[count], start, n); names[count++][n] = 0; }
        }
        pthread_mutex_unlock(&roots_mutex);
        if (status < 0) return status;
        for (size_t i = offset > 2 ? (size_t)offset - 2 : 0; i < count; i++) if (fill(buffer, names[i], NULL, (off_t)i + 3, 0)) break;
        return 0;
    }
    int64_t next = offset > 2 ? offset - 2 : 0;
    do {
        json_object *query = request(&root, "list", relative), *result; set_int(query, "offset", next); set_int(query, "limit", 256);
        status = rpc(query, &result); json_object_put(query); if (status) return status;
        json_object *entries = field(result, "entries");
        if (!entries || !json_object_is_type(entries, json_type_array)) { json_object_put(result); return -EPROTO; }
        for (size_t i = 0; i < json_object_array_length(entries); i++) {
            json_object *entry = json_object_array_get_idx(entries, i); const char *name = string(entry, "name"); struct stat value;
            if (!name || !*name || strchr(name, '/') || !strcmp(name, ".") || !strcmp(name, "..")) { json_object_put(result); return -EPROTO; }
            attributes(&root, entry, &value);
            if (fill(buffer, name, &value, (off_t)next + (off_t)i + 3, FUSE_FILL_DIR_PLUS)) { json_object_put(result); return 0; }
        }
        int64_t following = json_object_get_int64(field(result, "next"));
        if (following >= 0 && following <= next) { json_object_put(result); return -EPROTO; }
        next = following; json_object_put(result);
    } while (next >= 0);
    return 0;
}
static int open_file(const char *path, mode_t mode, struct fuse_file_info *info, bool create) {
    struct root root; const char *relative; int status = route(path, &root, &relative); if (status) return status == 1 ? -EISDIR : status;
    bool write = (info->flags & O_ACCMODE) != O_RDONLY; if (root.readonly && (write || create)) return -EROFS;
    struct remote_file *file = calloc(1, sizeof(*file)); if (!file) return -ENOMEM;
    json_object *query = request(&root, "open", relative), *result;
    set_bool(query, "read", (info->flags & O_ACCMODE) != O_WRONLY); set_bool(query, "write", write); set_bool(query, "create", create); set_bool(query, "exclusive", (info->flags & O_EXCL) != 0);
    set_bool(query, "truncate", (info->flags & O_TRUNC) != 0); set_int(query, "mode", mode & 0777);
    status = rpc(query, &result); json_object_put(query);
    if (!status) { const char *handle = string(result, "handle");
        if (!hex_secret(handle)) status = -EPROTO;
        else { file->root = root; file->append = (info->flags & O_APPEND) != 0; snprintf(file->handle, sizeof(file->handle), "%s", handle);
            info->fh = (uint64_t)(uintptr_t)file; info->direct_io = 1; }
        json_object_put(result);
    }
    if (status) free(file);
    return status;
}
static int fs_open(const char *path, struct fuse_file_info *info) { return open_file(path, 0644, info, false); }
static int fs_create(const char *path, mode_t mode, struct fuse_file_info *info) { return open_file(path, mode, info, true); }
static const char alphabet[] = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
static char *encode64(const unsigned char *bytes, size_t length) {
    char *result = malloc(((length + 2) / 3) * 4 + 1); if (!result) return NULL;
    size_t i = 0, j = 0;
    while (i < length) { size_t remaining = length - i; uint32_t n = (uint32_t)bytes[i++] << 16;
        if (remaining > 1) n |= (uint32_t)bytes[i++] << 8;
        if (remaining > 2) n |= bytes[i++];
        result[j++] = alphabet[n >> 18]; result[j++] = alphabet[(n >> 12) & 63];
        result[j++] = remaining > 1 ? alphabet[(n >> 6) & 63] : '='; result[j++] = remaining > 2 ? alphabet[n & 63] : '=';
    }
    result[j] = 0; return result;
}
static int decode64(const char *source, char *output, size_t limit) {
    size_t length = strlen(source), count = 0; if (length % 4 || length > ((limit + 2) / 3) * 4) return -EPROTO;
    for (size_t i = 0; i < length; i += 4) { uint32_t n = 0; int padding = 0;
        for (size_t j = 0; j < 4; j++) { const char *p = strchr(alphabet, source[i + j]);
            if (source[i + j] == '=') { if (j < 2 || i + 4 != length) return -EPROTO; padding++; n <<= 6; }
            else { if (!p || !source[i + j] || padding) return -EPROTO; n = (n << 6) | (uint32_t)(p - alphabet); }
        }
        int bytes = 3 - padding; if (count + (size_t)bytes > limit) return -EPROTO;
        for (int j = 0; j < bytes; j++) output[count++] = (char)(n >> (16 - 8 * j));
    }
    return (int)count;
}
static json_object *handle_request(struct remote_file *file, const char *operation) { json_object *query = request(&file->root, operation, ""); set_string(query, "handle", file->handle); return query; }
static int fs_read(const char *path, char *buffer, size_t size, off_t offset, struct fuse_file_info *info) {
    (void)path; if (!info || !info->fh || offset < 0) return -EBADF; struct remote_file *file = (struct remote_file *)(uintptr_t)info->fh;
    size_t total = 0;
    while (total < size) { size_t count = size - total > CHUNK ? CHUNK : size - total;
        json_object *query = handle_request(file, "read"), *result; set_int(query, "offset", offset + (off_t)total); set_int(query, "size", (int64_t)count);
        int status = rpc(query, &result); json_object_put(query); if (status) return total ? (int)total : status;
        const char *data = string(result, "data"); int got = data ? decode64(data, buffer + total, count) : -EPROTO; json_object_put(result);
        if (got < 0) return total ? (int)total : got;
        total += (size_t)got;
        if ((size_t)got < count) break;
    }
    return (int)total;
}
static int fs_write(const char *path, const char *buffer, size_t size, off_t offset, struct fuse_file_info *info) {
    (void)path; if (!info || !info->fh || offset < 0) return -EBADF; struct remote_file *file = (struct remote_file *)(uintptr_t)info->fh;
    size_t total = 0;
    while (total < size) { size_t count = size - total > CHUNK ? CHUNK : size - total; char *data = encode64((const unsigned char *)buffer + total, count);
        if (!data) return total ? (int)total : -ENOMEM;
        json_object *query = handle_request(file, "write"), *result; set_int(query, "offset", offset + (off_t)total); set_bool(query, "append", file->append);
        set_string(query, "data", data); free(data); int status = rpc(query, &result); json_object_put(query); if (status) return total ? (int)total : status;
        int64_t written = json_object_get_int64(field(result, "written")); json_object_put(result);
        if (written <= 0 || written > (int64_t)count) return total ? (int)total : -EPROTO;
        total += (size_t)written;
    }
    return (int)total;
}
static int fs_flush(const char *path, struct fuse_file_info *info) { (void)path; if (!info || !info->fh) return -EBADF; return execute(handle_request((struct remote_file *)(uintptr_t)info->fh, "flush")); }
static int fs_fsync(const char *path, int datasync, struct fuse_file_info *info) { (void)path; (void)datasync; if (!info || !info->fh) return -EBADF; return execute(handle_request((struct remote_file *)(uintptr_t)info->fh, "fsync")); }
static int fs_release(const char *path, struct fuse_file_info *info) { (void)path; if (!info || !info->fh) return -EBADF;
    struct remote_file *file = (struct remote_file *)(uintptr_t)info->fh; int status = execute(handle_request(file, "release")); free(file); info->fh = 0; return status; }
static int path_operation(const char *path, const char *operation, int mode) { struct root root; const char *relative;
    int status = route(path, &root, &relative); if (status) return status == 1 ? -EROFS : status;
    if (root.readonly) return -EROFS;
    json_object *query = request(&root, operation, relative); if (mode >= 0) set_int(query, "mode", mode); return execute(query); }
static int fs_mkdir(const char *path, mode_t mode) { return path_operation(path, "mkdir", (int)(mode & 0777)); }
static int fs_unlink(const char *path) { return path_operation(path, "unlink", -1); }
static int fs_rmdir(const char *path) { return path_operation(path, "rmdir", -1); }
static int fs_chmod(const char *path, mode_t mode, struct fuse_file_info *info) { (void)info; return path_operation(path, "chmod", (int)(mode & 0777)); }
static int fs_truncate(const char *path, off_t length, struct fuse_file_info *info) {
    if (length < 0) return -EINVAL;
    struct root root; const char *relative; json_object *query;
    if (info && info->fh) query = handle_request((struct remote_file *)(uintptr_t)info->fh, "truncate");
    else { int status = route(path, &root, &relative); if (status) return status == 1 ? -EISDIR : status;
        if (root.readonly) return -EROFS;
        query = request(&root, "truncate", relative); }
    set_int(query, "size", length); return execute(query);
}
static int fs_rename(const char *from, const char *to, unsigned flags) {
    if (flags & ~1U) return -EOPNOTSUPP;
    struct root a, b; const char *source, *destination;
    int status = route(from, &a, &source); if (status) return status == 1 ? -EROFS : status;
    status = route(to, &b, &destination); if (status) return status == 1 ? -EROFS : status;
    if (strcmp(a.id, b.id)) return -EXDEV;
    if (a.readonly) return -EROFS;
    json_object *query = request(&a, "rename", source); set_string(query, "to", destination); set_bool(query, "noReplace", (flags & 1U) != 0); return execute(query);
}
static int fs_utimens(const char *path, const struct timespec values[2], struct fuse_file_info *info) {
    (void)info; struct root root; const char *relative; int status = route(path, &root, &relative); if (status) return status == 1 ? -EROFS : status;
    if (root.readonly) return -EROFS;
    int64_t now; struct timespec clock; clock_gettime(CLOCK_REALTIME, &clock); now = (int64_t)clock.tv_sec * 1000 + clock.tv_nsec / 1000000;
    int64_t stamps[2]; for (int i = 0; i < 2; i++) stamps[i] = values[i].tv_nsec == UTIME_OMIT ? -1 : values[i].tv_nsec == UTIME_NOW ? now : (int64_t)values[i].tv_sec * 1000 + values[i].tv_nsec / 1000000;
    if (stamps[0] < 0 && stamps[1] < 0) return 0;
    json_object *query = request(&root, "utimens", relative); set_int(query, "access", stamps[0]); set_int(query, "modified", stamps[1]); return execute(query);
}
static int fs_statfs(const char *path, struct statvfs *value) {
    struct root root; const char *relative; int status = route(path, &root, &relative); if (status < 0) return status;
    if (status == 1) { pthread_mutex_lock(&roots_mutex); if (!root_count) { pthread_mutex_unlock(&roots_mutex); return -ENODEV; } root = roots[0]; pthread_mutex_unlock(&roots_mutex); relative = ""; }
    json_object *query = request(&root, "statfs", relative), *result; status = rpc(query, &result); json_object_put(query); if (status) return status;
    memset(value, 0, sizeof(*value)); value->f_bsize = value->f_frsize = 4096; value->f_namemax = 255;
    value->f_blocks = value->f_bfree = value->f_bavail = (fsblkcnt_t)(json_object_get_int64(field(result, "available")) / 4096); json_object_put(result); return 0;
}
static int unsupported_link(const char *from, const char *to) { (void)from; (void)to; return -EOPNOTSUPP; }
static int unsupported_readlink(const char *path, char *buffer, size_t length) { (void)path; (void)buffer; (void)length; return -EOPNOTSUPP; }
static void *fs_init(struct fuse_conn_info *connection, struct fuse_config *config) {
    (void)connection; config->use_ino = 1; config->entry_timeout = config->attr_timeout = config->negative_timeout = 0; return NULL;
}
static struct fuse_operations operations = {
    .init = fs_init, .getattr = fs_getattr, .readdir = fs_readdir, .open = fs_open, .create = fs_create,
    .read = fs_read, .write = fs_write, .flush = fs_flush, .fsync = fs_fsync, .release = fs_release,
    .mkdir = fs_mkdir, .unlink = fs_unlink, .rmdir = fs_rmdir, .rename = fs_rename, .chmod = fs_chmod,
    .truncate = fs_truncate, .utimens = fs_utimens, .statfs = fs_statfs, .link = unsupported_link,
    .symlink = unsupported_link, .readlink = unsupported_readlink
};
static int configuration(const char *filename) {
    int fd = open(filename, O_RDONLY | O_CLOEXEC | O_NOFOLLOW); if (fd < 0) return -errno;
    struct stat metadata; if (fstat(fd, &metadata) || !S_ISREG(metadata.st_mode) || metadata.st_size < 2 || metadata.st_size > 1024 * 1024
            || metadata.st_uid != getuid() || (metadata.st_mode & 0077)) { close(fd); return -EACCES; }
    char *bytes = malloc((size_t)metadata.st_size + 1); if (!bytes) { close(fd); return -ENOMEM; }
    size_t offset = 0; while (offset < (size_t)metadata.st_size) { ssize_t count = read(fd, bytes + offset, (size_t)metadata.st_size - offset);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) { free(bytes); close(fd); return -EIO; }
        offset += (size_t)count; }
    close(fd); bytes[offset] = 0; json_object *config = json_tokener_parse(bytes); memset(bytes, 0, offset); free(bytes); if (!config) return -EINVAL;
    const char *address = string(config, "endpoint"), *token = string(config, "token"); int status = 0;
    if (json_object_get_int(field(config, "version")) != 1 || !address || strlen(address) >= sizeof(endpoint) || !hex_secret(token)) status = -EINVAL;
    if (!status) {
        const char *port = !strncmp(address, "http://10.0.2.2:", 16) ? address + 16 : !strncmp(address, "http://127.0.0.1:", 17) ? address + 17 : NULL;
        char *end = NULL; long number = port ? strtol(port, &end, 10) : 0;
        if (!port || number < 1 || number > 65535 || !end || strcmp(end, "/v1")) status = -EINVAL;
    }
    if (!status) { snprintf(endpoint, sizeof(endpoint), "%s", address); snprintf(bearer, sizeof(bearer), "Authorization: Bearer %s", token); status = load_roots(field(config, "roots")); }
    json_object_put(config); return status;
}
#ifndef VSTAB_FILES_NO_MAIN
int main(int argc, char **argv) {
    if (argc < 4 || strcmp(argv[1], "--config")) { fprintf(stderr, "usage: vstab-android-files --config FILE MOUNTPOINT [fuse options]\n"); return 2; }
    int status = configuration(argv[2]); if (status) { fprintf(stderr, "Android filesystem configuration is unavailable (%d).\n", -status); return 1; }
    if (curl_global_init(CURL_GLOBAL_DEFAULT) != CURLE_OK) return 1;
    status = refresh_roots(true); if (status) { fprintf(stderr, "Android filesystem broker is unavailable (%d).\n", -status); curl_global_cleanup(); return 1; }
    char **arguments = calloc((size_t)argc, sizeof(char *)); if (!arguments) { curl_global_cleanup(); return 1; }
    arguments[0] = argv[0]; for (int i = 3; i < argc; i++) arguments[i - 2] = argv[i];
    status = fuse_main(argc - 2, arguments, &operations, NULL); free(arguments); curl_global_cleanup(); memset(bearer, 0, sizeof(bearer)); return status;
}
#endif
