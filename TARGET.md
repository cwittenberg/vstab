# vstab implementation target

Updated November 27, 2026 from the user's implementation clarifications.

**Build an installable Android port of the real VS Code workbench, with native Remote SSH included as a normal IDE capability.** The application must be useful before selecting an SSH server. It is not an SSH-only client or a launcher for a browser IDE.

The clarified native baseline is VSCodroid's architecture: a bundled Android/Bionic Node.js runtime and Code OSS backend on the device, providing a local Node extension host and PTY terminal. The earlier browser-worker-only local prototype is useful evidence for UI/SSH integration, but does not complete this target.

## Required behavior

1. Installing and opening the APK starts the bundled Code OSS workbench, built from the Microsoft VS Code source lineage, without internet access or an SSH server. Show the exact upstream and downstream source identities.
2. Open and edit local project files, save them and preserve them across app restarts. Keep local storage and any Android file permissions explicit.
3. Run local terminal processes, tools and compatible Node extensions on Android itself, offline, without an SSH host, Termux app, proot, guest OS or CPU emulation. Native libraries/executables must be Android-compatible and packaged through supported Android mechanisms.
4. Offer built-in SSH host management and Connect to Host from inside the IDE. It must not require an extension download, Termux, a separate SSH app, a vstab cloud account or a manually configured web IDE.
5. Authenticate to an ordinary SSH server, verify its host key and automatically install/start the matched remote agent under the selected user account.
6. Open the remote project in the same VS Code workbench. Files, Git, terminal processes, tools and workspace extensions must use that remote account and folder.
7. Preserve the familiar desktop Remote SSH journey: connect, open a remote folder, edit/save, use an integrated terminal, manage extensions for that workspace, disconnect and return to local work.
8. Support compatible official OpenAI Codex and Claude Code extensions through a real extension host. Demonstrate Codex installation and graphical activation inside the Android app; exercise authenticated agent operations when provider sign-in is available.
9. Use an edge-to-edge Code OSS workbench without an extra outer native toolbar/frame. Put connection actions in the IDE; retain the supplied logo for application identity.

## Evidence required

- APK build and Android lint results, component/source pins and APK hash.
- Actual Android emulator or device screenshots/video, labeled with device type.
- Local file edits saved through the Code OSS UI and preserved after restart.
- Actual airplane-mode/network-disabled startup, local editing/save/restart, search and bundled language-feature checks. No website, remote editor or guest operating system may be required for these local flows.
- On-device Android Node process, a local Node extension host, and a real local PTY command demonstrated while device networking is disabled.
- Native SSH login, host fingerprint verification, remote file editing and integrated terminal execution.
- Independent SSH inspection of the resulting remote file bytes, Git diff and program output.
- Actual extension installation, activation and graphical panel in the Android app; distinguish these from provider authentication and a completed model request.
- VS Code dark theme exercised in local and remote workspaces, including readable terminals, AI panels and saved theme state.
- Evidence that the workbench HTML/JS/assets were served from the APK, while remote services used SSH.

## Implementation boundaries

The existing Code OSS interface uses web technologies. Android System WebView renders that real interface from APK assets offline, as VSCodroid also does. It is not a connection to vscode.dev. The local backend must be a native Android Node/PTY environment; a remote Linux account supplies remote workspace processes when SSH is selected. Extensions are installed through the normal Extensions view, not prebundled Codex/Claude packages. Native module/platform compatibility must be reported honestly; do not claim exact desktop parity without testing it.

The earlier SSH-first plan is superseded wherever it describes an SSH connection as mandatory to open the editor or puts all local editing outside the first release. Existing implementation is a prototype until this target's ordinary app journeys are demonstrated.
