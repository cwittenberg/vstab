#!/usr/bin/env python3
"""Embed deterministic, source-owned Node extensions in the APK's SSH bootstrap."""
import argparse
import base64
import gzip
import hashlib
import io
import json
from pathlib import Path
import tarfile

ROOT = Path(__file__).resolve().parents[1]
EXTENSIONS = {
    'vstab-session-tools': 'workbench/session-tools/extension',
    'vstab-compatibility': 'workbench/compatibility/extension',
}
MARKER = '# @VSTAB_FIRST_PARTY_EXTENSIONS@'


def extension_files(root=ROOT):
    files = {}
    for name, relative in EXTENSIONS.items():
        directory = root / relative
        package = json.loads((directory / 'package.json').read_text())
        if package.get('publisher') != 'vstab' or not package.get('main'):
            raise ValueError(f'{relative} must be a first-party Node extension')
        entry = (directory / package['main']).resolve()
        if not entry.is_relative_to(directory.resolve()) or not entry.is_file():
            raise ValueError(f'{relative} has an unsafe or missing entry point')
        for source in sorted(directory.rglob('*')):
            if source.is_symlink():
                raise ValueError(f'Symlinks are not extension payloads: {source}')
            if not source.is_file():
                continue
            parts = source.relative_to(directory).parts
            if any(part.startswith('.') or part in ('test', 'tests', 'fixtures', 'node_modules', '__pycache__') for part in parts):
                continue
            if source.suffix not in ('.cjs', '.js', '.json', '.svg', '.md', '.txt'):
                raise ValueError(f'Unexpected executable or asset in extension: {source}')
            files[name + '/' + '/'.join(parts)] = source.read_bytes()
    return files


def payload(root=ROOT):
    files = extension_files(root)
    manifest = {'schema': 1, 'extensions': EXTENSIONS, 'files': {
        name: {'sha256': hashlib.sha256(data).hexdigest(), 'bytes': len(data)}
        for name, data in sorted(files.items())}}
    files['vstab-first-party.json'] = (json.dumps(manifest, sort_keys=True, indent=2) + '\n').encode()
    stream = io.BytesIO()
    with gzip.GzipFile(fileobj=stream, mode='wb', mtime=0, filename='') as compressed:
        with tarfile.open(fileobj=compressed, mode='w', format=tarfile.GNU_FORMAT) as archive:
            for name, data in sorted(files.items()):
                member = tarfile.TarInfo(name)
                member.mode = 0o644
                member.size = len(data)
                archive.addfile(member, io.BytesIO(data))
    result = stream.getvalue()
    return result, manifest


def install_script(data):
    digest = hashlib.sha256(data).hexdigest()
    encoded = '\n'.join(base64.b64encode(data).decode()[i:i+100] for i in range(0, len(base64.b64encode(data)), 100))
    return f'''# First-party payload: source bytes authenticated by the app-owned SSH script.
vstab_first_party_sha={digest}
vstab_feature_id={digest[:20]}
vstab_base_install="$vstab_install"
vstab_install="$vstab_root/server/$vstab_version-vstab-$vstab_feature_id"
vstab_state="$vstab_root/state/$vstab_version-$vstab_feature_id"
mkdir -p "$vstab_state"
chmod 700 "$vstab_state"
if [ ! -r "$vstab_install/.vstab-first-party-sha256" ] || [ "$(cat "$vstab_install/.vstab-first-party-sha256")" != "$vstab_first_party_sha" ]; then
  printf 'vstab: Preparing verified built-in workspace tools.\\n' >&2
  vstab_stage=$(mktemp -d "$vstab_root/server/.features.XXXXXX")
  base64 -d > "$vstab_stage/tools.tar.gz" <<'VSTAB_FIRST_PARTY_PAYLOAD'
{encoded}
VSTAB_FIRST_PARTY_PAYLOAD
  printf '%s  %s\\n' "$vstab_first_party_sha" "$vstab_stage/tools.tar.gz" | sha256sum -c - >/dev/null 2>&1 \\
    || vstab_error 'Built-in workspace tools failed their checksum.' builtin_checksum_mismatch
  tar -tzf "$vstab_stage/tools.tar.gz" | awk '/^\\// || /(^|\\/)\\.\\.(\\/|$)/ {{ bad=1 }} END {{ exit bad }}' \\
    || vstab_error 'Built-in workspace tools contain an unsafe path.' unsafe_builtin_archive
  # Immutable variants avoid the live server's builtin-scan cache. Hardlinks
  # share the pinned upstream bytes, while every owned extension gets new files.
  cp -al "$vstab_base_install" "$vstab_stage/server" \\
    || vstab_error 'Could not prepare built-in tools. Check host storage.' builtin_copy_failed
  rm -rf -- "$vstab_stage/server/lib/vscode/extensions/vstab-session-tools" "$vstab_stage/server/lib/vscode/extensions/vstab-compatibility"
  tar -xzf "$vstab_stage/tools.tar.gz" -C "$vstab_stage/server/lib/vscode/extensions" --no-same-owner \\
    || vstab_error 'Could not extract built-in workspace tools.' builtin_extraction_failed
  printf '%s\\n' "$vstab_first_party_sha" > "$vstab_stage/server/.vstab-first-party-sha256"
  if [ -e "$vstab_install" ]; then
    vstab_error 'The matching tools directory is incomplete. Retain it for diagnosis and retry after renaming it.' builtin_incomplete
  fi
  mv "$vstab_stage/server" "$vstab_install"
  rm "$vstab_stage/tools.tar.gz"
  rmdir "$vstab_stage"
  vstab_stage=
fi
# Do not stop an older editor: its builds and session daemons may still be in use.
vstab_node="$vstab_install/lib/node"
'''


def generate(output, root=ROOT):
    template = (root / 'server/bootstrap.sh').read_text()
    if template.count(MARKER) != 1:
        raise ValueError('The SSH bootstrap must contain exactly one first-party payload marker')
    data, manifest = payload(root)
    result = template.replace(MARKER, install_script(data)).encode()
    if len(result) > 1024 * 1024:
        raise ValueError('The generated SSH script exceeds the native 1 MiB limit')
    output.mkdir(parents=True, exist_ok=True)
    script = output / 'server-bootstrap.sh'
    script.write_bytes(result)
    metadata = {**manifest, 'payloadSha256': hashlib.sha256(data).hexdigest(),
                'bootstrapSha256': hashlib.sha256(result).hexdigest(), 'bootstrapBytes': len(result)}
    (output / 'server-tools.json').write_text(json.dumps(metadata, indent=2) + '\n')
    return metadata


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, default=ROOT / '.local/server-assets')
    args = parser.parse_args()
    metadata = generate(args.output)
    print(f"Packaged SSH bootstrap: {metadata['bootstrapBytes']} bytes; tools {metadata['payloadSha256'][:20]}")


if __name__ == '__main__':
    main()
