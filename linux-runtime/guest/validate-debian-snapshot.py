#!/usr/bin/env python3
"""Verify immutable official APT snapshots against the guest's recorded inputs."""
import argparse
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
import lzma
from pathlib import Path
import subprocess
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
WORK = ROOT / '.local/linux-runtime/guest/snapshot-validation'

def fetch(url, target):
    if not target.exists():
        with urllib.request.urlopen(url, timeout=60) as response: target.write_bytes(response.read())
    return target.read_bytes()

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--debian', default='20261128T084314Z')
    parser.add_argument('--security', default='20261128T112414Z')
    args = parser.parse_args(); WORK.mkdir(parents=True, exist_ok=True)
    lock = json.loads((ROOT / 'release/linux-guest.lock.json').read_text())['debianSnapshot']
    keyring = ROOT / '.local/linux-runtime/guest/x86_64/assembled/rootfs/usr/share/keyrings/debian-archive-keyring.gpg'
    records = []; tasks = []
    for archive, timestamp, suite in [('debian', args.debian, 'trixie'), ('debian', args.debian, 'trixie-updates'), ('debian-security', args.security, 'trixie-security')]:
        prefix = f'https://snapshot.debian.org/archive/{archive}/{timestamp}/dists/{suite}/'
        release = WORK / (suite + '.InRelease')
        signed = fetch(prefix + 'InRelease', release)
        signed_hash = hashlib.sha256(signed).hexdigest()
        if signed_hash != lock['signedReleaseSha256'][suite]: raise RuntimeError('Snapshot signed metadata differs from the release lock')
        subprocess.run(['gpgv', '--keyring', str(keyring), str(release)], check=True, stdout=subprocess.DEVNULL)
        checksums = {}; in_hashes = False
        for line in signed.decode().splitlines():
            if line == 'SHA256:': in_hashes = True; continue
            if in_hashes and not line.startswith(' '): break
            if in_hashes:
                digest, length, name = line.split(); checksums[name] = (digest, int(length))
        records.append({'archive': archive, 'timestamp': timestamp, 'suite': suite, 'inReleaseSha256': signed_hash})
        for architecture in ['amd64', 'arm64']:
            name = f'main/binary-{architecture}/Packages.xz'
            tasks.append((prefix + name, WORK / f'{suite}-{architecture}.Packages.xz', checksums[name]))
    def packages(task):
        url, destination, expected = task
        compressed = fetch(url, destination)
        if (hashlib.sha256(compressed).hexdigest(), len(compressed)) != expected: raise RuntimeError('Signed package index hash mismatch')
        return {line[8:] for line in lzma.decompress(compressed).decode().splitlines() if line.startswith('SHA256: ')}
    known = set()
    with ThreadPoolExecutor(max_workers=4) as executor:
        for result in executor.map(packages, tasks): known.update(result)
    unmatched = []
    for inventory in ROOT.glob('.local/linux-runtime/guest/*/assembled/debian-archives.sha256'):
        for line in inventory.read_text().splitlines():
            digest, filename = line.split(None, 1)
            if digest not in known: unmatched.append({'architecture': inventory.parents[1].name, 'file': Path(filename).name, 'sha256': digest})
    report = {'schema': 1, 'snapshots': records, 'allRecordedDownloadedPackagesMatch': not unmatched, 'unmatched': unmatched}
    (WORK / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report, indent=2))
    if unmatched: raise RuntimeError('The snapshot does not reproduce every recorded package input')

if __name__ == '__main__': main()
