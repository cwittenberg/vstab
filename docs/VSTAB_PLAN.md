# vstab architecture

The maintained implementation target is [TARGET.md](../TARGET.md). This guide describes the current source structure and replaces the earlier milestone-by-milestone proposal.

## Workbench and backends

The Android APK contains the real Code OSS browser workbench, paired to a pinned code-server release. Android System WebView renders its HTML, scripts, workers and built-in browser extensions from APK assets. It does not need a hosted editor to start.

Local projects use a bundled Android/Bionic Node backend, Node extension host, Bash PTY and tools. Native executables and addons are packaged in the APK's read-only native library directory. Support files and backend JavaScript are extracted into private app storage after checksum verification. See [runtime packaging](../native-runtime/README.md).

For a remote workspace, native Android SSH authenticates to a Linux account, verifies its host key, bootstraps the matched server and tunnels its loopback service. Files, Node extensions, Git and terminal processes then run under that account. The editor interface still comes from the APK. See [server bootstrap](../server/README.md).

Client and server identities remain paired. Exact upstream commits, artifact hashes and dependency locks are recorded in [workbench provenance](../workbench/SOURCES.md), `workbench/bundle-spec.json` and `release/`. Packaging refuses unknown patch inputs rather than bypassing protocol compatibility.

## Android integration

`MainActivity` hosts the workbench. `LocalRuntimeManager` and its foreground service own on-device backend startup; `LocalEditorGateway` exposes its authenticated app-owned gateway. `ConnectionController` and `NativeSshBridge` manage SSH sessions and bootstrap. Workspace tasks share the local backend while keeping remote connections separate.

The `vstab.android-controls` browser extension puts connection, workspace and document actions in Code OSS. A scoped MessagePort connects that first-party extension to the active Android window. [The control protocol](SSH_CONTROL_PROTOCOL.md) defines its methods and limits; [SSH configuration](SSH_CONFIG.md) describes the actual on-device `~/.ssh/config` integration. Credentials belong to a native-issued authentication prompt and are not stored as host configuration.

The older browser-only local filesystem adapter under `workbench/local/` remains available for its tests and prototype embedding branch. The ordinary application uses the native Node backend. Its embedding patch also supplies the current first-party control extension's workbench hook.

## Files, windows and terminals

Normal startup opens one workspace. Users may explicitly open additional Android tasks, up to four windows. The native document integration synchronizes granted Android files/folders with app-private working copies so Node tools use normal filesystem paths. Concurrent edits retain both versions for review.

The `vstab.session-tools` Node extension owns named PTY sessions through a separate daemon, allowing editor reloads and SSH reconnects to reattach while that process survives. The compatibility extension reports observed extension activation failures. See [workspace behavior and limits](WORKSPACE_FEATURES.md), [persistent terminals](../workbench/session-tools/README.md) and [extension diagnostics](../workbench/compatibility/README.md).

## Qualification boundaries

Build/lint and unit checks establish source and package properties. Android instrumentation establishes the exercised runtime behavior. Normal app journeys must separately demonstrate offline startup/edit/save/restart, integrated terminals, SSH workflows, shared-document write-back and extension activation. Provider sign-in and a completed AI request are distinct from opening an extension panel.

Physical ARM64 tablet behavior, arbitrary desktop native extensions, advanced SSH authentication and uninterrupted Android background execution require separate evidence. Follow the [verification procedure](DEMONSTRATION.md) and [workspace acceptance target](WORKSPACE_FEATURES_TARGET.md). Generated captures and private fixture data stay outside the source repository.
