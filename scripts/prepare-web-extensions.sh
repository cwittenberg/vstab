#!/bin/sh
# Build real browser builtin extensions from the exact Code OSS source revision.
# The core workbench and remote agent stay paired to the pinned downstream build.
set -eu
vstab_root=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
vstab_build="$vstab_root/.local/upstream"
vstab_source="$vstab_build/vscode"
vstab_revision=69047191fc427f2f92828279aae1d90d8eeda7ab
mkdir -p "$vstab_build/bin"
if [ ! -d "$vstab_source/.git" ]; then
  git init -q "$vstab_source"
  git -C "$vstab_source" remote add origin https://github.com/microsoft/vscode.git
  git -C "$vstab_source" fetch --depth 1 --filter=blob:none origin "$vstab_revision"
  git -C "$vstab_source" checkout --detach FETCH_HEAD
fi
[ "$(git -C "$vstab_source" rev-parse HEAD)" = "$vstab_revision" ] || {
  printf 'The local Code OSS checkout is not the pinned revision; refusing to modify it.\n' >&2
  exit 1
}
if [ ! -x "$vstab_build/bin/node" ]; then
  python3 - "$vstab_root" <<'PY'
import hashlib,json,pathlib,shutil,sys,tarfile,urllib.request
root=pathlib.Path(sys.argv[1]); spec=json.loads((root/'workbench/bundle-spec.json').read_text())
archive=root/'.local/downloads/code-server-4.146.0-linux-amd64.tar.gz'
if not archive.exists():
    archive.parent.mkdir(parents=True,exist_ok=True)
    with urllib.request.urlopen(spec['artifactUrl'],timeout=60) as source, archive.open('wb') as target:
        shutil.copyfileobj(source,target)
hash=hashlib.sha256()
with archive.open('rb') as source:
    for data in iter(lambda:source.read(1024*1024),b''):hash.update(data)
if hash.hexdigest()!=spec['artifactSha256']:raise SystemExit('Pinned server archive checksum mismatch')
with tarfile.open(archive) as source:
    node=source.extractfile('code-server-4.146.0-linux-amd64/lib/node')
    target=root/'.local/upstream/bin/node'
    with target.open('wb') as output:shutil.copyfileobj(node,output)
    target.chmod(0o755)
PY
fi
vstab_node_version=$("$vstab_build/bin/node" --version)
[ "$vstab_node_version" = v24.22.1 ] || { printf 'Expected pinned Node v24.22.1, got %s\n' "$vstab_node_version" >&2; exit 1; }
PATH="$vstab_build/bin:$vstab_build/tools/node_modules/.bin:$PATH"
export PATH
# Enforce certificate verification even if a surrounding development shell has
# disabled Node TLS verification. This setting is scoped to this build process.
NODE_TLS_REJECT_UNAUTHORIZED=1
PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1
export NODE_TLS_REJECT_UNAUTHORIZED PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD
if [ ! -x "$vstab_build/tools/node_modules/.bin/pnpm" ]; then
  npm install --prefix "$vstab_build/tools" --ignore-scripts --no-audit --no-fund pnpm@12.16.0
fi
vstab_pnpm="$vstab_build/tools/node_modules/.bin/pnpm"
[ "$("$vstab_pnpm" --version)" = 12.16.0 ] || { printf 'Expected pinned pnpm 12.16.0.\n' >&2; exit 1; }
for vstab_install_root in "$vstab_source" "$vstab_source/build" "$vstab_source/extensions"; do
  "$vstab_pnpm" --dir "$vstab_install_root" install --ignore-scripts --frozen-lockfile
done
"$vstab_build/bin/node" "$vstab_root/scripts/build-web-extensions.mjs" "$vstab_source"
git -C "$vstab_source" diff --exit-code -- pnpm-lock.yaml build/pnpm-lock.yaml extensions/pnpm-lock.yaml
python3 "$vstab_root/scripts/package-workbench.py"
