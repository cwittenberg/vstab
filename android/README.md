# VStab on Android

Android 10+.

## Install

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

## Remote web previews

1. Start your web server in the SSH workspace, for example on port `3000`.
2. Open **Ports** in the bottom panel and choose **Add Port**.
3. Enter `3000`, then choose **Open in Browser** on that row.

The page opens in your Android browser through a local SSH tunnel. The **Forwarded Address** shows the actual Android address; if the preferred port is busy, VStab uses a free port. Use **Stop Forwarding** to close it. Forwarded ports remain available while you use the browser and close when SSH disconnects or the workspace window closes.
