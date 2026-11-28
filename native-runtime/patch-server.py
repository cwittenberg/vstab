#!/usr/bin/env python3
"""Adapt the pinned paired Code OSS server JS for an actual Bionic Node process.

Keeps process.platform as Android and retains the paired server protocol and
extension host.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import sys

BASE = Path(__file__).resolve().parent
PINS = json.loads((BASE / 'server-patches/pins.json').read_text())
MARKER = '/* vstab-android-server-v1 */'
IDENT = r'[A-Za-z_$][\w$]*'
PLATFORM = re.compile(r'(?P<flag>' + IDENT + r')=(?P<proc>' + IDENT + r')\.platform==="linux",(?P<snap>' + IDENT + r')=(?P=flag)&&')
USER_DATA = re.compile(r'case"linux":(?=' + IDENT + r'=process\.env\.XDG_CONFIG_HOME)')
WATCHDOG = re.compile(r'try\{(?P<body>(?P<binding>' + IDENT + r')=(?P<require>' + IDENT + r')\("@vscode/native-watchdog"\),(?P=binding)\.start\((?P<init>' + IDENT + r')\.parentPid\))\}catch')
def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def vs_root(root: Path) -> Path:
    for candidate in [root / 'lib/vscode', root / 'vscode', root]:
        if (candidate / 'out/server-main.js').is_file():
            return candidate
    raise ValueError('Expected an extracted code-server root or its lib/vscode directory')


def patch_file(relative: str, source: str) -> tuple[str, dict]:
    counts = {}
    if relative.startswith('out/'):
        def platform(match):
            return (match['flag'] + '=(' + match['proc'] + '.platform==="linux"||'
                    + match['proc'] + '.platform==="android"),' + match['snap'] + '=' + match['flag'] + '&&')
        source, counts['androidLinuxShape'] = PLATFORM.subn(platform, source)
        if counts['androidLinuxShape'] != 1:
            raise ValueError(f'{relative}: expected one common-platform branch')
        source, counts['androidUserDataPath'] = USER_DATA.subn('case"linux":case"android":', source)
        expected_user_data = 1 if relative in ['out/server-main.js', 'out/vs/platform/terminal/node/ptyHostMain.js'] else 0
        if counts['androidUserDataPath'] != expected_user_data:
            raise ValueError(f'{relative}: unexpected user-data platform switch count')
        source, counts['optionalNativeWatchdogGuard'] = WATCHDOG.subn(
            lambda match: 'try{if(process.platform!=="android"){' + match['body'] + '}}catch', source)
        expected_watchdog = 1 if relative == 'out/vs/workbench/api/node/extensionHostProcess.js' else 0
        if counts['optionalNativeWatchdogGuard'] != expected_watchdog:
            raise ValueError(f'{relative}: unexpected optional watchdog load count')
    else:
        needle = 'export function binPathFor({ os, arch }) {'
        if source.count(needle) != 1:
            raise ValueError('Cannot identify the pinned ripgrep export')
        source = source.replace(needle, needle + '''
    if (os === 'android') {
        const nativePath = process.env.VSTAB_RIPGREP_PATH;
        if (!nativePath || !path.isAbsolute(nativePath)) {
            throw new Error('VSTAB_RIPGREP_PATH must name the bundled Android-native ripgrep executable');
        }
        return nativePath;
    }''')
        counts['androidRipgrepPath'] = 1
    if source.startswith('#!'):
        first, rest = source.split('\n', 1)
        return first + '\n' + MARKER + '\n' + rest, counts
    return MARKER + '\n' + source, counts


def apply(root: Path, dry_run=False) -> dict:
    root = vs_root(root)
    manifest_path = root / '.vstab-native-server-patches.json'
    if manifest_path.exists():
        report = json.loads(manifest_path.read_text())
        if report.get('codeServerVersion') != PINS['codeServerVersion']:
            raise ValueError('A different native server patch manifest already exists')
        records = report.get('files', [])
        if {item['path'] for item in records} != set(PINS['files']):
            raise ValueError('Native patch manifest does not describe the pinned file set')
        for item in records:
            if item['originalSha256'] != PINS['files'][item['path']] or sha((root / item['path']).read_bytes()) != item['patchedSha256']:
                raise ValueError('Native-patched server changed after qualification: ' + item['path'])
        return {**report, 'alreadyApplied': True}

    plans = []
    for relative, expected in PINS['files'].items():
        path = root / relative
        original = path.read_bytes()
        if sha(original) != expected:
            raise ValueError(f'{relative}: source hash mismatch; use the exact pinned paired server (or re-extract a partial patch)')
        changed, counts = patch_file(relative, original.decode())
        payload = changed.encode()
        plans.append((path, payload, {'path': relative, 'originalSha256': expected, 'patchedSha256': sha(payload), 'changes': counts}))

    report = {
        'schema': 1,
        'codeServerVersion': PINS['codeServerVersion'],
        'codeVersion': PINS['codeVersion'],
        'codeCommit': PINS['codeCommit'],
        'platform': 'Android Bionic; process.platform remains android',
        'processModel': 'ordinary native Node child_process.fork; readonly process.execPath required',
        'ripgrepEnv': 'VSTAB_RIPGREP_PATH=applicationInfo.nativeLibraryDir/librg.so',
        'files': [record for _, _, record in plans],
        'dryRun': dry_run,
        'deviceQualification': 'pending actual Android startup, extension-host and PTY tests'
    }
    if not dry_run:
        for path, payload, _ in plans:
            temporary = path.with_name(path.name + '.vstab-tmp')
            temporary.write_bytes(payload)
            temporary.replace(path)
        manifest_path.write_text(json.dumps(report, indent=2) + '\n')
    return report


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('server_root', type=Path)
    parser.add_argument('--manifest-out', '--manifestout', dest='manifest_out', type=Path)
    parser.add_argument('--dry-run', action='store_true')
    args = parser.parse_args()
    report = apply(args.server_root, args.dry_run)
    serialized = json.dumps(report, indent=2) + '\n'
    if args.manifest_out:
        args.manifest_out.parent.mkdir(parents=True, exist_ok=True)
        args.manifest_out.write_text(serialized)
    print(serialized, end='')
    return 0


if __name__ == '__main__':
    try:
        raise SystemExit(main())
    except (OSError, ValueError, KeyError) as error:
        print('vstab native server patch: ' + str(error), file=sys.stderr)
        raise SystemExit(1)
