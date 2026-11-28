# Dependency pins

| Input | Pins |
| --- | --- |
| [server-lock.json](server-lock.json) | SSH server download and checksum |
| [android-qemu.lock.json](android-qemu.lock.json) | Signed Android QEMU host packages and checksums |
| [linux-guest.lock.json](linux-guest.lock.json) | Linux kernel, Debian snapshots and guest backend inputs |
| [bundle-spec.json](../workbench/bundle-spec.json) | Workbench version and download |

Build inventories and reports are generated here and ignored by Git.
APKs go in `dist/`; captures and test reports go in `artifacts/`.
