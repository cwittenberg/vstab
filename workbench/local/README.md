# Local Code OSS workspace integration

The local editor is the bundled, real Code OSS workbench. This integration supplies an Android filesystem to its normal Explorer, editor and save operations; it does not supply another editor widget.

## Startup

Copy runtime files to `/local/` under the app-owned asset origin:

- `bridge.mjs`
- `startup.mjs`
- `extension/package.json`
- `extension/extension.js`

The original workbench HTML retains its base-URL, product, NLS and import-map setup. Replace its direct workbench entry script with:

```html
<script type="module" src="/local/startup.mjs"></script>
```

Run `patch-workbench.py` against the pinned compiled `static/out/vs/code/browser/workbench/workbench.js` during packaging. The patch locates the existing Code OSS startup call, derives its minified aliases, and inserts a guarded local embedding branch. It calls the actual existing `create()` function with normal metadata plus the constructed local options. It does not modify the editor, explorer or extension APIs. The patch refuses an unknown bootstrap and is idempotent.

The patch also restores the official upstream browser user-data URI (`vscode-userdata:/User`) when no remote authority exists. code-server's downstream storage patch otherwise requires a remote `userDataPath` before local startup. The local branch uses the workbench's existing IndexedDB file/storage providers; it never invents a server path or depends on SSH for local settings. Remote sessions retain the original remote user-data behavior. [Pinned upstream environment service](https://github.com/microsoft/vscode/blob/69047191/src/vs/workbench/services/environment/browser/environmentService.ts).

`startup.mjs` creates the native-message listener synchronously, constructs a first-party extension MessageChannel, and imports the actual workbench using its versioned base-URL metadata. `remoteAuthority` is absent locally. The workbench options supply `additionalBuiltinExtensions`, `enabledExtensionProposedApi`, `messagePorts`, `workspaceProvider` and the built-in Connect to Host action.

The browser extension ID is `vstab.local-workspace`. It declares the `ipc` proposed API and uses `ExtensionContext.messagePassingProtocol`. The official Code OSS worker implementation transfers the embedder’s corresponding `messagePorts` entry to that extension. It also registers the real `fileSearchProvider` and `textSearchProvider` APIs for Quick Open and Find in Files. [Embedding API](https://github.com/microsoft/vscode/blob/main/src/vs/workbench/browser/web.api.ts), [IPC API](https://github.com/microsoft/vscode/blob/main/src/vscode-dts/vscode.proposed.ipc.d.ts), [worker implementation](https://github.com/microsoft/vscode/blob/main/src/vs/workbench/services/extensions/browser/webWorkerExtensionHost.ts).

## Android port contract

Only the local main-frame origin `https://appassets.androidplatform.net` receives a native filesystem channel. After the page finishes loading, Android transfers one WebMessagePort with the string `vstab-native-ready`. The renderer rejects an iframe as the sender, accepts the native channel once, and queues operations until that channel arrives. Android receives and returns JSON strings on the transferred port. No `addJavascriptInterface` is needed.

Request:

```json
{"v":1,"id":1,"method":"fs.readFile","params":{"path":"/workspace/main.py"}}
```

Success:

```json
{"v":1,"id":1,"ok":true,"result":{"dataBase64":"cHJpbnQoJ2hpJykK"}}
```

Failure:

```json
{"v":1,"id":1,"ok":false,"error":{"code":"FileNotFound","message":"No such file."}}
```

Supported methods:

| Method | Parameters/result |
| --- | --- |
| `fs.stat` | `path`; result `{type,ctime,mtime,size}` using VS Code FileType and millisecond times |
| `fs.readDirectory` | `path`; result `[[name,type],...]` |
| `fs.readFile` | `path`; result `{dataBase64}` |
| `fs.writeFile` | `path,dataBase64,create,overwrite`; writes are native and atomic |
| `fs.createDirectory` | `path` |
| `fs.delete` | `path,recursive` |
| `fs.rename` | `path,newPath,overwrite` |
| `local.import` | Show SAF picker; result `{path}` or `{cancelled:true}` |
| `local.export` | `path`; show SAF destination picker |
| `ssh.showConnections` | Show the native SSH host UI |

Native code anchors `/workspace` under the app-private project directory. It must validate canonical containment, follow the requested create/overwrite semantics, and reject traversal or unauthorized paths. Paths are already decoded URI paths; do not decode them again. The provider accepts local files up to 16 MiB and exposes `vstab-local:/workspace` as the initial real workspace.

Native file imports/changes can send `{"v":1,"event":"filesChanged","changes":[{"path":"/workspace/main.py","type":1}]}`. Types are Changed=1, Created=2 and Deleted=3. The workbench receives normal filesystem events. The native channel is never transferred into an SSH-hosted renderer.

The first-party extension contributes Import File from Android and Export File to Android. The construction options contribute Remote-SSH: Connect to Host and the Android remote-status indicator. File export saves the current document first.

## Local search

Quick Open and Find in Files walk the granted native workspace through the same filesystem RPC. File search uses relaxed path matching, with the official Code OSS glob matcher for includes/excludes. Workspace and nested `.gitignore` files use the `ignore` package. Text search supports literal/JavaScript regular-expression queries, case sensitivity, whole words, multiline matches, previews, context, cancellation and result limits.

Traversal is bounded to 20,000 entries, an individual file to 16 MiB and a text query to 64 MiB of file reads. Providers report limits and skipped files rather than claiming exhaustive results. App-private workspaces do not follow symlinks or read global/parent ignore files outside the grant. The search cache is short-lived and invalidated by file changes.

The source is `src/extension.cjs` plus `src/search.js`; `extension/extension.js` is its browser bundle. `build.mjs` bundles the pinned upstream glob source and includes both Microsoft and `ignore` license notices. Rebuild after source changes:

```sh
npm ci --prefix workbench/local --ignore-scripts --no-audit --no-fund
node workbench/local/build.mjs
```

## Verification

```sh
node --test workbench/local/bridge.test.mjs
python3 workbench/local/patch-workbench.py input-workbench.js output-workbench.js
```

The channel test runs the actual browser bundle across both message boundaries and checks byte preservation, native errors, queued startup, path rejection and forbidden operations. It also exercises Quick Open fuzzy paths, upstream globs, workspace ignore rules, text matches, result limits and cancellation. It does not replace actual Android workbench qualification.
