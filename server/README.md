# SSH server setup

Targets: Linux x86_64/ARM64 with glibc, `curl`, `tar` and `sha256sum`.
The app installs the pinned server under the SSH user's account; no sudo required.
Preload a verified archive in `~/.cache/vstab/` to avoid downloading it again.

```sh
python3 scripts/package-server-assets.py
```

The app sends the generated bootstrap through SSH. It returns one JSON line with `protocol`, `status`, `port`, `password`, `workspace`, `home`, `serverVersion` and `codeVersion`.
**Never log the response: it contains a password.** Progress goes to stderr.

[Server lock](../release/server-lock.json) · [Local SSH fixture](../demo/README.md)
