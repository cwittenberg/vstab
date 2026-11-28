#!/bin/bash
# Runs inside a pinned, architecture-matched Debian container.
set -euo pipefail
export DEBIAN_FRONTEND=noninteractive
printf '#!/bin/sh\nexit 101\n' > /usr/sbin/policy-rc.d
chmod 755 /usr/sbin/policy-rc.d
rm -f /etc/apt/apt.conf.d/docker-clean
cp /etc/apt/sources.list.d/debian.sources /tmp/vstab-default-apt.sources
cp /guest-src/debian-snapshot.sources /etc/apt/sources.list.d/debian.sources
apt-get update
kernel_packages=()
if [ "${VSTAB_BUILTIN_KERNEL:-0}" != 1 ]; then
  kernel_packages=(kmod initramfs-tools "$VSTAB_KERNEL_PACKAGE")
fi
apt-get install -y --no-install-recommends \
  ca-certificates busybox-static openssh-client openssh-server git curl iproute2 \
  procps e2fsprogs fuse3 libfuse3-4 libcurl4t64 libjson-c5 \
  libstdc++6 libatomic1 xz-utils unzip gzip util-linux sudo "${kernel_packages[@]}"
mkdir -p /opt/code-server /usr/local/lib/vstab /usr/local/share/vstab /out
tar -xzf /inputs/code-server.tar.gz --strip-components=1 -C /opt/code-server
# This optional Code OSS builtin contains a desktop vendor engine. It is not
# required by the editor; users install chosen AI extensions from the gallery.
if [ -d /opt/code-server/lib/vscode/extensions/copilot ]; then
  du -sb /opt/code-server/lib/vscode/extensions/copilot > /out/removed-optional-copilot.txt
  rm -rf /opt/code-server/lib/vscode/extensions/copilot
fi
# The paired editor ships an optional vendor SDK outside the optional extension.
# Remove the complete optional component, including its native engine payloads.
for sdk in /opt/code-server/lib/vscode/node_modules/@github/copilot-sdk-*; do
  [ -e "$sdk" ] || continue
  du -sb "$sdk" >> /out/removed-optional-copilot.txt
  rm -rf "$sdk"
done
install -m 755 /guest-src/vstab-init /sbin/vstab-init
install -m 644 /guest-src/supervisor.cjs /usr/local/lib/vstab/supervisor.cjs
install -m 644 /guest-src/open-uri.cjs /usr/local/lib/vstab/open-uri.cjs
install -m 644 /guest-src/disk-growth.cjs /usr/local/lib/vstab/disk-growth.cjs
install -m 644 /guest-src/dns-relay.cjs /usr/local/lib/vstab/dns-relay.cjs
install -m 755 /guest-src/xdg-open /usr/local/bin/xdg-open
ln -sf /opt/code-server/lib/node /usr/local/bin/node
useradd --create-home --uid 1000 --shell /bin/bash developer
printf 'developer ALL=(ALL:ALL) NOPASSWD: ALL\n' > /etc/sudoers.d/vstab-developer
chmod 440 /etc/sudoers.d/vstab-developer
mkdir -p /home/developer/workspaces /run/sshd /mnt/android
chown -R 1000:1000 /home/developer
printf 'user_allow_other\n' > /etc/fuse.conf
printf 'vstab-linux\n' > /etc/hostname
printf '127.0.0.1 localhost\n127.0.1.1 vstab-linux\n::1 localhost ip6-localhost\n' > /etc/hosts
ln -sf /proc/self/mounts /etc/mtab
if [ -f /inputs/android-files.c ]; then
  apt-get install -y --no-install-recommends gcc pkg-config libfuse3-dev libcurl4-openssl-dev libjson-c-dev
  gcc -std=c11 -D_FILE_OFFSET_BITS=64 -O2 -Wall -Wextra /inputs/android-files.c -o /usr/local/bin/vstab-android-files $(pkg-config --cflags --libs fuse3 libcurl json-c) -lpthread
  strip /usr/local/bin/vstab-android-files
  sha256sum /inputs/android-files.c | cut -d ' ' -f1 > /out/files-client-source.sha256
  apt-get purge -y gcc pkg-config libfuse3-dev libcurl4-openssl-dev libjson-c-dev
  apt-get autoremove -y
