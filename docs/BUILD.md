# Build and test

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

See [Android setup](../android/README.md), [runtime packaging](../native-runtime/README.md)
and [workbench provenance](../workbench/SOURCES.md) for component details.

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
[The SSH fixture](../demo/README.md) provides an isolated backend for editor, save
and terminal checks. Verify ordinary app interactions on the intended device,
including offline save/restart, SSH reconnect and Android document write-back.
