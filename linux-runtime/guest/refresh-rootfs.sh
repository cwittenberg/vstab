#!/bin/bash
# Rebuild the disk after first-party integration changes, retaining the already
# assembled, unmodified upstream Debian/editor package contents and inventory.
set -euo pipefail
export DEBIAN_FRONTEND=noninteractive
if ! command -v mkfs.ext4 >/dev/null; then
  cp /guest-src/debian-snapshot.sources /etc/apt/sources.list.d/debian.sources
  apt-get update
  apt-get install -y --no-install-recommends e2fsprogs libstdc++6 libatomic1
fi
test -s /out/rootfs/usr/local/share/vstab/guest-build.json
install -m 755 /guest-src/vstab-init /out/rootfs/sbin/vstab-init
install -m 644 /guest-src/supervisor.cjs /out/rootfs/usr/local/lib/vstab/supervisor.cjs
install -m 644 /guest-src/open-uri.cjs /out/rootfs/usr/local/lib/vstab/open-uri.cjs
install -m 644 /guest-src/disk-growth.cjs /out/rootfs/usr/local/lib/vstab/disk-growth.cjs
install -m 644 /guest-src/dns-relay.cjs /out/rootfs/usr/local/lib/vstab/dns-relay.cjs
install -m 755 /guest-src/xdg-open /out/rootfs/usr/local/bin/xdg-open
ln -sf /proc/self/mounts /out/rootfs/etc/mtab
if [ "${VSTAB_REFRESH_FILES:-0}" = 1 ]; then
  install -m 755 /inputs/android-files /out/rootfs/usr/local/bin/vstab-android-files
  sha256sum /inputs/android-files.c | cut -d ' ' -f1 > /out/files-client-source.sha256
fi
if [ "${VSTAB_BUILTIN_KERNEL:-0}" = 1 ]; then
  cp /inputs/kernel /out/kernel
  cp /inputs/kernel.config /out/kernel.config
  cp /inputs/kernel-version.txt /out/kernel-version.txt
  version=$(cat /out/kernel-version.txt)
  rm -f /out/initrd
  chown "$VSTAB_OUTPUT_UID:$VSTAB_OUTPUT_GID" /out/kernel /out/kernel.config /out/kernel-version.txt
fi
/build-node /guest-src/build-metadata.cjs /out/rootfs /out /inputs/code-server.tar.gz
/build-node /guest-src/deduplicate.cjs /out/rootfs/opt/code-server > /out/deduplication.json
rm -f /out/rootfs.ext4
truncate -s "${VSTAB_DISK_MIB:-2048}M" /out/rootfs.ext4
mkfs.ext4 -q -F -L vstab-linux -d /out/rootfs /out/rootfs.ext4
chown "$VSTAB_OUTPUT_UID:$VSTAB_OUTPUT_GID" /out/rootfs.ext4
