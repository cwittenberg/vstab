#!/usr/bin/env python3
"""Prepare a verified, generic Android QEMU host without a Termux installation.

The executable is installed read-only by Android's package manager. Its exact
ELF dependency closure and the selected machine's boot data are ordinary assets.
No publisher extension, guest executable, or guest filesystem is modified.
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
import zipfile

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / '.local/linux-runtime'
REPO = 'https://packages-cf.termux.dev/apt/termux-main'
KEY = 'CC72CF8BA7DBFA0182877D045A897D96E57CF20C'
ARCHES = {
    'x86_64': ('x86_64', 'qemu-system-x86-64-headless', 'qemu-system-x86_64'),
    'arm64-v8a': ('aarch64', 'qemu-system-aarch64-headless', 'qemu-system-aarch64'),
}
SYSTEM_LIBRARIES = {'libc.so', 'libdl.so', 'libm.so', 'liblog.so',
                    'libandroid.so', 'libstdc++.so'}


def sha(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def fetch(url, target, expected=None):
    if target.is_file() and (expected is None or sha(target) == expected):
        return
    target.parent.mkdir(parents=True, exist_ok=True)
    temporary = target.with_suffix(target.suffix + '.part')
    subprocess.run(['curl', '-fLsS', '--retry', '3', '--max-time', '180',
                    url, '-o', str(temporary)], check=True)
    if expected is not None and sha(temporary) != expected:
        temporary.unlink()
        raise RuntimeError('Package digest mismatch: ' + url)
    temporary.replace(target)


def index_entries(path):
    entries = {}
    for stanza in path.read_text().split('\n\n'):
        fields = dict(line.split(': ', 1) for line in stanza.splitlines()
                      if ': ' in line and not line.startswith(' '))
        if 'Package' in fields:
            entries.setdefault(fields['Package'], fields)
    return entries


def package_closure(entries, initial):
    result = {}
    pending = [initial, 'termux-licenses']
    while pending:
        name = pending.pop()
        if name in result:
            continue
        if name not in entries:
            raise RuntimeError('Unresolved signed package dependency: ' + name)
        item = entries[name]
        result[name] = {key: item[key] for key in
                        ['Package', 'Version', 'Filename', 'SHA256', 'Size']}
        for field in ['Pre-Depends', 'Depends']:
            for dependency in item.get(field, '').split(','):
                if not dependency.strip():
                    continue
                alternatives = [part.strip().split(' ')[0]
                                for part in dependency.split('|')]
                selected = next((value for value in alternatives if value in entries), None)
                if selected is None:
                    raise RuntimeError('Unresolved package alternatives: ' + dependency)
                pending.append(selected)
    return [result[name] for name in sorted(result)]


def resolve_lock(abis, index_cache):
    target = ROOT / 'release/android-qemu.lock.json'
    lock = json.loads(target.read_text()) if target.is_file() else {
        'schema': 1, 'source': REPO, 'signingKey': KEY, 'architectures': {}}
    for abi in abis:
        architecture, package, executable = ARCHES[abi]
        cache = OUT / 'cache' / architecture
        cache.mkdir(parents=True, exist_ok=True)
        url = f'{REPO}/dists/stable/main/binary-{architecture}/Packages'
        if index_cache is not None:
            for filename in ['Packages', 'InRelease']:
                shutil.copyfile(index_cache / architecture / filename, cache / filename)
        else:
            fetch(url, cache / 'Packages')
        environment = dict(os.environ)
        if index_cache is not None:
            environment['TERMUX_OFFLINE'] = '1'
        subprocess.run(['bash', str(ROOT / 'native-runtime/vendor/verify-termux-index.sh'),
                        url, str(cache / 'Packages')], env=environment, check=True)
        lock['architectures'][abi] = {
            'termuxArchitecture': architecture, 'executable': executable,
            'indexSha256': sha(cache / 'Packages'),
            'inReleaseSha256': sha(cache / 'InRelease'),
            'packages': package_closure(index_entries(cache / 'Packages'), package),
        }
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(lock, indent=2) + '\n')
    return lock


def elf_metadata(path, abi):
    metadata = subprocess.check_output(['readelf', '-hlWd', str(path)], text=True)
    machine = 'AArch64' if abi == 'arm64-v8a' else 'Advanced Micro Devices X86-64'
    if machine not in metadata:
        raise RuntimeError('Unexpected ELF architecture: ' + str(path))
    for line in metadata.splitlines():
        if line.strip().startswith('LOAD ') and int(line.split()[-1], 16) < 16384:
            raise RuntimeError('ELF does not support 16 KiB pages: ' + str(path))
    interpreter = re.search(r'Requesting program interpreter: (.+?)\]', metadata)
    if interpreter and interpreter[1] != '/system/bin/linker64':
        raise RuntimeError('Unexpected ELF interpreter: ' + str(path))
    soname = re.search(r'\(SONAME\).*?\[(.*?)\]', metadata)
    return {'needed': re.findall(r'Shared library: \[(.+?)\]', metadata),
            'soname': soname[1] if soname else path.name,
            'interpreter': interpreter[1] if interpreter else None}


def zip_tree(source, target):
    target.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(target, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for path in sorted(source.rglob('*')):
            if path.is_file():
                entry = zipfile.ZipInfo(str(path.relative_to(source)), (2026, 1, 1, 0, 0, 0))
                entry.compress_type = zipfile.ZIP_DEFLATED
                entry.external_attr = 0o100644 << 16
                archive.writestr(entry, path.read_bytes(), compresslevel=9)


def prepare(abi, info):
    cache = OUT / 'cache' / info['termuxArchitecture']
    def download(item):
        target = cache / 'debs' / Path(item['Filename']).name
        fetch(f"{REPO}/{item['Filename']}", target, item['SHA256'])
        return item, target
    with concurrent.futures.ThreadPoolExecutor(max_workers=12) as pool:
        packages = list(pool.map(download, info['packages']))
    extracted = cache / 'extracted'
    if extracted.exists():
        shutil.rmtree(extracted)
    extracted.mkdir(parents=True)
    prefix = extracted / 'data/data/com.termux/files/usr'
    work = OUT / 'host' / abi
    if work.exists():
        shutil.rmtree(work)
    work.mkdir(parents=True)
    for item, path in packages:
        subprocess.run(['dpkg-deb', '-x', str(path), str(extracted)], check=True)
        # Copyright paths can overlap; preserve each immediately after extraction.
        notice = prefix / 'share/doc' / item['Package'] / 'copyright'
        if notice.is_file():
            destination = work / 'licenses' / (item['Package'] + '.txt')
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(notice, destination)
    # Termux uses several upstream doc directory names; a package-name-only
    # copyright lookup misses full licences such as libssh/COPYING and lzo.
    docs = prefix / 'share/doc'
    for source in sorted(docs.rglob('*')):
        if source.is_file() and (source.name.lower().startswith(
                ('copying', 'copyright', 'license', 'licence'))
                or str(source.relative_to(docs)) == 'qemu/about/license.html'):
            destination = work / 'licenses/docs' / source.relative_to(docs)
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source, destination)
    common_licenses = prefix / 'share/licenses'
    if common_licenses.is_dir():
        shutil.copytree(common_licenses, work / 'licenses/common', symlinks=False)
    libraries = {}
    for path in sorted((prefix / 'lib').glob('*.so*')):
        if path.is_file() and path.read_bytes()[:4] == b'\x7fELF':
            metadata = elf_metadata(path, abi)
            libraries.setdefault(metadata['soname'], path)
    executable = prefix / 'bin' / info['executable']
    metadata = elf_metadata(executable, abi)
    if metadata['interpreter'] is None:
        raise RuntimeError('Expected Android dynamic PIE QEMU executable')
    jni = OUT / 'jniLibs' / abi
    jni.mkdir(parents=True, exist_ok=True)
    target = jni / 'libqemu-system.so'
    shutil.copyfile(executable, target)
    target.chmod(0o755)
    pending = metadata['needed'][:]
    copied = {}
    while pending:
        name = pending.pop()
        if name in copied or name in SYSTEM_LIBRARIES:
            continue
        source = libraries.get(name)
        if source is None:
            raise RuntimeError('QEMU library dependency missing: ' + name)
        data = elf_metadata(source, abi)
        destination = work / 'lib' / name
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, destination)
        copied[name] = {'sha256': sha(destination), 'bytes': destination.stat().st_size,
                        'needed': data['needed']}
        pending.extend(data['needed'])
    # Direct Linux boot uses SeaBIOS on the x86 PC; ARM virt needs no firmware.
    resources = ['bios-256k.bin', 'bios.bin', 'kvmvapic.bin', 'linuxboot_dma.bin',
                 'multiboot_dma.bin', 'pvh.bin'] if abi == 'x86_64' else []
    for filename in resources:
        source = prefix / 'share/qemu' / filename
        if not source.is_file():
            raise RuntimeError('Required QEMU PC boot resource missing: ' + filename)
        destination = work / 'share/qemu' / filename
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, destination)
    (work / 'share/qemu').mkdir(parents=True, exist_ok=True)
    # ZIPs omit empty directories. A marker keeps the explicit ARM resource
    # directory present even though direct virt kernel boot needs no firmware.
    (work / 'share/qemu/README.txt').write_text(
        'VStab supplies QEMU resources through an explicit -L path.\n')
    archive = OUT / 'assets/linux-runtime' / ('host-' + abi + '.zip')
    zip_tree(work, archive)
    result = {
        'executable': 'libqemu-system.so',
        'executableSha256': sha(target), 'executableBytes': target.stat().st_size,
        'archive': archive.name, 'archiveSha256': sha(archive),
        'archiveBytes': archive.stat().st_size, 'libraries': copied,
        'resources': resources, 'systemLibraries': sorted(SYSTEM_LIBRARIES),
        'launch': {'environment': {'LD_LIBRARY_PATH': '${HOST}/lib',
                                    'QEMU_MODULE_DIR': '${HOST}/modules'},
                   'arguments': ['-L', '${HOST}/share/qemu', '-no-user-config',
                                 '-nodefaults', '-display', 'none', '-accel', 'tcg']},
    }
    print(f'{abi}: executable {target.stat().st_size} bytes; {len(copied)} libraries; assets {archive.stat().st_size} bytes', flush=True)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--resolve-lock', action='store_true')
    parser.add_argument('--index-cache', type=Path, help='Already signed Packages/InRelease pairs to verify offline')
    parser.add_argument('--abi', action='append', choices=list(ARCHES), help='Default: both supported host ABIs')
    args = parser.parse_args()
    abis = args.abi or list(ARCHES)
    lock = resolve_lock(abis, args.index_cache) if args.resolve_lock else json.loads(
        (ROOT / 'release/android-qemu.lock.json').read_text())
    manifest_path = OUT / 'assets/linux-runtime/host-manifest.json'
    manifest = json.loads(manifest_path.read_text()) if manifest_path.is_file() else {
        'schema': 1, 'sourceLock': 'release/android-qemu.lock.json', 'architectures': {}}
    for abi in abis:
        manifest['architectures'][abi] = prepare(abi, lock['architectures'][abi])
    manifest['sourceLockSha256'] = sha(ROOT / 'release/android-qemu.lock.json')
    manifest['recipeSha256'] = sha(Path(__file__))
    helper = ROOT / 'linux-runtime/host/termux-launch.sh'
    helper_target = OUT / 'assets/linux-runtime/host/termux-launch.sh'
    helper_target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(helper, helper_target)
    manifest['termuxHelper'] = {'asset': 'linux-runtime/host/termux-launch.sh',
                               'sha256': sha(helper_target)}
    manifest_path.parent.mkdir(parents=True, exist_ok=True)
    manifest_path.write_text(json.dumps(manifest, indent=2) + '\n')


if __name__ == '__main__':
    main()
