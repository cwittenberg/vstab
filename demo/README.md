# SSH test project

Requires Linux, Docker, Python and OpenSSH.

```sh
./demo/start.sh
python3 demo/verify.py --output artifacts/remote-state.json
docker stop vstab-demo-ssh
```

| Setting | Value |
| --- | --- |
| Host | `127.0.0.1:22227`; Android emulator: `10.0.2.2:22227` |
| Login | Generated in `.local/demo/connection.json` |
| Project | `/home/developer/project` |

Connect from VStab, open the project, edit `app.py`, save, and run `python3 app.py` in Terminal.
Keep `.local/demo/` credentials private. Physical devices need a reachable SSH host.
