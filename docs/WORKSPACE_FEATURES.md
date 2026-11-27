# Workspace windows, shared files, persistent terminals and diagnostics

## One workspace by default

Normal startup opens one full-size Android workspace. Run **vstab: New Workspace
Window...** only when another project is wanted. Choose an Android project path
or an SSH workspace. Each new window is a separate Android task; Android split
screen or desktop windowing arranges them. The system divider controls their
size. Closing one window leaves the other running.

Up to four windows are supported. Each has its own SSH connection and a separate
loopback cookie host; the on-device backend is shared. Each window's editor,
terminals and extensions use its selected folder and backend. The app never
starts a second window automatically.

## Named persistent terminals

Run **vstab: New Persistent Terminal...**, enter a name and use the normal
terminal panel. A separate daemon owns the actual shell PTY. Reloading the
editor or reconnecting SSH reattaches to the same session; commands are not
rerun. The Explorer's **Persistent Terminals** view lists sessions. Commands
also attach, rename, refresh and stop them.

Closing a terminal tab detaches it. **Stop Persistent Terminal...** explicitly
ends its shell after confirmation. Output replay is bounded. A lost shell is
reported as ended rather than recreated. Android force-stop, reboot, process
killing and host shutdown can end processes; local background survival is not
guaranteed. See [terminal implementation and limits](../workbench/session-tools/README.md).

## Android files and shared projects

Run **vstab: Open Shared Folder...** to use Android's folder picker. The app
maintains a working copy in private storage so Git, language tools and terminals
have ordinary filesystem paths. `.git` history is retained. Saves and external
changes synchronize while the session is active; external changes are polled
every three seconds. Polling checks content, not only timestamps.

Run **vstab: Open Android File...**, or choose **vstab** in Android Files'
**Open with** menu, to edit a single document. Saving the working copy schedules
write-back to that same granted document URI. A status entry and warning report
failed delivery. Read-only or revoked provider permissions cannot be bypassed.

When both versions changed, the device's version is kept beside the local edit
as a `.device-conflict-...` file. Neither original is overwritten automatically.
Use **vstab: Review Shared-file Conflicts...** to compare and choose a version.
**Sync Shared Files Now** requests an immediate pass. A shared project selected
from an SSH window opens on the Android backend after the editor's normal
unsaved-work decision.

Limits: 20,000 entries, 256 MiB per file and 1 GiB per scan. Symbolic links stay
local. Incomplete provider listings do not authorize deletion. Android's document
API offers no atomic compare-and-write; the app keeps a complete private pre-save
recovery copy and attempts restoration if a provider write fails. Provider
changes racing inside the platform write itself cannot be made transactional.

## Extension compatibility

Actual recorded Node extension activation failures produce a named warning,
a concise reason and **Show details**. Android native-helper incompatibility
also offers **Connect to SSH Host...**. The status bar retains the failure count.
Use **vstab: Show Extension Compatibility Report** for redacted details.

Inactive extensions are not presumed broken. Warnings are deduplicated, log
reads are bounded, and this extension's own errors do not recursively warn.
Helpers failing silently after successful activation and failures isolated to
the browser extension host are outside this detector's coverage.

## Validation

Validate windows, terminal reconnection, shared-document write-back and extension
warnings through the ordinary app UI on the intended device. The
[isolated SSH fixture](../demo/README.md) supports remote editor and terminal
checks. Keep APK identities, captures and generated reports under ignored
`artifacts/`; source and unit checks alone do not establish device behavior.
