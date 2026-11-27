# vstab

Code OSS on Android tablets, with an on-device Node extension host, local
terminal and built-in Remote SSH. The APK bundles the editor and native runtime,
so local projects can open without an SSH server or hosted editor.

Android System WebView renders the Code OSS interface. Local files, extensions,
Git and terminals use the Android/Bionic backend; SSH workspaces use a matching
Linux backend through native SSH. The app also supports Android file
upload/download, shared-document save-back, optional workspace windows, named
persistent terminals and extension activation diagnostics.

This is an early implementation. Physical tablet behavior, arbitrary desktop
native extensions and authenticated AI workflows require separate validation.
Android force-stop, reboot and device power policies can end local processes.

## Source layout

| Directory | Contents |
| --- | --- |
| `android/` | Android app, native SSH, file integration and tests |
| `workbench/` | Workbench pins, embedding patch and first-party extensions |
| `native-runtime/` | Android runtime adaptations, probes and vendor notices |
| `server/` | Pinned Linux SSH backend bootstrap |
| `scripts/` | Dependency preparation and APK asset packaging |
| `tools/` | Android build, emulator and verification tools |
| `demo/` | Isolated Linux SSH test fixture |
| `release/` | Dependency locks; generated reports are ignored |

Downloads, SDKs, credentials and generated assets stay under ignored `.local/`.
APKs, captures and reports stay in ignored build, `dist/` or `artifacts/`
directories. Source pins, lockfiles, patches and license notices are retained.

## Build

Use a Linux x86_64 host with Python 3.12+, Node.js 24 and npm, Git, curl,
`dpkg-deb`, `readelf`, JDK 17, Android SDK platform/build-tools 36 and Android NDK
r28. Allow network access and sufficient disk space for the pinned upstream
sources, SDKs and native payloads. Run these commands from the repository root:

```sh
export JAVA_HOME=/path/to/jdk17
export ANDROID_HOME=/path/to/android-sdk
export ANDROID_NDK_HOME=/path/to/android-ndk
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"

npm ci --prefix workbench/local --ignore-scripts --no-audit --no-fund
./scripts/prepare-web-extensions.sh
python3 scripts/prepare-android-runtime.py
python3 scripts/package-android-backend.py
python3 scripts/build-android-addons.py --ndk "$ANDROID_NDK_HOME"
python3 scripts/package-android-backend.py
python3 scripts/package-workbench.py
./tools/build-android.sh :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
```

The first backend packaging step prepares the addon sources; the second includes
the completed native outputs. Both ARM64 device and x86_64 emulator payloads are
packaged. The debug APK is
`android/app/build/outputs/apk/debug/app-debug.apk`.

See [Android setup](android/README.md), [runtime packaging](native-runtime/README.md)
and [workbench provenance](workbench/SOURCES.md) for component details.

## Checks

After preparing the dependencies above:

```sh
node --test workbench/controls/*.test.* workbench/session-tools/*.test.cjs \
  workbench/compatibility/test/*.test.cjs
python3 scripts/test-package-server-assets.py
./tools/build-android.sh :app:testDebugUnitTest :app:lintDebug
```

Persistent-terminal integration tests require a real compatible `node-pty`
binding and explicitly skip when unavailable. Android instrumentation is a
separate check against an installed app/test APK pair; use
`tools/run-android-instrumentation.py` to require a JUnit success result.
[The SSH fixture](demo/README.md) provides an isolated backend for editor, save
and terminal checks. Verify ordinary app interactions on the intended device,
including offline save/restart, SSH reconnect and Android document write-back.

## Documentation and licenses

- [Architecture](docs/ARCHITECTURE.md)
- [SSH configuration](docs/SSH_CONFIG.md) and [control protocol](docs/SSH_CONTROL_PROTOCOL.md)
- [Android file transfers](docs/ANDROID_FILE_TRANSFERS.md)
- [Windows, shared files, terminals and diagnostics](docs/WORKSPACE_FEATURES.md)
- [Codex and Claude compatibility](docs/CODEX_COMPATIBILITY.md)

Extensions must support their selected backend. Linux native helpers do not
automatically run on Android/Bionic. Codex and Claude Code are installed
separately through the Extensions view; provider sign-in and completed model
requests need their own validation.

The workbench derives from Microsoft Code OSS through pinned code-server
sources. Microsoft's proprietary Remote-SSH extension and VS Code server
distribution are separate products. Retain the existing third-party notices
and satisfy the bundled components' license and corresponding-source obligations
when distributing binaries. [Source provenance](workbench/SOURCES.md) records
the workbench inputs.
