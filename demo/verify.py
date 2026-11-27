#!/usr/bin/env python3
"""Independently inspect the fixture after edits performed in the Android app.

This runs over an ordinary verified SSH session from the host. It is supporting
evidence for remote bytes and processes, not proof of which UI made an edit.
"""

import argparse
import json
from pathlib import Path
import subprocess
import sys


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path)
    parser.add_argument('--expect-greeting')
    args = parser.parse_args()
    root = Path(__file__).resolve().parent.parent
    private = root / '.local/demo'
    command = [
        'ssh', '-i', str(private / 'id_ed25519'),
        '-o', f'UserKnownHostsFile={private / "known_hosts"}',
        '-o', 'StrictHostKeyChecking=yes', '-o', 'IdentitiesOnly=yes',
        '-p', '22227', 'developer@127.0.0.1', 'python3', '-',
    ]
    inspection = r'''
import getpass, hashlib, json, pathlib, platform, subprocess
project = pathlib.Path('/home/developer/project')
source = (project / 'app.py').read_text()
state = pathlib.Path.home() / '.local/share/vstab'
extensions = []
extension_root = state / 'extensions'
if extension_root.exists():
    for manifest_path in extension_root.glob('*/package.json'):
        try:
            manifest = json.loads(manifest_path.read_text())
            extensions.append({'id': manifest.get('publisher', '') + '.' + manifest['name'], 'version': manifest['version']})
        except (OSError, KeyError, ValueError):
            pass
result = {
    'inspection': 'independent SSH read after Android interaction',
    'remoteUser': getpass.getuser(), 'remoteHost': platform.node(),
    'project': str(project), 'file': 'app.py',
    'sha256': hashlib.sha256(source.encode()).hexdigest(),
    'content': source,
    'gitDiff': subprocess.run(['git', 'diff', '--', 'app.py'], cwd=project, capture_output=True, text=True, check=True).stdout,
    'runOutput': subprocess.run(['python3', 'app.py'], cwd=project, capture_output=True, text=True, check=True).stdout,
    'installedExtensions': extensions,
}
print(json.dumps(result, indent=2))
'''
    result = subprocess.run(command, input=inspection, text=True, capture_output=True)
    if result.returncode:
        print(result.stderr, file=sys.stderr)
        raise SystemExit(result.returncode)
    report = json.loads(result.stdout)
    if args.expect_greeting and args.expect_greeting not in report['runOutput']:
        raise SystemExit('Remote program output did not contain the expected greeting')
    rendered = json.dumps(report, indent=2) + '\n'
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(rendered)
        print(f'Remote state recorded at {args.output}; {len(report["installedExtensions"])} installed extensions.')
    else:
        print(rendered, end='')


if __name__ == '__main__':
    main()
