# Built-in Android and Remote SSH controls

This first-party browser extension supplies the normal Code OSS Remote Explorer,
saved SSH aliases and folders, config editing, authentication prompts and live
connection status. It ships with Android/SSH integration; Codex and Claude Code
remain separately installed extensions.

Edit `src/extension.cjs` and `src/native-client.js`. `extension/extension.js` is
the generated single browser bundle: web extension workers cannot load ordinary
relative CommonJS modules. `node build.mjs` uses the locked esbuild dependency in
`../local`; `scripts/package-workbench.py` runs that build before packaging.
Keep separately maintained document UI code in `extension/documents-ui.js`; the
source entry explicitly includes it in the generated bundle.

The first-party extension receives one scoped `ipc` MessagePort from Code OSS's
web embedder. `native-startup.mjs` connects that worker port to Android's
exact-origin main-frame WebMessagePort. The pinned v4 bootstrap hook preserves
normal Node-backed workbench options and only adds this extension's channel.
The methods, event/status fields, fixed config URI and ephemeral credential
reply rules are defined in [the protocol](../../docs/SSH_CONTROL_PROTOCOL.md).

`vstab-ssh-config:/config` exposes only the native home's `~/.ssh/config` through
the normal editor. Native compare-and-swap saves protect concurrent changes.
SSH hosts come from that actual file, while private metadata retains recent
folders and legacy key-document references. Private key bytes stay native.
Password/passphrase replies exist only for a native-issued masked prompt;
neither config/state nor browser storage/logs receive them.

The earlier UI-only URI actions remain available to the Android shell:

- `vstab://ssh/connect` opens connection settings.
- `vstab://ssh/disconnect` requests the normal disconnect confirmation.
- `vstab://workspace/local` returns to the Android workspace, with the appropriate unsaved-work decision.
- `vstab://about` opens application/source information.

No URI accepts a host, credential, command, arbitrary path or socket. These actions do not expose native filesystem/SSH APIs to extension frames. The native handler must reject other actions and validate its current application context.
