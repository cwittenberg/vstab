# vstab

Code OSS for Android tablets. Local Node.js development and built-in Remote SSH.

## Screenshots

**Local editor, Node.js and Git — light theme**

![Local JavaScript project with native Node.js and Git output](screenshots/07-light-local-editor-and-terminal.png)

**SSH and Android workspaces — split screen**

![Independent SSH Python and Android JavaScript workspaces with terminals](screenshots/03-independent-split-screen-workspaces.png)

[All nine screenshots](screenshots/README.md) · Android emulator · 1920 × 1080

## Features

| Feature | Status | Details |
| --- | --- | --- |
| Offline editing | Included | Bundled Code OSS editor; Android 10+ |
| Local tools | Included | Native Node.js, Bash, Git, npm/npx and ripgrep |
| Remote SSH | Included | Linux x86_64/ARM64; password/key login; host-key verification |
| Android files and folders | Included | Open with, shared folders, save-back and Git history |
| File upload, download and clipboard paste | Included | Android and SSH workspaces; files only; download/paste: 256 MiB per file |
| Independent workspace windows | Included | Up to four; Android split screen or desktop windowing |
| Named persistent terminals | Included | Reattach after editor reload or SSH reconnect while the daemon runs |
| Extensions | Included | Node extension host; native helpers must match the backend |
| Codex and Claude Code | Separate install | Linux x64 SSH extensions; authenticated AI workflows unverified |
| SSH jump hosts, ProxyCommand, Match and keyboard-interactive | Not supported | See [SSH configuration](docs/SSH_CONFIG.md) |
| Desktop Linux/glibc helpers on Android | Not supported | Requires Android/Bionic builds or an SSH workspace |
| Local processes surviving Android force-stop or reboot | Not supported | Android can terminate local processes |

## Build

[Build and tests](docs/BUILD.md) · [Android setup](android/README.md) · [Architecture](docs/ARCHITECTURE.md)

[File transfers](docs/ANDROID_FILE_TRANSFERS.md) · [Workspace features](docs/WORKSPACE_FEATURES.md) · [Extension compatibility](docs/CODEX_COMPATIBILITY.md)

[Source provenance and licenses](workbench/SOURCES.md)
