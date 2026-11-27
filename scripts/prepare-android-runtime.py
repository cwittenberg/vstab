#!/usr/bin/env python3
"""Prepare verified Android-native Node, shell and Git inputs for both APK ABIs.

Downloads happen on the build host. Android receives read-only executables in
jniLibs and an ordinary data ZIP; it never fetches or execs writable binaries.
"""
import argparse
import concurrent.futures
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / '.local/android-runtime'
REPO = 'https://packages-cf.termux.dev/apt/termux-main'
ARCHES = {'arm64-v8a': 'aarch64', 'x86_64': 'x86_64'}
PACKAGES = ['nodejs-lts', 'npm', 'bash', 'git', 'ripgrep', 'readline', 'ncurses',
    'libiconv', 'libandroid-support', 'libcurl', 'openssl', 'zlib', 'pcre2',
    'libexpat', 'libnghttp2', 'libnghttp3', 'libngtcp2', 'libssh2',
    'c-ares', 'libicu', 'libc++', 'libsqlite', 'ca-certificates', 'termux-licenses']

def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()

def fetch(url, target, expected=None):
    if target.is_file() and (expected is None or digest(target) == expected):
        return
    target.parent.mkdir(parents=True, exist_ok=True)
    temp = target.with_suffix(target.suffix + '.part')
    subprocess.run(['curl', '--fail', '--location', '--silent', '--show-error',
                    '--retry', '3', '--max-time', '180', '--output', str(temp), url], check=True)
    if expected is not None and digest(temp) != expected:
        raise RuntimeError(f'checksum mismatch: {url}')
    temp.replace(target)

def parse_index(path):
    result = {}
    for stanza in path.read_text().split('\n\n'):
        fields = dict(line.split(': ', 1) for line in stanza.splitlines()
                      if ': ' in line and not line.startswith(' '))
        if 'Package' in fields:
            result.setdefault(fields['Package'], fields)
    return result

def lock_packages():
    lock = {'schema': 1, 'source': REPO,
            'signingKey': 'CC72CF8BA7DBFA0182877D045A897D96E57CF20C', 'architectures': {}}
    for abi, arch in ARCHES.items():
        cache = OUT / 'cache' / arch
        url = f'{REPO}/dists/stable/main/binary-{arch}/Packages'
        fetch(url, cache / 'Packages')
        subprocess.run(['bash', str(ROOT / 'native-runtime/vendor/verify-termux-index.sh'),
                        url, str(cache / 'Packages')], check=True)
        index = parse_index(cache / 'Packages')
        packages = []
        for name in PACKAGES:
            item = index[name]
            packages.append({key: item[key] for key in
                             ['Package', 'Version', 'Filename', 'SHA256', 'Size']})
        lock['architectures'][abi] = {'termuxArchitecture': arch,
            'indexSha256': digest(cache / 'Packages'),
            'inReleaseSha256': digest(cache / 'InRelease'), 'packages': packages}
    path = ROOT / 'release/android-runtime.lock.json'
    path.write_text(json.dumps(lock, indent=2) + '\n')
    return lock

def copy_file(source, destination):
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(source, destination)

