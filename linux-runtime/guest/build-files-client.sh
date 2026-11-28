#!/bin/bash
set -euo pipefail
mkdir -p /out/x86_64 /out/arm64-v8a
gcc -std=c11 -D_FILE_OFFSET_BITS=64 -O2 -Wall -Wextra -Werror /source/vstab-android-files.c \
  -o /out/x86_64/vstab-android-files $(pkg-config --cflags --libs fuse3 libcurl json-c) -lpthread
strip /out/x86_64/vstab-android-files
export PKG_CONFIG_LIBDIR=/usr/lib/aarch64-linux-gnu/pkgconfig:/usr/share/pkgconfig
aarch64-linux-gnu-gcc -std=c11 -D_FILE_OFFSET_BITS=64 -O2 -Wall -Wextra -Werror /source/vstab-android-files.c \
  -o /out/arm64-v8a/vstab-android-files $(pkg-config --cflags --libs fuse3 libcurl json-c) -lpthread -Wl,-rpath-link,/usr/lib/aarch64-linux-gnu
aarch64-linux-gnu-strip /out/arm64-v8a/vstab-android-files
sha256sum /source/vstab-android-files.c > /out/source.sha256
sha256sum /source/vstab-android-files.c | cut -d ' ' -f1 > /out/x86_64/vstab-android-files.source.sha256
cp /out/x86_64/vstab-android-files.source.sha256 /out/arm64-v8a/
chown -R "$VSTAB_OUTPUT_UID:$VSTAB_OUTPUT_GID" /out
