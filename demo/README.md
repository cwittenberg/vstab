# Isolated SSH demonstration

Run `./demo/start.sh` from a Linux development host with Docker, Python and OpenSSH installed. It builds a disposable Ubuntu SSH server and binds it to **host loopback only** at `127.0.0.1:22227`. An Android emulator reaches it through the emulator host alias `10.0.2.2:22227`; a physical tablet needs a separate reachable SSH host or an explicit development-network mapping.

The fixture creates a `developer` account with a generated password and SSH key. Credentials are stored only in ignored `.local/demo/` files; `.local/demo/connection.json` is mode 0600. The project is `/home/developer/project`. The fixture never reads the operator's existing SSH keys, provider tokens or Codex authentication.

In vstab, add that SSH host, inspect/accept its fingerprint, authenticate, and open the project. Change `app.py` in the editor, save it and run `python3 app.py` in the integrated terminal. Install the official Codex extension from the application's Extensions UI; do not preinstall it through the fixture if recording an installation demonstration.

For independent verification of saved bytes after the app interaction:

```sh
python3 demo/verify.py --output artifacts/remote-state.json
```

This records the remote file, Git diff, program output and installed extension IDs over SSH. Pair it with Android screenshots/video and app session evidence; the inspection alone does not demonstrate Android UI interaction.

The demo container is `vstab-demo-ssh`. Stop it with `docker stop vstab-demo-ssh`. Removing that named container discards its remote project/server state; private host credentials remain under `.local/demo/` for repeatable testing. Do not publish those credential files or secret bootstrap responses.
