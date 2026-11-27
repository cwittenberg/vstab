# Bundled Code OSS workbench provenance

The editor in this APK is the actual Code OSS workbench from the [microsoft/vscode source repository](https://github.com/microsoft/vscode), with the downstream browser deployment patches maintained by [code-server](https://github.com/coder/code-server). It is not an imitation editor or a screenshot of a remote desktop.

Exact source identities for the initial bundle:

- Microsoft Code OSS source: [`69047191fc427f2f92828279aae1d90d8eeda7ab`](https://github.com/microsoft/vscode/tree/69047191fc427f2f92828279aae1d90d8eeda7ab), version 1.146.0.
- Downstream release: [code-server v4.146.0](https://github.com/coder/code-server/releases/tag/v4.146.0), product/protocol commit `9cc1033c190703416bbd5ec95d8f7db9f3edb63d`.
- The [downstream source submodule](https://github.com/coder/code-server/tree/v4.146.0/lib) establishes its upstream Code OSS commit. The two identities must not be confused.
- Artifact URL and trusted SHA-256: `workbench/bundle-spec.json` and `release/server-lock.json`.

`scripts/package-workbench.py` verifies the pinned archive, packages the compiled workbench, CSS, fonts, workers, webview bootstrap/service worker and browser runtime dependencies into APK assets, and records file hashes in the generated `workbench/bundle.json`. It retains the matching downstream client/agent identity without bypassing protocol checks. Browser dependencies are based on upstream [`remote/web/package.json`](https://github.com/microsoft/vscode/blob/69047191fc427f2f92828279aae1d90d8eeda7ab/remote/web/package.json); a full web build additionally uses [`build/.webignore`](https://github.com/microsoft/vscode/blob/69047191fc427f2f92828279aae1d90d8eeda7ab/build/.webignore).

The Android app owns the startup HTML and configuration, loads core assets from its APK and uses its SSH connection for remote agent services, files, terminals and extension resources. It does not fetch the remote server's editor index HTML for startup. The renderer remains Android WebView running the real Code OSS browser workbench; this is not a port of Electron or an editor rewritten with Android widgets.

The app also starts the bundled workbench without an SSH connection for local Android projects. The browser builtin extensions omitted from the server distribution are compiled from the exact Microsoft source commit by `scripts/prepare-web-extensions.sh`. Its three upstream dependency lockfiles remain frozen. `scripts/build-web-extensions.mjs` calls upstream's local browser-extension and media packaging functions and records source/runtime/lock hashes; it omits the separate Marketplace download step. These outputs overlay the extension asset tree, while the matched core client/agent identity remains unchanged.

The `workbench/local/patch-workbench.py` embedding hook adds a dedicated message port for the first-party `vstab.android-controls` browser extension to the existing Node-backed workbench `create()` options. Its `ipc` proposed API allowance applies to that extension ID. Narrow download and clipboard adaptations connect Explorer file transfers to the Android document picker. The workspace provider, folder and webview endpoints retain their upstream behavior. The original and patched core bundle hashes are recorded in `bundle.json`.

The local Android app uses its bundled Bionic Node agent, Node extension host and PTY through the private native gateway; its files and settings are stored by that on-device backend. SSH workspaces use the matching remote agent. Both use the same first-party control extension hook.

`workbench/controls/native-startup.mjs` creates the worker message channel before importing the real workbench. Android transfers a separate, exact-origin main-frame port using `vstab-ssh-ready`; only that first-party extension receives its worker endpoint. Request methods, credential-prompt handling, limits and events are defined in `docs/SSH_CONTROL_PROTOCOL.md`. No HTTP control listener or broad Android JavaScript interface is added.

The initial package retains the downstream browser and webview changes. Its own small HTML changes set vstab metadata, direct icon/manifest URLs into local assets, mark the content source and use bundled English localization. Native Android code supplies dynamic workspace/host information and an HTML-encoded configuration. Provider accounts, Microsoft Marketplace and Microsoft's proprietary Remote-SSH/Server distributions are not embedded by this asset bundle.

Code OSS and code-server notices are retained in the generated static asset tree. Third-party dependency license files are kept with their selected packages. vstab must preserve these notices in distributed builds and must describe downstream changes accurately.
