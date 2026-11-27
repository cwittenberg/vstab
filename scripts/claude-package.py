#!/usr/bin/env python3
"""Cache and inspect the pinned official Claude Code VSIX; never install or sign in.

The demonstration installs the extension through the Android application's real
Extensions view. This helper never reads provider credentials or configuration.
"""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import sys
import urllib.request
import zipfile


ROOT = Path(__file__).resolve().parents[1]
LOCK = ROOT / 'extensions/claude/linux-x64.lock.json'


def digest(path: Path) -> str:
    result = hashlib.sha256()
    with path.open('rb') as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b''):
            result.update(chunk)
    return result.hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--download', action='store_true', help='download and verify the unmodified publisher VSIX')
    parser.add_argument('--inspect', action='store_true', help='inspect a verified cached VSIX without running it')
    args = parser.parse_args()
    lock = json.loads(LOCK.read_text())
    if lock['id'] != 'Anthropic.claude-code' or lock['publisherVerified'] is not True or lock['targetPlatform'] != 'linux-x64':
        raise RuntimeError('Unexpected publisher identity or target in lock')
    if not re.fullmatch(r'[0-9]+(?:\.[0-9]+)+', lock['version']):
        raise RuntimeError('Unexpected package version')
    if not lock['downloadUrl'].startswith('https://open-vsx.org/api/Anthropic/claude-code/linux-x64/'):
        raise RuntimeError('Unexpected extension package source')
    package = ROOT / '.local/downloads' / f"Anthropic.claude-code-{lock['version']}@linux-x64.vsix"
    if args.download:
        package.parent.mkdir(parents=True, exist_ok=True)
        if not package.exists() or digest(package) != lock['sha256']:
            temporary = package.with_suffix('.vsix.part')
            request = urllib.request.Request(lock['downloadUrl'], headers={'User-Agent': 'vstab-package-inspector/1'})
            with urllib.request.urlopen(request, timeout=90) as response, temporary.open('wb') as output:
                if not response.geturl().startswith('https://'):
                    raise RuntimeError('Refused an insecure package redirect')
                while chunk := response.read(1024 * 1024):
                    output.write(chunk)
            if digest(temporary) != lock['sha256']:
                temporary.unlink(missing_ok=True)
                raise RuntimeError('Publisher VSIX SHA-256 mismatch')
            temporary.replace(package)
        print(f'Verified unmodified package: {package}')
    if args.inspect:
        if not package.exists() or digest(package) != lock['sha256']:
            raise RuntimeError('Run --download first; the package must match the pinned hash')
        with zipfile.ZipFile(package) as archive:
            manifest = json.loads(archive.read('extension/package.json'))
            if manifest.get('publisher') != 'Anthropic' or manifest.get('name') != 'claude-code' or manifest.get('version') != lock['version']:
                raise RuntimeError('VSIX manifest identity does not match lock')
            output = ROOT / '.local/claude-inspection'
            output.mkdir(parents=True, exist_ok=True)
            for item in ['extension/package.json', 'extension/extension.js', 'extension/README.md']:
                (output / Path(item).name).write_bytes(archive.read(item))
            files = archive.namelist()
            report = {
                'id': lock['id'], 'version': manifest['version'], 'targetPlatform': lock['targetPlatform'],
                'sha256': lock['sha256'], 'main': manifest.get('main'), 'browser': manifest.get('browser'),
                'engine': manifest.get('engines', {}).get('vscode'),
                'proposedApis': manifest.get('enabledApiProposals', []),
                'untrustedWorkspaces': manifest.get('capabilities', {}).get('untrustedWorkspaces'),
                'commands': [item['command'] for item in manifest.get('contributes', {}).get('commands', [])],
                'nativeRuntimePaths': [name for name in files if ('/native-binary/' in name or '/bin/' in name) and not name.endswith('/')],
                'license': manifest.get('license'),
                'status': 'package inspection only; no installation, extension execution or authentication',
            }
            (output / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
            print(json.dumps(report, indent=2))
    if not args.download and not args.inspect:
        print(json.dumps(lock, indent=2))
    return 0


if __name__ == '__main__':
    try:
        raise SystemExit(main())
    except (OSError, ValueError, RuntimeError, zipfile.BadZipFile) as error:
        print(f'claude-package: {error}', file=sys.stderr)
        raise SystemExit(1)
