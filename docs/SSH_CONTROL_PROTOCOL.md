# Built-in Remote SSH control protocol, version 1

The first-party browser extension is `vstab.android-controls`. Its Code OSS
`ExtensionContext.messagePassingProtocol` communicates through the embedder's
MessageChannel and one exact-origin Android WebMessagePort. The handshake is
`vstab-ssh-ready`. Only the bundled main workbench frame receives that port.
Private-key contents, authentication cookies and arbitrary native
execution/filesystem operations never cross this channel. Passwords and
passphrases may transit only an ephemeral, native-issued `ssh.respondPrompt`
reply to the first-party extension's masked VS Code input. They never appear in
state/config/events/logs, host metadata, URLs or persistent browser storage.

Requests: `{v:1,id:positiveInteger,method,params:{...}}`.
Replies: `{v:1,id,ok:true,result}` or
`{v:1,id,ok:false,error:{code,message}}`.
Maximum serialized message: 512 KiB; maximum outstanding calls: 64.
Config UTF-8 content: at most 256 KiB. Use typed errors `Unavailable`,
`NoPermissions`, `FileNotFound`, `Conflict`, `InvalidConfiguration`.

Events have no `id`: `{v:1,event:'stateChanged',state}` and
`{v:1,event:'configChanged',revision,mtime,size}`. Emit state after metadata,
connection phase or folder changes, and config events after atomic saves.
UI actions reply with `{accepted:true}` before navigating/destroying the page.

## State shape

`ssh.getState` returns:

```json
{
  "schema": 1,
  "profiles": [{
    "id": "opaque-stable-alias-id",
    "alias": "workstation",
    "hostname": "192.0.2.10",
    "user": "developer",
    "port": 22,
    "authMethod": "key",
    "hasIdentity": true,
    "configLine": 1,
    "connectable": true,
    "diagnostics": [],
    "recentFolders": ["/home/developer/project"]
  }],
  "connection": {
    "phase": "connected",
    "profileId": "opaque-stable-alias-id",
    "connectionId": "opaque-session-generation",
    "message": "Connected to workstation",
    "folder": "/home/developer/project",
    "home": "/home/developer"
  },
  "config": {"revision": "sha256", "mtime": 0, "size": 0, "diagnostics": []}
}
```

`configLine` and diagnostic `line` fields are zero-based. `connection` is always
present; idle phase is `disconnected`, with empty IDs/paths. Other phases are
`connecting`, `verifying-host`, `authenticating`, `preparing`, `connected`,
`reconnecting`, `disconnecting`, `error`. Only display phases actually entered.
`authMethod` is `password`, `key` or `automatic`; it is descriptive, not a secret.
Diagnostics: `{line, message, severity:'error'|'warning'}`.

## Methods

| Method | Parameters | Result / behavior |
| --- | --- | --- |
| `ssh.getState` | `{}` | State above; no connection side effect |
| `ssh.readConfig` | `{}` | `{content,revision,mtime,size,diagnostics}` for fixed native `HOME/.ssh/config` |
| `ssh.writeConfig` | `{content,expectedRevision}` | Same config snapshot, atomically saved with compare-and-swap; preserve exact user text, return parse diagnostics |
| `ssh.addHost` | `{command,alias?}` | `{profileId,state}`; parse SSH command without execution, append ordinary config entry |
| `ssh.removeHost` | `{profileId}` | Updated state; remove only that alias, preserving unrelated blocks/comments; frontend confirms first |
| `ssh.connect` | `{profileId,path?}` | `{accepted:true}`; native credential and host-key dialogs, then normal connection stages; optional path selects a known recent folder on connection |
| `ssh.disconnect` | `{}` | `{accepted:true}`; close SSH transport and return to native local workspace |
| `ssh.reconnect` | `{profileId}` | `{accepted:true}`; explicit reconnect, prompt for session credentials as needed |
| `ssh.listDirectory` | `{profileId,connectionId,path}` | `{path,parent,home,directories:[{name,path,symbolicLink}],truncated}`; existing authenticated session only |
| `ssh.openFolder` | `{profileId,connectionId,path}` | `{accepted:true}`; validate actual directory, reuse SSH session/tunnel, publish updated Ready and reopen bundled IDE with selected folder |
| `ssh.importKey` | `{profileId}` | `{accepted:true}`; native Android document picker, private key copy under `HOME/.ssh/keys`, mode0600, config IdentityFile updated; no key bytes to JS |
| `ssh.respondPrompt` | `{promptId,value?,accepted?,cancelled?}` | `{accepted:true}`; answer one native-issued password/passphrase/host-key prompt, bound to its visible source and connection generation; reject stale/replayed IDs |
| `workspace.local` | `{}` | `{accepted:true}`; return to native local IDE, with normal unsaved-work handling |
| `app.about` | `{}` | `{accepted:true}`; native About dialog |

Directory requests must match the actual active profile/session generation;
stale or disconnected requests fail without authenticating another host.
Empty or `~` directory path means that account's home. Permit normal absolute
remote paths, reject NULs, enforce entry/output bounds, retain the live session
on listing errors. Browser browsing never reads remote file contents through
this channel: normal Code OSS Explorer owns the chosen workspace's files.

The config editor URI is exactly `vstab-ssh-config:/config`. The extension's
FileSystemProvider exposes that one file, supports stat/read/write/watch, and
uses the native revision from its last read for write conflict detection.
Config diagnostics appear in the editor. Other paths/schemes are rejected.

Canonical aliases/options come from a real native-home OpenSSH config. Private
metadata stores only key-document migration references, recent directories and
display history. Imported keys retain their original OpenSSH encryption;
passwords and passphrases remain session-only. Unsupported execution-dependent
options are shown as actionable diagnostics, never silently executed/ignored.

Root frontend owns VS Code Quick Picks, masked input boxes, fingerprint consent,
tree items/context menus, config editing and standard save/discard/cancel
handling before workspace changes. Native UI owns key document selection and
actual transport/credential consumption. Connection events update the host
icon, description, tooltip and remote status bar throughout the lifecycle.

## Credential and host-key prompts

Native emits `{v:1,event:'sshPrompt',prompt:{id,profileId,kind,title,message,
detail?,host?,port?,user?,fingerprint?,algorithm?}}`. `kind` is `password`,
`passphrase` or `host-key`. Only the foreground bound workbench can answer;
cancel pending prompts on detach, disconnect or connection-generation change.
Use an opaque random ID, allow one reply, and time out after 120 seconds.

The extension uses `window.showInputBox({password:true,ignoreFocusOut:true})`
for password/passphrase, and a normal VS Code modal Continue/Cancel confirmation
displaying the native fingerprint for a first host key. It answers through
`ssh.respondPrompt`. Cancellation sets `cancelled:true`; a host key continues
only with explicit `accepted:true`. Changed known keys remain blocked rather
than receiving a new-host consent prompt. Secret replies are bounded to 16KiB
and must never enter diagnostics, logs, saved host/config text or event payloads.
