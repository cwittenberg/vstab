#!/usr/bin/env python3
"""Build read-only Android launchers for the public Claude Code extension."""
import argparse
import base64
import hashlib
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import tarfile
import time
import urllib.request
import zipfile
import zlib

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / '.local/android-runtime'
SOURCE = ROOT / 'native-runtime/claude'
LOCK = ROOT / 'release/android-claude.lock.json'
ARCHITECTURES = {'arm64-v8a': ('aarch64', '616ae350'), 'x86_64': ('x86_64', '6165ee59')}


def sha(data):
    return hashlib.sha256(data).hexdigest()


def fetch(url):
    with urllib.request.urlopen(url, timeout=90) as response:
        return response.read()


def gzip_streams(data, count):
    streams = []
    while data:
        decompressor = zlib.decompressobj(31)
        decompressor.decompress(data)
        if not decompressor.eof:
            raise ValueError('Incomplete Alpine gzip stream')
        length = len(data) - len(decompressor.unused_data)
        streams.append(data[:length])
        data = decompressor.unused_data
    if len(streams) != count:
        raise ValueError(f'Expected {count} Alpine streams, found {len(streams)}')
    return streams


def member(stream, name):
    with tarfile.open(fileobj=io.BytesIO(stream)) as archive:
        entries = [item for item in archive.getmembers() if item.name == name]
        if len(entries) != 1 or not entries[0].isfile():
            raise ValueError(f'Expected one regular {name} in Alpine stream')
        return archive.extractfile(entries[0]).read(), entries[0].mtime


def resolve():
    lock = {'schema': 1, 'branch': 'v3.23', 'architectures': {}}
    # Source-built x86_64 musl must keep the same Alpine security patches as the
    # binary package. A package refresh must not silently discard that pin.
    source_lock = json.loads(LOCK.read_text()).get('x86_64Source') if LOCK.exists() else None
    cache = OUT / 'claude-cache'
    cache.mkdir(parents=True, exist_ok=True)
    for abi, (arch, key) in ARCHITECTURES.items():
        mirror = f'https://dl-cdn.alpinelinux.org/alpine/v3.23/main/{arch}'
        index = fetch(mirror + '/APKINDEX.tar.gz')
        signature_stream, signed = gzip_streams(index, 2)
        key_name = '.SIGN.RSA.alpine-devel@lists.alpinelinux.org-' + key + '.rsa.pub'
        with tarfile.open(fileobj=io.BytesIO(signature_stream)) as archive:
            if archive.getnames() != [key_name]:
                raise ValueError('Alpine index does not name the pinned signing key')
        signature, _ = member(signature_stream, key_name)
        sig_path, signed_path = cache / f'{arch}.sig', cache / f'{arch}.signed'
        sig_path.write_bytes(signature)
        signed_path.write_bytes(signed)
        subprocess.run(['openssl', 'dgst', '-sha1', '-verify',
                        str(SOURCE / f'alpine-devel-{key}.rsa.pub'),
                        '-signature', str(sig_path), str(signed_path)], check=True)
        text, modified = member(signed, 'APKINDEX')
        if not -86400 <= time.time() - modified <= 30 * 86400:
            raise ValueError('Alpine signed index is stale or dated in the future')
        packages = [dict(line.split(':', 1) for line in block.splitlines() if ':' in line)
                    for block in text.decode().split('\n\n')]
        package = next(item for item in packages if item.get('P') == 'musl')
        if not package['V'].startswith('1.2.5-'):
            raise ValueError('Update musl notice and qualify the new libc version first')
        url = mirror + '/musl-' + package['V'] + '.apk'
        data = fetch(url)
        _, control, payload = gzip_streams(data, 3)
        checksum = 'Q1' + base64.b64encode(hashlib.sha1(control).digest()).decode()
        if checksum != package['C']:
            raise ValueError('Alpine package control differs from signed index')
        pkginfo, _ = member(control, '.PKGINFO')
        info = dict(line.split(' = ', 1) for line in pkginfo.decode().splitlines() if ' = ' in line)
        if sha(payload) != info.get('datahash'):
            raise ValueError('Alpine package payload differs from signed control')
        loader, _ = member(payload, f'lib/ld-musl-{arch}.so.1')
        (cache / f'musl-{arch}.apk').write_bytes(data)
        lock['architectures'][abi] = {
            'architecture': arch, 'version': package['V'], 'url': url,
            'apkSha256': sha(data), 'loaderSha256': sha(loader),
            'indexSha256': sha(index), 'signingKey': key_name,
        }
    if source_lock:
        if source_lock['alpinePackageVersion'] != lock['architectures']['x86_64']['version']:
            raise ValueError('Refresh the pinned x86_64 musl source and Alpine patches for the new package first')
        lock['x86_64Source'] = source_lock
    LOCK.write_text(json.dumps(lock, indent=2) + '\n')
    return lock


