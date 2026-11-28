# Android QEMU host

`python3 scripts/build-android-qemu.py --resolve-lock` resolves and verifies the
signed Termux package indexes, pins the required archives, and prepares both
Android host architectures. Later builds use the checked-in lock without
resolving new package versions. `--abi x86_64` or `--abi arm64-v8a` selects one.
See [source provenance](SOURCES.md) for the matching official Termux recipe and
verified upstream QEMU source archive, including the limits of that record.

The output is generic VM infrastructure. It contains no extension names, engines,
extension patches, guest executable substitutions, or platform overrides.

Outputs under `.local/linux-runtime/`:

| Path | Purpose |
| --- | --- |
| `jniLibs/<ABI>/libqemu-system.so` | Android PIE executable installed read-only by the APK package manager |
| `assets/linux-runtime/host-<ABI>.zip` | Exact ELF shared-library closure, selected machine boot resources, and notices |
| `assets/linux-runtime/host-manifest.json` | Source lock, executable, archive and library SHA-256 records and launch contract |

Extract the architecture's host archive into a private versioned directory,
set `LD_LIBRARY_PATH` to its `lib` subdirectory, and pass `-L` with its
`share/qemu` subdirectory. Always provide explicit guest storage, networking,
console, QMP and machine arguments; use TCG, `-no-user-config`, `-nodefaults` and
`-display none`. Never inherit Termux's preload or environment. The host does
not need Termux installed and must never execute guest code as Android code.

The packager validates Android's ELF interpreter, host architecture, 16 KiB load
alignment, and complete `DT_NEEDED` closure. These static checks do not prove
Android process launch or Linux guest boot. Those require the installed APK,
ordinary application UID, and real guest kernel evidence recorded separately.

The initial upstream build retains optional hardware/audio dependencies and is
a functional baseline. Their measured size is reported rather than hidden.
A smaller generic NDK build may replace it after equivalent guest behavior is
proved. Do not remove libraries just because a particular extension does not
use them.
