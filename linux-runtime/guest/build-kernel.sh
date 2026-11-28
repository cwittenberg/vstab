#!/bin/bash
# Build-only container. No host package or kernel changes.
set -euo pipefail
export DEBIAN_FRONTEND=noninteractive
cp /guest-src/debian-snapshot.sources /etc/apt/sources.list.d/debian.sources
apt-get update
apt-get install -y --no-install-recommends gcc make bc bison flex libssl-dev libelf-dev xz-utils cpio python3
if [ "$VSTAB_KERNEL_ARCH" = arm64 ]; then
  apt-get install -y --no-install-recommends gcc-aarch64-linux-gnu
  export ARCH=arm64 CROSS_COMPILE=aarch64-linux-gnu-
else
  export ARCH=x86_64
fi
if [ ! -f /out/source/Makefile ]; then
  mkdir -p /out/deb /out/source
  dpkg-deb -x /inputs/linux-source.deb /out/deb
  tar -xJf /out/deb/usr/src/linux-source-6.12.tar.xz --strip-components=1 -C /out/source
  rm -rf /out/deb
fi
mkdir -p /out/build
cd /out/source
make O=/out/build allnoconfig KCONFIG_ALLCONFIG=/guest-src/kernel.config
make O=/out/build -j32
if [ "$ARCH" = arm64 ]; then cp /out/build/arch/arm64/boot/Image /out/kernel; else cp /out/build/arch/x86/boot/bzImage /out/kernel; fi
cp /out/build/.config /out/kernel.config
make O=/out/build -s kernelrelease > /out/kernel-version.txt
chown "$VSTAB_OUTPUT_UID:$VSTAB_OUTPUT_GID" /out/kernel /out/kernel.config /out/kernel-version.txt
echo 'VStab built-in Linux kernel built.'
