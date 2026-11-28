#!/usr/bin/env python3
"""Build the pinned real Linux kernel with only virtual-platform drivers."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / '.local/linux-runtime/guest'

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--arch', choices=['x86_64', 'arm64-v8a'], default='x86_64')
    args = parser.parse_args()
    lock = json.loads((ROOT / 'release/linux-guest.lock.json').read_text())
    source = lock['kernelSource']
    tools = OUT / 'build-tools'; tools.mkdir(parents=True, exist_ok=True)
    archive = tools / source['debianPackage']
    if not archive.exists():
        with urllib.request.urlopen(source['url'], timeout=180) as response, archive.open('wb') as target:
            while chunk := response.read(4 * 1024 * 1024): target.write(chunk)
    with archive.open('rb') as stream:
        if hashlib.file_digest(stream, 'sha256').hexdigest() != source['sha256']:
            raise RuntimeError('Pinned Linux source archive hash mismatch')
    destination = OUT / ('kernel-' + args.arch); destination.mkdir(parents=True, exist_ok=True)
    command = ['docker', 'run', '--rm',
        '--mount', f'type=bind,src={ROOT / "linux-runtime/guest"},dst=/guest-src,readonly',
        '--mount', f'type=bind,src={destination},dst=/out',
        '--mount', f'type=bind,src={archive},dst=/inputs/linux-source.deb,readonly',
        '-e', 'VSTAB_KERNEL_ARCH=' + ('arm64' if args.arch == 'arm64-v8a' else 'x86_64'),
        '-e', 'VSTAB_OUTPUT_UID=' + str(os.getuid()), '-e', 'VSTAB_OUTPUT_GID=' + str(os.getgid()),
        lock['architectures']['x86_64']['debianImage'], 'bash', '/guest-src/build-kernel.sh']
    with (destination / 'build.log').open('w') as log:
        subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, check=True)
    print(json.dumps({'architecture': args.arch, 'kernel': str(destination / 'kernel'), 'bytes': (destination / 'kernel').stat().st_size}))

if __name__ == '__main__': main()
