# vstab

Code OSS on Android tablets, with an on-device Node extension host, local terminal and built-in Remote SSH. The Android app bundles the workbench and native runtime; opening a local workspace does not require an SSH server or a hosted editor.

The product is **vstab**. The development directory may still be named `vspad`.

This is an early implementation. Source and build checks do not establish physical tablet qualification or compatibility with every desktop extension. See the [implementation target](TARGET.md) and [verification procedure](docs/DEMONSTRATION.md) for the required app journeys and remaining proof boundaries.

## Source layout

| Directory | Contents |
| --- | --- |
| `android/` | Android application, native SSH transport, workspace/document integration and tests |
| `workbench/` | Workbench source pins, embedding patches and first-party extensions |
| `native-runtime/` | Android/Bionic runtime adaptations, native probes and third-party notices |
| `server/` | Pinned Linux SSH backend bootstrap |
| `scripts/` | Dependency preparation and APK asset packaging |
| `tools/` | Android build, emulator and UI verification tools |
| `demo/` | Isolated Linux SSH test fixture |
| `release/` | Source dependency locks and generated local reports |

Downloaded dependencies, SDKs, credentials, generated assets, APKs and test captures belong in ignored local/build directories. Keep the source pins, lockfiles, patches and license notices needed to reproduce the app.

## Build

Use a Linux x86_64 host with Python 3.12+, Node.js 24 and npm, Git, curl, `dpkg-deb`, `readelf`, JDK 17, Android SDK platform/build-tools 36 and Android NDK r28. Building downloads the pinned upstream dependencies; the resulting local environment is bundled in the APK.

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

The debug APK is `android/app/build/outputs/apk/debug/app-debug.apk`. Runtime preparation produces ARM64 device and x86_64 emulator payloads. See [Android behavior and setup](android/README.md), [runtime packaging](native-runtime/README.md) and [workbench provenance](workbench/SOURCES.md).

## Focused checks

After preparing the workbench above:

```sh
node --test workbench/local/bridge.test.mjs workbench/controls/*.test.* \
  workbench/session-tools/*.test.cjs workbench/compatibility/test/*.test.cjs
python3 scripts/test-package-server-assets.py
./tools/build-android.sh :app:testDebugUnitTest :app:lintDebug
```

Persistent-terminal integration tests require a compatible real `node-pty` binding and explicitly skip when one is unavailable. Android instrumentation and normal editor interactions are separate checks; follow [the verification procedure](docs/DEMONSTRATION.md) and [SSH fixture instructions](demo/README.md).

## Architecture and limits

Android WebView renders the bundled Code OSS interface. Local files, Node extensions and terminals use the Android-native backend. SSH workspaces use a matching Linux backend through native SSH. There is no Termux app, guest operating system or CPU emulator on the device. See the [architecture](docs/VSTAB_PLAN.md) and [workspace features](docs/WORKSPACE_FEATURES.md).

Extensions must support their selected runtime. Desktop Linux native helpers do not automatically run on Android/Bionic. Provider-authenticated Codex/Claude operations, physical Xiaomi behavior and advanced SSH configurations need their own qualification. Android force-stop and reboot can end local processes.

The workbench derives from Microsoft Code OSS through pinned code-server sources. Microsoft's proprietary Remote-SSH extension and VS Code server distribution are separate products. Existing third-party license notices are retained with their components; binary distribution must also satisfy the bundled runtime components' license requirements. [Product requirements](PRODUCT.md) and [source provenance](workbench/SOURCES.md) document the intended scope and dependencies.
