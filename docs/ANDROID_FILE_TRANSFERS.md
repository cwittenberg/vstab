# Android files

Available in local and SSH workspaces.

| Action | Steps |
| --- | --- |
| Upload | Right-click an Explorer folder → **Upload…** → select Android files |
| Paste | Copy files in Android Files → select an Explorer folder → **Ctrl+V** or **Paste from Android** |
| Download | Right-click an Explorer file → **Download…** → choose an Android destination |
| Shared project | Run **vstab: Open Shared Folder…** → select an Android folder |
| Open a file | Choose **VStab** from Android Files **Open with** |

## Limits

- Download and clipboard paste: 256 MiB per file.
- Clipboard paste: up to 100 files and 256 MiB total.
- Upload/download/paste transfer files, not folders.
- Read-only files and revoked permissions prevent save-back.
- In an SSH workspace, uploaded and pasted files go to the remote host.

Local terminals can use uploaded files and a shared folder's working directory.
SSH terminals can use the transferred remote copies.

[Verified APK and test evidence](../artifacts/transfers/REPORT.md)
