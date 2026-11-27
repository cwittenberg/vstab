# Remote server bootstrap

`bootstrap.sh` is the self-contained asset the Android app sends to an authenticated SSH exec channel running `sh -s --`. It accepts no shell-interpolated user parameters. It installs the pinned code-server archive into the remote user's private data directory, verifies its SHA-256 from the app's trusted release pin, starts it on remote loopback with password authentication and returns exactly one protocol JSON line on stdout.

Success fields are `protocol`, `status`, `port`, `password`, `workspace`, `home`, `serverVersion` and `codeVersion`. **The response contains the server password and must never be logged.** Progress goes to stderr. Error responses contain `status: error` and a stable `code`.

If a developer captures the response to a local file for backend diagnostics, set `umask 077` before shell redirection and keep the file inside ignored private state. The install script's remote umask cannot control permissions on a file created by the caller's local shell.

The native Android client forwards the returned remote port through SSH, posts the password to `/login`, installs the returned HttpOnly cookie into its WebView cookie store, then opens `/?folder=<encoded absolute remote folder>`. The folder is a URL parameter, not a shell command. Resolve a user's `~/` prefix against `home` without shell interpolation.

Supported first targets are Linux x86_64 and ARM64 with a compatible glibc runtime, `curl`, `tar`, `sha256sum` and standard POSIX utilities. A verified archive preloaded in `~/.cache/vstab/` avoids downloading it again. Release metadata lives in `release/server-lock.json`.

Run `demo/start.sh` to create the isolated Linux SSH fixture. It uses a dedicated generated password and key, binds host port 22227 to loopback and puts private connection details in `.local/demo/connection.json`. It does not use the operator's SSH or Codex credentials.
