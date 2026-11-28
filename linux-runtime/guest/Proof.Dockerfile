# Host-only cross-architecture proof tool, never packaged in the application.
FROM debian:trixie-slim@sha256:38a76d01668772e381ad2826d876627c89e7133e2f8a0f5d567306798b0f2a16
COPY debian-snapshot.sources /etc/apt/sources.list.d/debian.sources
RUN apt-get update && apt-get install -y --no-install-recommends qemu-system-arm && rm -rf /var/lib/apt/lists/*