fi
if [ "${VSTAB_BUILTIN_KERNEL:-0}" = 1 ]; then
  cp /inputs/kernel /out/kernel
  cp /inputs/kernel.config /out/kernel.config
  cp /inputs/kernel-version.txt /out/kernel-version.txt
  version=$(cat /out/kernel-version.txt)
  rm -f /out/initrd
else
kernel=$(find /boot -maxdepth 1 -name 'vmlinuz-*' -printf '%f\n' | sort -V | tail -1)
version=${kernel#vmlinuz-}
printf 'MODULES=list\nCOMPRESS=gzip\n' > /etc/initramfs-tools/conf.d/vstab
printf 'virtio\nvirtio_pci\nvirtio_blk\nvirtio_net\next4\n9p\n9pnet_virtio\nfuse\n' > /etc/initramfs-tools/modules
update-initramfs -u -k "$version"
cp "/boot/$kernel" /out/kernel
cp "/boot/initrd.img-$version" /out/initrd
cp "/boot/config-$version" /out/kernel.config
printf '%s\n' "$version" > /out/kernel-version.txt
fi
dpkg-query -W -f='${Package}\t${Version}\t${Architecture}\n' > /out/packages.tsv
find /var/cache/apt/archives -name '*.deb' -type f -exec sha256sum '{}' + > /out/debian-archives.sha256
/opt/code-server/lib/node --version > /out/node-version.txt
/opt/code-server/bin/code-server --version > /out/code-server-version.txt
/opt/code-server/lib/node /guest-src/build-metadata.cjs / /out /inputs/code-server.tar.gz
# Host keys and machine identity belong to each installed persistent guest.
rm -f /etc/ssh/ssh_host_* /etc/machine-id
touch /etc/machine-id
apt-get clean
cp /tmp/vstab-default-apt.sources /etc/apt/sources.list.d/debian.sources
rm -rf /var/lib/apt/lists/* /tmp/* /var/tmp/*
rm -rf /out/rootfs
mkdir -p /out/rootfs
for item in bin boot etc home lib lib64 opt root sbin usr var; do
  [ ! -e "/$item" ] && [ ! -L "/$item" ] && continue
  cp -a "/$item" /out/rootfs/
done
mkdir -p /out/rootfs/{dev,proc,sys,run,tmp,mnt/android}
chmod 1777 /out/rootfs/tmp
rm -rf /out/rootfs/boot/* /out/rootfs/var/cache/apt/* /out/rootfs/var/log/*
/opt/code-server/lib/node /guest-src/deduplicate.cjs /out/rootfs/opt/code-server > /out/deduplication.json
# Retain only modules used by the virtual platform or the generic file bridge.
# The boot initramfs already contains the exact root-device driver closure.
module_keep=/out/modules.keep
: > "$module_keep"
if [ -d "/out/rootfs/lib/modules/$version" ]; then
for module in virtio virtio_pci virtio_mmio virtio_blk virtio_net virtio_rng virtio_console 9p 9pnet_virtio fuse overlay ext4; do
  modprobe --set-version "$version" --show-depends "$module" 2>/dev/null | sed -n 's/^insmod //p' >> "$module_keep" || true
done
sort -u "$module_keep" -o "$module_keep"
while IFS= read -r module; do
  if ! grep -Fxq "${module#/out/rootfs}" "$module_keep"; then rm -f "$module"; fi
done < <(find "/out/rootfs/lib/modules/$version" -type f \( -name '*.ko' -o -name '*.ko.xz' -o -name '*.ko.zst' \))
depmod --basedir /out/rootfs "$version"
fi
disk_mib=${VSTAB_DISK_MIB:-2048}
# A same-size truncate preserves old free blocks. Always create a fresh sparse
# image so removed optional components cannot remain in unallocated disk bytes.
rm -f /out/rootfs.ext4
truncate -s "${disk_mib}M" /out/rootfs.ext4
mkfs.ext4 -q -F -L vstab-linux -d /out/rootfs /out/rootfs.ext4
chown "$VSTAB_OUTPUT_UID:$VSTAB_OUTPUT_GID" /out/kernel /out/kernel.config /out/*version.txt /out/packages.tsv /out/debian-archives.sha256 /out/rootfs.ext4 /out/modules.keep
[ ! -f /out/initrd ] || chown "$VSTAB_OUTPUT_UID:$VSTAB_OUTPUT_GID" /out/initrd
echo 'VStab Linux guest filesystem and boot assets assembled.'
