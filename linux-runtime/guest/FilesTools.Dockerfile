# Host-only compiler for both guest architectures, never shipped in the app.
FROM debian:trixie-slim@sha256:38a76d01668772e381ad2826d876627c89e7133e2f8a0f5d567306798b0f2a16
COPY debian-snapshot.sources /etc/apt/sources.list.d/debian.sources
RUN dpkg --add-architecture arm64 && apt-get update && apt-get install -y --no-install-recommends \
    gcc gcc-aarch64-linux-gnu pkg-config binutils \
    libfuse3-dev libcurl4-openssl-dev libjson-c-dev \
    libfuse3-dev:arm64 libcurl4-openssl-dev:arm64 libjson-c-dev:arm64 \
    e2fsprogs libstdc++6 libatomic1 && rm -rf /var/lib/apt/lists/*
