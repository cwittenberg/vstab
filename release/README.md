# Dependency pins

| Input | Pins |
| --- | --- |
| [server-lock.json](server-lock.json) | SSH server download and checksum |
| [android-runtime.lock.json](android-runtime.lock.json) | Android package versions and checksums |
| [bundle-spec.json](../workbench/bundle-spec.json) | Workbench version and download |
| [pins.json](../native-runtime/server-patches/pins.json) | Android patch inputs |

Build inventories and reports are generated here and ignored by Git.
APKs go in `dist/`; captures and test reports go in `artifacts/`.
