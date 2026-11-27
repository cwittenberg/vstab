# Android files in Explorer

Explorer file transfers work on the Android backend and in an SSH workspace. The source or destination on Android is chosen through the system document picker; SSH transfers use the currently connected workspace.

- **Upload…**: right-click the destination folder in Explorer, choose Upload, then select Android files. Multi-selection is supported.
- **Paste**: copy files in Android Files, focus the destination in Explorer, and press Ctrl+V. The Explorer context menu also offers **Paste from Android**. Copy/Cut within Explorer keeps its normal behavior. Editor and terminal text paste keep their normal behavior.
- **Download…**: right-click a file in Explorer and choose Download. Select an Android destination and filename in the system save picker. Success is reported after the destination bytes have been verified.

Downloads and clipboard transfers support individual files up to 256 MiB. Clipboard paste is limited to 256 MiB combined and 100 selected files. Files are transferred in bounded chunks. Folder transfer through these actions is outside this implementation; use **vstab: Open Shared Folder…** for a local Android project.

Downloads are staged privately before the destination is opened. Cancellation releases staging. A failed provider write attempts to restore and verify the original document, retaining a recovery copy if restoration fails. The app cannot bypass revoked or read-only Android document permissions.

A local terminal can use uploaded files or a shared folder's synchronized working directory. An SSH terminal uses remote files after upload/paste; Android storage is not automatically mounted on the server.
