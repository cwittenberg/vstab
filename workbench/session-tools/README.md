# Persistent terminals

| Command | ID |
| --- | --- |
| New Persistent Terminal | `vstab.sessions.create` |
| Attach to Persistent Terminal | `vstab.sessions.attach` |
| Rename Persistent Terminal | `vstab.sessions.rename` |
| Stop Persistent Terminal | `vstab.sessions.stop` |
| Refresh Persistent Terminals | `vstab.sessions.refresh` |

Closing a tab detaches it. Stop ends its shell. Up to 12 sessions per workspace.
Editor reload and SSH reconnect can reattach; force-stop, reboot or killing the daemon ends local sessions.

```sh
node --test workbench/session-tools/*.test.cjs
```

Tests need a compatible `node-pty`; set `VSTAB_TEST_PTY_MODULE` to its directory if using a custom install.
