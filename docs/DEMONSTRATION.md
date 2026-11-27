# Build and app verification

This procedure records evidence for a particular APK. The [implementation target](../TARGET.md) and [workspace acceptance target](WORKSPACE_FEATURES_TARGET.md) define the required behavior. Historical prototype captures and generated qualification reports are local artifacts, not proof that every later source revision or build behaves identically.

## Prepare a candidate

1. Follow the [root build instructions](../README.md#build), including the native runtime and bundled workbench preparation.
2. Record the source commit, build/lint and unit-test results, APK SHA-256, Android model/API, CPU architecture and System WebView version. Keep package/source identities from the generated workbench and runtime manifests with the evidence.
3. Install both `android/app/build/outputs/apk/debug/app-debug.apk` and `android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk` on the chosen device. Debug WebView inspection is available for development; release behavior requires its own check.

Run Android tests against an explicitly selected device. For example:

```sh
export VSTAB_ANDROID_SERIAL=emulator-5580
adb -s "$VSTAB_ANDROID_SERIAL" install -r android/app/build/outputs/apk/debug/app-debug.apk
adb -s "$VSTAB_ANDROID_SERIAL" install -r android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
python3 tools/run-android-instrumentation.py \
  --classes app.vstab.NativeRuntimeBackendTest,app.vstab.SharedDocumentManagerTest \
  --output artifacts/instrumentation.txt
```

The instrumentation runner requires an actual JUnit success marker. Include the remaining filesystem, SSH-config and host-store test classes when qualifying changes to those boundaries. A passed runtime probe establishes its exercised native checks; it does not replace the ordinary workbench journeys below.

## Local and offline workflow

Disable device networking and cold-start the app without an SSH account. Confirm that the bundled Code OSS workbench opens a local project. Edit and save a file through the editor, restart the app and independently inspect the saved bytes. Exercise Quick Open, Find in Files and a bundled language feature.

Run a command in the actual integrated local terminal and activate a compatible Node extension. Record that the backend is Android-native and that terminal/extension processes run on the device. Keep APK-asset response provenance alongside the UI evidence. A host-side build, mocked extension host or browser-only prototype is insufficient for this journey.

## Remote SSH workflow

Start the controlled Linux SSH fixture using [demo/README.md](../demo/README.md), or use a separate reachable test account. Generated fixture credentials remain under ignored `.local/demo/`; bootstrap responses may contain passwords and must stay private.

Connect using the IDE's Remote Explorer or **Remote-SSH: Connect to Host...** command. Independently verify the server's SHA-256 fingerprint before accepting it. Open the remote project, edit/save a file and run its program from the integrated terminal. Reconnect and return to the local workspace through the IDE.

For the controlled fixture, inspect the resulting remote bytes, Git diff, program output and installed extensions independently:

```sh
python3 demo/verify.py --output artifacts/remote-state.json
```

Pair this inspection with Android screenshots/video and session details. SSH inspection alone does not demonstrate that the Android editor performed the edit.

## Workspace and extension workflows

Follow [the workspace acceptance target](WORKSPACE_FEATURES_TARGET.md) to exercise independent windows, real persistent PTYs, shared-folder synchronization, Android **Open with**, conflict recovery and observed extension-failure diagnostics. Check actual provider bytes after a save; a writable private copy does not establish shared-document write-back.

Install Codex and Claude Code through the normal Extensions UI and record installation, host activation and graphical panel behavior separately. Authenticate with the user's chosen provider account only for a requested provider workflow. An installed extension, started helper or visible panel does not establish a completed AI request or compatibility with every native helper.

## Reporting results

Store screenshots, transcripts, package inventories and verification output under ignored `artifacts/`. Keep secrets and raw bootstrap/authentication responses in ignored private state. Report each journey as passed, failed, skipped or untested, tied to the exact candidate APK.

Emulator results establish behavior on that emulator. Physical Xiaomi/ARM64 qualification, provider-authenticated operations and release-signed builds need their own evidence. Android force-stop, reboot and background process limits must remain explicit in persistent-terminal results.
