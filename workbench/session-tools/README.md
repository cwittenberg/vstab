# Persistent project terminals

This first-party workspace extension owns named shells through a separate Node
process and the backend's existing `node-pty` binding. The editor's terminal tabs
are clients of those PTYs. Closing a tab detaches; **Stop Persistent Terminal**
ends its shell. A reload/reconnect attaches to the same session and replays bounded
output. It never replays terminal input or recreates a lost shell automatically.

Install `extension/` as `lib/vscode/extensions/vstab-session-tools` in both the
Android backend and the matched Linux SSH backend. No browser-worker build and no
new native dependency is needed. `vscode.env.appRoot/node_modules/node-pty` must
contain a binding compatible with the selected backend. Android uses the same
APK-native binding as its ordinary integrated terminals.

Commands in the normal palette:

- **vstab: New Persistent Terminal...** (`vstab.sessions.create`)
- **vstab: Attach to Persistent Terminal...** (`vstab.sessions.attach`)
- **vstab: Rename Persistent Terminal...** (`vstab.sessions.rename`)
- **vstab: Stop Persistent Terminal...** (`vstab.sessions.stop`)
- **vstab: Refresh Persistent Terminals** (`vstab.sessions.refresh`)

The Explorer's **Persistent Terminals** view shows names and attachment states.
Creating a shell requires Workspace Trust. Names, PTY dimensions, input sizes and
session counts are bounded. Up to twelve sessions are retained per workspace.
Output replay is bounded to 256 Ki UTF-16 code units per session, preserving
surrogate boundaries. Exited shells retain their transcript until explicitly
stopped; a daemon with no sessions exits after five minutes.

The socket and token live inside a mode-0700 runtime directory derived from the
workspace and home paths, below `TMPDIR`; each socket authenticates before it can
list or attach sessions. The daemon binds no TCP port. Tokens and socket files
are mode 0600. It rejects duplicate operation IDs; create and input identities
prevent replay of ambiguous acknowledgements. Input arriving while disconnected
is rejected rather than buffered for a later command execution.

A daemon survives editor reloads, extension-host restarts, ordinary SSH transport
loss and closed tabs while its backend operating system keeps it alive. Android
force-stop, device reboot, host reboot or killing the daemon can end its shells.
No commands are rerun to pretend a dead process survived. Normal server disconnect
must leave the remote daemon running; stopping the remote operating system is a
separate action. Keeping a local shell running indefinitely under Android memory
and battery policies is outside this guarantee.

Run focused checks with:

```sh
node --test workbench/session-tools/*.test.cjs
```

The integration suite uses the existing pinned `node-pty` source tree under
`.local/android-runtime/addon-sources/node-pty` when prepared. Set
`VSTAB_TEST_PTY_MODULE` to another compatible module directory when needed.
Integration cases skip explicitly when no real binding exists; they must not be
reported as passed. Android UI/device proof is recorded separately by the parent
application verification.
