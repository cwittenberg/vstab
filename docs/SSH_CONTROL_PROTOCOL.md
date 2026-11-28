# Control API v1

Channel: `vstab.android-controls` MessagePort; Android handshake: `vstab-ssh-ready`.
Only the bundled main frame receives the port. Mutating actions require the active visible workspace.

```json
{"v":1,"id":1,"method":"ssh.getState","params":{}}
{"v":1,"id":1,"ok":true,"result":{}}
{"v":1,"id":1,"ok":false,"error":{"code":"Unavailable","message":"…"}}
```

Limits: 512 KiB/message, 64 pending calls, 256 KiB config text, 120-second prompt timeout, 16 KiB credential replies.
Never log request payloads, passwords, passphrases, keys, cookies or bootstrap responses.

| Method | Parameters | Result/action |
| --- | --- | --- |
| `ssh.getState` | `{}` | `{schema,profiles,connection,config}` |
| `ssh.readConfig` | `{}` | `{content,revision,mtime,size,diagnostics}` |
| `ssh.writeConfig` | `{content,expectedRevision}` | Config snapshot; stale revisions return `Conflict` |
| `ssh.addHost` | `{command,alias?}` | `{profileId,state}`; parse command without executing it |
| `ssh.removeHost` | `{profileId}` | Updated state |
| `ssh.connect` | `{profileId,path?}` | Start connection |
| `ssh.reconnect` | `{profileId}` | Reconnect |
| `ssh.disconnect` | `{}` | Disconnect and return to local workspace |
| `ssh.recordWorkspace` | `{profileId,connectionId,path}` | Update active workspace metadata |
| `ssh.importKey` | `{profileId}` | Open Android key picker |
| `ssh.respondPrompt` | `{promptId,value?,accepted?,cancelled?}` | Answer one pending authentication/host-key prompt |
| `workspace.local` | `{}` | Return to local workspace |
| `window.new` | `{}` | Open workspace-window selection |
| `app.about` | `{}` | Open About |
| `tunnels.forward` | `{remoteHost,remotePort,localPort?}` | `{id,connectionId,remoteHost,remotePort,localHost,localPort,localAddress}` |
| `tunnels.close` | `{id,connectionId}` | Close the session-owned listener |
| `tunnels.list` | `{}` | `{connectionId,tunnels}` |
| `documents.getState` | `{}` | Current shared-document state |
| `documents.pickFolder`, `documents.pickFile` | `{}` | Open Android picker; return selected document state |
| `documents.openApproved`, `documents.sync` | `{}` | Open or synchronize the approved document |
| `documents.resolve` | `{path,keepLocal}` | Resolve a shared-file conflict |
| `files.clipboard` | `{}` | `{files:[{token,name,type,size}],chunkBytes}` |
| `files.read` | `{token,offset}` | `{offset,nextOffset,eof,dataBase64}` |
| `files.downloadBegin` | `{name,size?}` | `{token,chunkBytes}` |
| `files.downloadWrite` | `{token,offset,dataBase64}` | `{offset}` |
| `files.downloadFinish` | `{token}` | Open save picker; return save/cancellation result |
| `files.release`, `files.abort` | `{token}` | Release transfer |

Events: `{v:1,event:'stateChanged',state}`, `{v:1,event:'configChanged',revision,mtime,size}` and `{v:1,event:'sshPrompt',prompt}`.
Prompt fields: `{id,profileId,kind,title,message,detail?,host?,port?,user?,fingerprint?,algorithm?}`; kind: `password`, `passphrase`, `host-key`.
Prompt IDs are single-use and bound to the source workspace/connection. Changed known host keys remain blocked.

Profiles contain alias/connection details, diagnostics and recent folders. Connection phases: `disconnected`, `connecting`, `verifying-host`, `authenticating`, `preparing`, `connected`, `reconnecting`, `disconnecting`, `error`.
Config URI: `vstab-ssh-config:/config`; native file: `HOME/.ssh/config`. Line numbers are zero-based. Diagnostics: `{line,message,severity}`.
Errors: `Unavailable`, `NoPermissions`, `FileNotFound`, `Conflict`, `InvalidConfiguration`; transfers can also return `SessionChanged`.

Tunnel requests are available only to the bundled main frame, not extension workers. The standard Ports panel uses them to create real SSH TCP forwards on Android loopback. Destinations are restricted to the remote host's loopback; up to 32 ports per SSH workspace. An occupied local port falls back to an available port, which is displayed in Ports. Listeners remain open while using an external Android browser and close on Stop Forwarding, SSH disconnect or workspace-window closure. Close requests carry the returned tunnel connection ID so stale requests cannot close a later session's listener.

Handlers: [NativeSshBridge](../android/app/src/main/java/app/vstab/NativeSshBridge.java), [MainActivity](../android/app/src/main/java/app/vstab/MainActivity.java), [AndroidFileTransfers](../android/app/src/main/java/app/vstab/AndroidFileTransfers.java).
