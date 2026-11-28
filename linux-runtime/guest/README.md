# Shared Linux guest

This directory builds the real Linux kernel and glibc filesystem used by the local development environment. The same guest runs under bundled QEMU or QEMU hosted by Termux. Editor extensions execute as the ordinary `developer` user (UID/GID 1000) on guest ext4.

Build the kernel and guest for each architecture:

```sh
python3 linux-runtime/guest/build-kernel.py --arch x86_64
python3 scripts/build-linux-guest.py --arch x86_64 \
  --kernel-dir .local/linux-runtime/guest/kernel-x86_64 \
  --files-source linux-runtime/files/vstab-android-files.c
```

Use `arm64-v8a` for both commands to build ARM64. An x86 host needs an AArch64 userspace binfmt interpreter to run the architecture-matched Debian rootfs assembly container. That interpreter is a host build dependency; it is not shipped in the Android application or the guest. Kernel compilation uses a cross compiler in an isolated container.

Assets and their SHA-256 manifest are published under `.local/linux-runtime/guest/assets/`. The built-in kernel needs no initrd. `release/linux-guest.lock.json` pins the Debian container manifests, Linux source package, original paired code-server archives and official Debian snapshot timestamps/signed metadata hashes. Debian package inventory and archive hashes are recorded under each architecture's `assembled/` directory. `validate-debian-snapshot.py` authenticates the signed snapshot indexes and verifies that both architectures' recorded downloaded package inputs match. Build-only APT uses the immutable snapshots; the installed guest retains normal Debian update sources for user-managed package installation and security updates.

The default image compression is XZ with a 16 MiB decoder dictionary. `--compression gzip` provides the earlier bootstrap format. Only the format referenced by the generated manifest remains in the asset output. Exact duplicate editor files are hardlinked without changing their contents, permissions or paths. `--refresh-integration` repopulates an existing assembled disk after first-party integration changes, without reinstalling or modifying upstream packages.

The seed filesystem remains 2 GiB. New builds advertise `logicalDiskBytes: 8589934592` and `capabilities.diskGrowth: true`; the host can extend the stopped sparse disk to that capacity before boot without decoding an 8 GiB seed. Before starting the backend, the guest verifies that `/dev/vda` is its mounted writable ext4 root, compares the existing filesystem geometry with the block device and uses the already-installed standard `resize2fs` to grow it. It never formats or shrinks an existing filesystem. Readiness reports actual disk/filesystem capacity and whether this boot performed growth.

The editor's original Linux Node binary, server JavaScript, native modules and frontend assets are retained. Optional bundled vendor extension/engine components are excluded by explicit component paths. Installed public extension packages are not rewritten by the guest builder.

The host provides a read-only 9p share tagged `vstab-bootstrap`, containing `bootstrap.env`. Values are parsed as data rather than sourced as shell code:

| Key | Value |
| --- | --- |
| `VSTAB_ENVIRONMENT_ID_B64` | Base64 UTF-8 environment identity |
| `VSTAB_AUTHORIZED_KEY_B64` | Base64 complete public SSH key line for the host controller |
| `VSTAB_EDITOR_PASSWORD_B64` | Base64 per-session editor password, at least 16 characters |
| `VSTAB_FILES_CONFIG_B64` | Base64 private Android file broker JSON |
| `VSTAB_CONTROL_HOST` | Control endpoint host, normally `10.0.2.2` |
| `VSTAB_CONTROL_PORT` | Control endpoint TCP port |
| `VSTAB_CONTROL_TOKEN_B64` | Base64 private control bearer token |

Guest SSH listens on port 22 and authenticated code-server on port 3000. The host forwards these through Android loopback listeners. The serial `VSTAB_LINUX_READY` JSON marker contains the public SSH host key, backend process ID and editor versions after SSH and authenticated workbench readiness. Secrets are never included in the marker.

Each new image records a deterministic `guestBuildId`, `platformApiVersion: 1`, `dataSchemaVersion: 1`, `userDataMode: "whole-root"` and `upgradePolicy: "preserve-installed-system"` in its metadata and asset manifest. Readiness repeats the build/API/data fields and reports `capabilities.gracefulStopRequest: true`. The build ID hashes the paired editor archive, kernel/config, package inventory and installed first-party integration files; it is separate from the compressed and unpacked disk integrity hashes.

The host must retain installed build metadata and its matching kernel alongside the persistent disk. A different shipped build must not overwrite that disk or silently assign it the new build ID. Existing whole-root disks may contain projects, installed packages and user changes anywhere. Separate `/home` storage and automatic migration are deferred; `capabilities.separateHomeDisk` is false. Legacy disks without build metadata must remain intact and keep their existing kernel until an explicit, recoverable migration exists.

For graceful stop, the host writes a regular file `stop-request` in the read-only bootstrap share whose entire contents exactly equal the current environment ID, without a newline. The guest checks once per second, terminates its services, syncs and powers off. Requests for another environment, symlinks and absent files do not stop the machine. Remove the request before restarting that environment.

Inbound controller authentication uses `/run/vstab/machine-authorized-keys`, separately from the user's outbound SSH data. When Android grants `/mnt/android/app/ssh`, the guest's entire `~/.ssh` points there; an earlier guest directory is preserved during migration. Android files are mounted with the ordinary FUSE client at `/mnt/android`. Guest root privileges do not grant additional Android filesystem permissions. `developer` has passwordless sudo inside this managed development VM.

The standard `BROWSER`/`xdg-open` integration uses the private HTTP control endpoint and waits for the host to acknowledge browser opening and callback forwarding. It includes generic process/socket ownership checks.

Host-only qualification:

```sh
python3 linux-runtime/guest/prove-boot.py --arch x86_64
docker build -f linux-runtime/guest/Proof.Dockerfile \
  -t vstab-linux-guest-proof:local linux-runtime/guest
python3 linux-runtime/guest/prove-boot.py --arch arm64-v8a \
  --qemu-container vstab-linux-guest-proof:local
```

The proof checks pinned public-key SSH, ordinary-user identity, nested execution from writable ext4, the paired native terminal module, authenticated editor HTML and anonymous access rejection. Host QEMU results do not establish Android startup performance, physical ARM64 support, SAF behavior or authenticated third-party service operation.

Use `--prove-disk-growth` to check online 2→8 GiB growth and preservation of a pre-existing ordinary-user file in the proof copy. `--assets-root` selects a frozen/versioned manifest for that proof; `build-linux-guest.py --assets-output` publishes a candidate separately without replacing an earlier frozen image.
