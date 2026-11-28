# Build VStab

Requirements: Linux x86_64, Python 3.12+, Node.js 24, npm, Git, curl,
`dpkg-deb`, `readelf`, JDK 17, Android SDK 36 and NDK r28.

Run from the repository root:

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

APK: `android/app/build/outputs/apk/debug/app-debug.apk`

```sh
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```

## Tests

```sh
node --test workbench/controls/*.test.* workbench/session-tools/*.test.cjs \
  workbench/compatibility/test/*.test.cjs
python3 scripts/test-package-server-assets.py
./tools/build-android.sh :app:testDebugUnitTest :app:lintDebug
```

Terminal integration tests need a compatible `node-pty` binding.