def prepare_abi(abi, info):
    cache = OUT / 'cache' / info['termuxArchitecture']
    def download(item):
        target = cache / 'debs' / Path(item['Filename']).name
        fetch(f"{REPO}/{item['Filename']}", target, item['SHA256'])
        return item, target
    with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
        downloaded = list(pool.map(download, info['packages']))
    extracted = cache / 'extracted'
    prefix = extracted / 'data/data/com.termux/files/usr'
    if extracted.exists():
        shutil.rmtree(extracted)
    extracted.mkdir()
    usr = OUT / 'usr' / abi
    if usr.exists():
        shutil.rmtree(usr)
    notices = OUT / 'usr' / abi / 'share/vstab/notices'
    for item, target in downloaded:
        subprocess.run(['dpkg-deb', '-x', str(target), str(extracted)], check=True)
        # Preserve copyright before another package can replace a shared path.
        copyright_file = prefix / 'share/doc' / item['Package'] / 'copyright'
        if copyright_file.is_file():
            copy_file(copyright_file, notices / (item['Package'] + '.txt'))
    jni = OUT / 'jniLibs' / abi
    jni.mkdir(parents=True, exist_ok=True)
    executables = {'node': 'libnode.so', 'bash': 'libbash.so',
                   'git': 'libgit.so', 'rg': 'librg.so'}
    records = {}
    for name, filename in executables.items():
        source = prefix / 'bin' / name
        copy_file(source, jni / filename)
        (jni / filename).chmod(0o755)
        records[name] = filename
    helper = prefix / 'libexec/git-core/git-remote-http'
    if helper.exists():
        copy_file(helper, jni / 'libgit-remote-curl.so')
        (jni / 'libgit-remote-curl.so').chmod(0o755)
    subprocess.run(['python3', str(ROOT / 'native-runtime/vendor/patch-default-shell.py'),
                    str(jni / 'libnode.so'), str(jni / 'libgit.so'),
                    str(jni / 'libgit-remote-curl.so')], check=True)
    # Dynamic libraries are data mapped by Android's linker, never execve targets.
    for source in (prefix / 'lib').glob('*.so*'):
        if source.is_file():
            metadata = subprocess.check_output(['readelf', '-d', str(source)], text=True)
            match = re.search(r'\(SONAME\).*?\[(.*?)\]', metadata)
            # Ship each library once under its actual loader name, avoiding
            # triplicate ICU data under the .so/.so.79/.so.79.1 aliases.
            copy_file(source, usr / 'lib' / (match[1] if match else source.name))
    for name in ['terminfo', 'git-core', 'licenses', 'doc/nodejs-lts']:
        source = prefix / 'share' / name
        if source.exists():
            shutil.copytree(source, usr / 'share' / name, dirs_exist_ok=True, symlinks=False)
    # npm remains JavaScript loaded by the bundled Node executable.
    npm = prefix / 'lib/node_modules/npm'
    if npm.exists():
        shutil.copytree(npm, usr / 'lib/node_modules/npm', dirs_exist_ok=True, symlinks=False)
    ssl = prefix / 'etc/tls'
    if ssl.exists():
        shutil.copytree(ssl, usr / 'etc/tls', dirs_exist_ok=True, symlinks=False)
    return {'executables': records, 'nodeVersion': next(p['Version'] for p in info['packages']
            if p['Package'] == 'nodejs-lts'), 'nativeFiles':
            {p.name: {'sha256': digest(p), 'bytes': p.stat().st_size} for p in sorted(jni.iterdir())}}

def zip_tree(source, output):
    output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(output, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
        for path in sorted(source.rglob('*')):
            if path.is_file():
                entry = zipfile.ZipInfo(str(path.relative_to(source)), (2026, 1, 1, 0, 0, 0))
                entry.compress_type = zipfile.ZIP_DEFLATED
                entry.external_attr = 0o100644 << 16
                archive.writestr(entry, path.read_bytes(), compresslevel=6)

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--resolve-lock', action='store_true', help='Resolve a new signed Termux package lock')
    args = parser.parse_args()
    lock = lock_packages() if args.resolve_lock else json.loads(
        (ROOT / 'release/android-runtime.lock.json').read_text())
    manifest = {'schema': 1, 'codeServerVersion': '4.146.0', 'codeVersion': '1.146.0',
                'codeCommit': '69047191fc427f2f92828279aae1d90d8eeda7ab', 'architectures': {}}
    for abi, info in lock['architectures'].items():
        print(f'Preparing Android {abi}', flush=True)
        manifest['architectures'][abi] = prepare_abi(abi, info)
        zip_path = OUT / 'assets/local-runtime' / f'usr-{abi}.zip'
        zip_tree(OUT / 'usr' / abi, zip_path)
        manifest['architectures'][abi]['usrArchive'] = zip_path.name
        manifest['architectures'][abi]['usrSha256'] = digest(zip_path)
    manifest_path = OUT / 'assets/local-runtime/runtime.json'
    manifest_path.parent.mkdir(parents=True, exist_ok=True)
    manifest_path.write_text(json.dumps(manifest, indent=2) + '\n')
    print('Android native inputs prepared. Build paired native addons and backend next.', flush=True)

if __name__ == '__main__':
    main()
