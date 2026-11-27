#!/bin/sh
# Creates an isolated local SSH fixture. Credentials stay in .local/demo/.
set -eu
umask 077
vstab_demo_root=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
vstab_demo_private="$vstab_demo_root/.local/demo"
vstab_demo_archive="$vstab_demo_root/.local/downloads/code-server-4.146.0-linux-amd64.tar.gz"
mkdir -p "$vstab_demo_private"
chmod 700 "$vstab_demo_private"
if [ ! -f "$vstab_demo_private/id_ed25519" ]; then
  ssh-keygen -q -t ed25519 -N '' -C vstab-isolated-demo -f "$vstab_demo_private/id_ed25519"
fi
cp "$vstab_demo_private/id_ed25519.pub" "$vstab_demo_private/authorized_keys"
if [ ! -f "$vstab_demo_private/container.env" ]; then
  printf 'VSTAB_DEMO_PASSWORD=%s\n' "$(od -An -N24 -tx1 /dev/urandom | tr -d ' \n')" > "$vstab_demo_private/container.env"
fi
if [ ! -f "$vstab_demo_archive" ]; then
  mkdir -p "$(dirname "$vstab_demo_archive")"
  curl --fail --location --proto '=https' --tlsv1.2 --retry 2 \
    --output "$vstab_demo_archive.partial" \
    https://github.com/coder/code-server/releases/download/v4.146.0/code-server-4.146.0-linux-amd64.tar.gz
  mv "$vstab_demo_archive.partial" "$vstab_demo_archive"
fi
printf '%s  %s\n' cf7e82ca5fe1df664d43a43d900c3c9710f8f3403a5e8d0e18f6a12d10d7401b "$vstab_demo_archive" | sha256sum -c - >/dev/null
docker build -t vstab-demo-ssh:local "$vstab_demo_root/demo"
if docker container inspect vstab-demo-ssh >/dev/null 2>&1; then
  docker start vstab-demo-ssh >/dev/null
else
  docker run -d --name vstab-demo-ssh \
    --publish 127.0.0.1:22227:22 \
    --env-file "$vstab_demo_private/container.env" \
    --mount "type=bind,src=$vstab_demo_private,dst=/opt/vstab-fixture,readonly" \
    --mount "type=bind,src=$(dirname "$vstab_demo_archive"),dst=/opt/vstab-cache,readonly" \
    vstab-demo-ssh:local >/dev/null
fi
python3 - "$vstab_demo_private" <<'PY'
import json, pathlib, sys
root = pathlib.Path(sys.argv[1])
password = (root / 'container.env').read_text().strip().split('=', 1)[1]
connection = {
    'host': '127.0.0.1', 'emulatorHost': '10.0.2.2', 'port': 22227,
    'username': 'developer', 'password': password,
    'privateKey': str(root / 'id_ed25519'), 'workspace': '/home/developer/project',
    'container': 'vstab-demo-ssh',
}
(root / 'connection.json').write_text(json.dumps(connection, indent=2) + '\n')
(root / 'connection.json').chmod(0o600)
print('Isolated SSH fixture is starting at host 127.0.0.1:22227 (emulator 10.0.2.2:22227).')
print('Private connection details: .local/demo/connection.json (mode 0600).')
PY
