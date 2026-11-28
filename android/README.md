# VStab on Android

Android 10+.

## Build and install

[Build instructions](../docs/BUILD.md)

```sh
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```

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

[SSH settings](../docs/SSH_CONFIG.md) · [Files](../docs/ANDROID_FILE_TRANSFERS.md) · [Windows and terminals](../docs/WORKSPACE_FEATURES.md)