def add_notice(abi):
    destination = OUT / 'usr' / abi / 'share/vstab/notices/musl.txt'
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(SOURCE / 'COPYRIGHT.musl', destination)
    archive = OUT / 'assets/local-runtime' / f'usr-{abi}.zip'
    temporary = archive.with_suffix('.claude.zip')
    name = 'share/vstab/notices/musl.txt'
    with zipfile.ZipFile(archive) as original, zipfile.ZipFile(temporary, 'w') as updated:
        for entry in original.infolist():
            if entry.filename != name:
                updated.writestr(entry, original.read(entry.filename))
        entry = zipfile.ZipInfo(name, (2026, 1, 1, 0, 0, 0))
        entry.external_attr = 0o100644 << 16
        entry.compress_type = zipfile.ZIP_DEFLATED
        updated.writestr(entry, destination.read_bytes())
    temporary.replace(archive)
    return sha(archive.read_bytes())


def build_x86_64_loader(source, package, ndk):
    """Rebuild Alpine's patched musl with 16 KB ELF segments on Linux x86_64."""
    if source['alpinePackageVersion'] != package['version']:
        raise ValueError('Source-built musl and pinned Alpine package versions differ')
    cache = OUT / 'claude-cache' / 'musl-source'
    cache.mkdir(parents=True, exist_ok=True)

    def source_file(name, url, expected):
        path = cache / name
        if not path.exists() or hashlib.sha512(path.read_bytes()).hexdigest() != expected:
            data = fetch(url)
            if hashlib.sha512(data).hexdigest() != expected:
                raise ValueError('Pinned musl source digest differs: ' + name)
            path.write_bytes(data)
        return path

    archive = source_file('musl-' + source['version'] + '.tar.gz',
                          source['archive']['url'], source['archive']['sha512'])
    work = OUT / 'claude-build' / 'musl-x86_64'
    if work.exists():
        shutil.rmtree(work)
    work.mkdir(parents=True)
    with tarfile.open(archive) as tar:
        tar.extractall(work, filter='data')
    tree = work / ('musl-' + source['version'])
    for patch in source['patches']:
        path = source_file(patch['name'], source['patchBaseUrl'] + patch['name'], patch['sha512'])
        subprocess.run(['patch', '-p1', '--batch', '--forward', '-i', str(path)], cwd=tree, check=True)
    # Match Alpine's prepare(): these assembly implementations are deliberately
    # replaced by their portable C counterparts in the shipped package.
    for name in ['memcpy.s', 'memmove.s']:
        (tree / 'src/string/x86_64' / name).unlink()
    (tree / 'VERSION').write_text(source['version'] + '\n')
    llvm = ndk / 'toolchains/llvm/prebuilt/linux-x86_64/bin'
    # Linux musl's x86_64 ABI uses the 80-bit x87 long double ABI. Android's
    # compiler-rt uses a different ABI and omits the required __mulxc3 helper.
    # Use the Linux host compiler runtime, while keeping LLVM as the linker.
    libgcc = subprocess.check_output(['gcc', '-print-libgcc-file-name'], text=True).strip()
    environment = os.environ.copy()
    environment.update({
        'CC': str(llvm / 'clang') + ' --target=x86_64-linux-musl -fuse-ld=lld',
        'AR': str(llvm / 'llvm-ar'), 'RANLIB': str(llvm / 'llvm-ranlib'),
        'LIBCC': libgcc,
        'CFLAGS': '-g0 -ffile-prefix-map=' + str(tree) + '=.',
        'LDFLAGS': '-Wl,-soname,libc.musl-x86_64.so.1 -Wl,--build-id=sha1 '
                   '-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384',
        'SOURCE_DATE_EPOCH': '1767225600',
    })
    subprocess.run(['./configure', '--target=x86_64-linux-musl', '--prefix=/usr',
                    '--sysconfdir=/etc', '--disable-static'], cwd=tree, env=environment, check=True)
    subprocess.run(['make', '-s', '-j' + str(min(os.cpu_count() or 1, 8)), 'lib/libc.so'],
                   cwd=tree, env=environment, check=True)
    loader = tree / 'lib/libc.so'
    unstripped = OUT / 'unstripped/x86_64/libldmusl.so'
    unstripped.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(loader, unstripped)
    subprocess.run([str(llvm / 'llvm-strip'), '--strip-unneeded', str(loader)], check=True)
    return loader.read_bytes()


