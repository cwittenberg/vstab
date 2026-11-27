# vstab Android application

The APK bundles the Code OSS workbench, an Android/Bionic Node runtime, Node extension host, Bash PTY, Git, ripgrep, npm/npx and native SSH transport. The local development environment starts without an SSH connection. Code OSS fills the app window; connection and workspace actions live inside the IDE.

Main HTML, client scripts, workers, grammars and icons come from the APK in local and SSH modes. Responses carry `X-Vstab-Source: apk` for inspection. Source identities and hashes are recorded in the generated manifests and About vstab; see [workbench provenance](../workbench/SOURCES.md).

## Build and install

Follow the [root build instructions](../README.md#build) to prepare pinned runtime/workbench assets, build the debug APK and run lint. Gradle fails if the generated payloads are missing. Native payloads include ARM64 devices and x86_64 emulators.

```sh
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```

Native executables and addons ship as extracted, read-only APK native libraries. Support files and backend JavaScript are extracted to private storage after archive hashes are checked. The app does not depend on a Termux installation, proot, CPU emulation or downloading executable payloads at startup. Debug builds enable WebView inspection; release builds do not.

## Local development

On first launch the app prepares its bundled runtime and probes Node child processes, a Bash PTY, SQLite, Argon2 and filesystem notifications before starting the local server. A failed check is shown explicitly. The runtime uses a private Unix socket behind an authenticated, app-owned loopback gateway. Its local origin stays stable across ordinary restarts, preserving workspace identity and editor storage.

The default project is a real directory under `files/workspaces/default` in private app storage. Node extensions, terminals and language servers use the ordinary filesystem. Home, extension installations and user settings survive APK updates; uninstalling removes private data.

Editing, installed tools and compatible extensions can work offline. Package/extension downloads, SSH connections and cloud AI services require their respective network connections. Desktop Linux native helpers need Android/Bionic-compatible replacements; a Node extension host does not make every desktop executable portable.

Explorer supports Android file upload, file clipboard paste and Download in local and SSH workspaces. See [Android file transfers](../docs/ANDROID_FILE_TRANSFERS.md) for actions and limits.

Use **vstab: Open Shared Folder...** or **vstab: Open Android File...** to grant Android document access. Shared documents use private working copies with write-back, change detection and conflict review. Up to four workspace windows can be opened explicitly; Android arranges them through split screen or desktop windowing. See [workspace features and limits](../docs/WORKSPACE_FEATURES.md).

The local runtime uses an Android foreground service with a Stop notification action. Named persistent terminals can survive editor reloads while their daemon survives. Android force-stop, reboot, memory pressure and device power policies can still end local processes.

## Native Remote SSH

Use the IDE's **Remote Explorer**, **Remote-SSH: Connect to Host...** command or remote indicator. Saved aliases and options come from the real on-device `HOME/.ssh/config`, editable in Code OSS. Revision checks protect concurrent config saves and unsupported options produce diagnostics. [SSH configuration](../docs/SSH_CONFIG.md) describes supported behavior.

Connections use masked password/passphrase prompts and fingerprint confirmation. Credentials travel through the dedicated first-party prompt channel and are not saved in SSH config or profile metadata. Import SSH Key uses Android's document picker, preserves the selected OpenSSH/PEM bytes and encryption, and writes a mode-0600 key under `HOME/.ssh/keys`.

Compare the first-connection SHA-256 fingerprint with the server's fingerprint. Accepted keys are stored privately; changed keys are blocked. The bundled script installs/starts a version-pinned remote server under the selected SSH account without sudo. Initial server setup needs network access on that host unless the verified archive is preloaded.

Remote Explorer manages SSH hosts. Connecting opens a remote window without a selected folder; use the standard **File → Open Folder...** action and Code OSS Explorer for remote filesystem navigation. Workspace changes synchronize native metadata and recent-folder history on the existing SSH session without another directory browser or a second workbench reload. Files, Node extensions and terminals execute on the SSH host through the native tunnel. Disconnect returns to the on-device environment.

Each workspace window has one active SSH connection and a separate loopback cookie host. The on-device backend is shared. Jump hosts, keyboard-interactive/MFA and guaranteed background survival remain outside the current implementation. Terminal GPU acceleration defaults to off for Android WebView; it can be changed in Code OSS Settings.

## Components and notices

- Android API 29 minimum; compile/target API 36.
- Gradle 8.13 and Android Gradle Plugin 8.13.2, with a pinned wrapper checksum.
- Maintained JSch `com.github.mwiede:jsch:2.28.8` and Bouncy Castle 1.87.
- Remote bootstrap generated from `server/bootstrap.sh` during the build.
- Launcher and About artwork retain the supplied [original SVG](../logo/vstab-original.svg).

Dependency licenses are bundled and available in About vstab. VSCodroid adaptation and attribution are documented in [the native runtime guide](../native-runtime/README.md). Private connection, trust and runtime data are excluded from Android backup and device transfer.

Build/lint results establish source/package checks; device tests and ordinary app journeys establish behavior. Check offline editing, save/restart, terminals, SSH reconnection and document write-back on the intended device. Emulator checks do not establish physical tablet qualification, and opening an AI panel does not establish authenticated cloud inference.
