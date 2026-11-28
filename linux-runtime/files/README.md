# Android files inside the Linux guest

`AndroidFileBroker` runs under VStab's Android UID. The generic Linux FUSE client
mounts approved roots at ordinary paths under `/mnt/android`; both VM hosts use
the same client and protocol. No extension identifiers or engines are involved.

Android integration:

```java
AndroidFileBroker broker = new AndroidFileBroker(context);
broker.addAppDirectory("projects", "/mnt/android/app/workspaces", projects, false);
broker.addDocumentTree("folder-id", "/mnt/android/folders/id", grantedTree, readOnly);
JSONObject configuration = broker.manifest("10.0.2.2");
// Pass configuration privately through VSTAB_FILES_CONFIG_B64. Never log it.
broker.revoke("folder-id");
broker.close();
```

Adding a document tree does not acquire a URI grant. The activity must obtain the
appropriate Android picker grant first. The broker checks access for every
operation. Its authenticated `roots` request refreshes the guest namespace, so a
new export does not require restarting the VM.

The guest supervisor writes the decoded configuration to `/run/vstab/files.json`
with mode `0600`, then runs this as root:

```sh
vstab-android-files --config /run/vstab/files.json /mnt/android \
  -f -o allow_other,default_permissions
```

Presented ownership is the guest development UID/GID `1000`; raw file modes are
preserved, including SSH key mode `0600`. The mount has no host device/FUSE
requirement: `/dev/fuse` belongs to the guest kernel.

## Filesystem guarantees

Raw directory operations walk descriptor-anchored parents with `O_NOFOLLOW`.
Path traversal and symbolic-link traversal cannot grant access outside a root.
Ordinary exclusive file creation, replacement rename, random access, file modes
and `fsync` use the Android filesystem operations. `RENAME_NOREPLACE`, hard links
and symbolic links are explicitly unsupported, rather than emulated with a
check-then-write race.

Document trees use `ContentResolver` only within the granted tree. Writable files
are staged privately, checked against their opening content before committing,
and verified after a provider save. Conflicts return `EAGAIN`; write failures
retain staging/recovery copies under `files/file-broker/`. Document providers do
not supply POSIX modes, atomic replacement, exclusive creation or durable
`fsync`; those operations return errors. Closing a writable handle commits its
bytes, and `flush` reports save failures to the Linux application.

Guest-originated writes generate ordinary guest filesystem notifications.
Changes made directly through Android or another provider client are visible
on a subsequent stat/reopen. This implementation does not synthesize inotify
events for external changes: `externalNotifications` reports
`revalidate-on-access`. Canonical repositories and builds belong on guest ext4;
Android document mirrors remain explicitly synchronized copies.

## Protocol and bounds

The transport is loopback-only HTTP `POST /v1`, `Content-Type: application/json`.
Every request requires the per-boot `Authorization: Bearer` secret. Root requests
also include the root's independent `id` and `capability`. Secrets are never URL
parameters or process arguments. Responses return `ok` and a numeric Linux
`errno`; an unavailable or revoked root is an error, never an empty directory.

Operations: `roots`, `stat`, `fstat`, `list`, `open`, `read`, `write`, `truncate`,
`flush`, `fsync`, `release`, `mkdir`, `unlink`, `rmdir`, `rename`, `chmod`, `utimens`
and `statfs`. File handles are random capabilities bound to one root. Reads and
writes carry at most 128 KiB, encoded as Base64; directory pages contain at most
256 entries. The broker permits 64 roots and 64 open handles, closes a root's
handles when it is revoked, bounds request headers to 16 KiB and JSON bodies to
192 KiB, and uses a bounded worker queue. Document staging permits at most
512 MiB per file and 1 GiB of reserved active/recovery staging per process.

## Build and checks

Build inside the Linux image toolchain with `libfuse3-dev`, a libcurl development
package and `libjson-c-dev`:

```sh
cc -std=c11 -D_FILE_OFFSET_BITS=64 -Wall -Wextra -Werror \
  $(pkg-config --cflags fuse3 libcurl json-c) vstab-android-files.c \
  -o vstab-android-files $(pkg-config --libs fuse3 libcurl json-c) -lpthread
```

`AndroidFileBrokerTest` exercises the production HTTP server with a host
filesystem test adapter. `tests/client-test.c` exercises real native FUSE
callbacks over that HTTP connection. `tests/FileBrokerHostHarness.java` provides
a private fixture configuration for an actual Linux guest mount. These checks
establish transport/client behavior; they do not establish Android storage
behavior. `AndroidFileBrokerStorageTest` uses actual Android descriptors and a
real test `DocumentsProvider` and must run on the owned Android test device.
