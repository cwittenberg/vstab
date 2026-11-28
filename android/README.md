# VStab on Android

Android 10+.

## Install

```sh
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```

## Signed release

Prepare the runtime and workbench assets first, following [the runtime build instructions](../native-runtime/README.md).
Set `ANDROID_HOME` and `JAVA_HOME`, then run:

```sh
python3 tools/build-android-release.py
```

The script builds and verifies a signed Play bundle and APK, runs release lint,
and writes `dist/vstab-0.1.3-4.aab`, `dist/vstab-0.1.3-4.apk` and checksums.
Upload the `.aab` to Google Play; use the `.apk` for direct installation.
Building does not publish the app.

Release builds use R8 and embed their mapping file and matching native function
symbol tables in the Play bundle. Versioned `.mapping.txt` and
`.native-debug-symbols.zip` copies are also retained in `dist/`.
The native coverage report is `android/app/build/reports/native-symbols-release.json`;
pre-stripped vendor libraries without matching unstripped builds remain uncovered.

Signing defaults to the owner-only configuration at
`~/.local/state/vstab/android-publishing/signing.json`. Override its path with
`VSTAB_SIGNING_CONFIG`. The JSON must identify `package` as `com.vstab` and contain
`alias`, an absolute `keystore` path, and an absolute `passwordFile` path.
Protect all three files with mode `600`; retain a secure backup of the upload key.
Alternatively, supply all four variables: `ANDROID_KEYSTORE`,
`ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, and `ANDROID_KEY_PASSWORD`.
Passwords are passed through the process environment, never command arguments.

Use `VSTAB_VERSION_CODE` and `VSTAB_VERSION_NAME` to override the default version
when releasing an update. The version code must exceed every prior Play upload.
Release builds require signing; debug builds keep their normal debug key.

The application ID registered in Google Play is `com.vstab`. Java sources and
generated classes retain the `app.vstab` namespace; the launcher component is
`com.vstab/app.vstab.MainActivity`.

## Local development

- Open the app to start a local project.
- Use the terminal for Node.js, Bash, Git and npm.
- Use **vstab: Open Shared Folder...** for an Android folder, or **Open with → VStab** for a file.
- Editing and installed local tools work offline. Downloads and cloud services need a connection.

## Remote SSH

1. Open **Remote Explorer** and add a host.
2. Connect, check its fingerprint, and enter a password or import an SSH key.
3. Use **File → Open Folder...** to select the remote project.

Disconnect to return to local work. Android force-stop or reboot ends local processes.

## Remote web previews

1. Start your web server in the SSH workspace, for example on port `3000`.
2. Open **Ports** in the bottom panel and choose **Add Port**.
3. Enter `3000`, then choose **Open in Browser** on that row.

The page opens in your Android browser through a local SSH tunnel. The **Forwarded Address** shows the actual Android address; if the preferred port is busy, VStab uses a free port. Use **Stop Forwarding** to close it. Forwarded ports remain available while you use the browser and close when SSH disconnects or the workspace window closes.