def build(abi, package, ndk, source=None):
    arch = package['architecture']
    cache = OUT / 'claude-cache' / f'musl-{arch}.apk'
    cache.parent.mkdir(parents=True, exist_ok=True)
    if not cache.exists() or sha(cache.read_bytes()) != package['apkSha256']:
        data = fetch(package['url'])
        if sha(data) != package['apkSha256']:
            raise ValueError('Pinned musl package digest differs: ' + abi)
        cache.write_bytes(data)
    _, _, payload = gzip_streams(cache.read_bytes(), 3)
    loader, _ = member(payload, f'lib/ld-musl-{arch}.so.1')
    if sha(loader) != package['loaderSha256']:
        raise ValueError('Pinned musl loader digest differs: ' + abi)
    if abi == 'x86_64':
        if source is None:
            raise ValueError('Missing pinned x86_64 musl sources for the 16 KB loader build')
        loader = build_x86_64_loader(source, package, ndk)
    jni = OUT / 'jniLibs' / abi
    jni.mkdir(parents=True, exist_ok=True)
    (jni / 'libldmusl.so').write_bytes(loader)
    compiler = ndk / 'toolchains/llvm/prebuilt/linux-x86_64/bin' / (arch + '-linux-android29-clang')
    flags = ['-O2', '-Wall', '-Wextra', '-Werror', '-Wl,--build-id=sha1',
             '-Wl,-z,max-page-size=16384', '-Wl,-z,common-page-size=16384']
    subprocess.run([str(compiler), '-shared', '-fPIC', '-nostdlib',
                    '-Wl,--pack-dyn-relocs=none', '-Wl,-soname,libseccomp-shim.so',
                    *flags, '-o', str(jni / 'libseccomp-shim.so'), str(SOURCE / 'seccomp-shim.c')], check=True)
    subprocess.run([str(compiler), '-fPIE', '-pie', *flags,
                    '-o', str(jni / 'libclaude-launch.so'), str(SOURCE / 'launcher.c')], check=True)
    outputs = {}
    for name in ['libldmusl.so', 'libseccomp-shim.so', 'libclaude-launch.so']:
        binary = jni / name
        binary.chmod(0o755)
        metadata = subprocess.check_output(['readelf', '-hlWd', str(binary)], text=True)
        machine = 'AArch64' if abi == 'arm64-v8a' else 'Advanced Micro Devices X86-64'
        if machine not in metadata:
            raise ValueError('Incorrect Claude launcher architecture: ' + name)
        if name == 'libseccomp-shim.so' and ('(NEEDED)' in metadata or 'ANDROID_RELR' in metadata):
            raise ValueError('Claude shim must remain freestanding with musl-compatible relocations')
        for line in metadata.splitlines():
            if line.strip().startswith('LOAD ') and int(line.split()[-1], 16) < 16384:
                raise ValueError('Incorrect Claude launcher segment alignment: ' + name)
        outputs[name] = {'sha256': sha(binary.read_bytes()), 'bytes': binary.stat().st_size}
    return outputs, add_notice(abi)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--ndk', type=Path, default=os.environ.get('ANDROID_NDK_HOME'))
    parser.add_argument('--resolve-lock', action='store_true')
    args = parser.parse_args()
    lock = resolve() if args.resolve_lock else json.loads(LOCK.read_text())
    if args.ndk is None:
        raise SystemExit('Pass --ndk or set ANDROID_NDK_HOME')
    manifest_path = OUT / 'assets/local-runtime/runtime.json'
    manifest = json.loads(manifest_path.read_text())
    for abi, package in lock['architectures'].items():
        outputs, usr_sha = build(abi, package, args.ndk, lock.get('x86_64Source'))
        manifest['architectures'][abi]['nativeFiles'].update(outputs)
        manifest['architectures'][abi]['usrSha256'] = usr_sha
    identity = json.dumps({'backendSha256': manifest['backendSha256'],
                           'architectures': manifest['architectures']}, sort_keys=True).encode()
    manifest['runtimeId'] = 'vstab-native-' + sha(identity)[:20]
    manifest_path.write_text(json.dumps(manifest, indent=2) + '\n')
    print('Built read-only Claude launchers, syscall adapters and musl loaders for both Android ABIs.')


if __name__ == '__main__':
    main()
