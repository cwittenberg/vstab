#!/usr/bin/env python3
"""Build pinned upstream OpenSSH clients for the app's Android HOME.

Termux injects its own fixed home directory into its client binaries, so those
cannot read an Android profile's app-private SSH config. Build the unchanged
portable clients with NDK headers; the SSH-only passwd shim supplies HOME.
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
import tarfile

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / '.local/android-runtime'
VERSION = '10.6p2'
SHA256 = '9ae44872090cf2f06b3cf9ff147e7f16f309671340763ed2f99248398ae29d5b'
URL = f'https://ftp.openbsd.org/pub/OpenBSD/OpenSSH/portable/openssh-{VERSION}.tar.gz'
ARCHES = {'arm64-v8a': 'aarch64', 'x86_64': 'x86_64'}
TOOLS = {'ssh': 'client', 'ssh-keygen': 'keygen', 'ssh-agent': 'agent', 'ssh-add': 'add'}


def sha(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def validate(binary, abi):
    metadata = subprocess.check_output(['readelf', '-hlW', str(binary)], text=True)
    machine = 'AArch64' if abi == 'arm64-v8a' else 'Advanced Micro Devices X86-64'
    if machine not in metadata or 'Requesting program interpreter: /system/bin/linker64' not in metadata:
        raise RuntimeError('OpenSSH is not the expected Android ELF: ' + str(binary))
    for line in metadata.splitlines():
        if line.strip().startswith('LOAD ') and int(line.split()[-1], 16) < 16384:
            raise RuntimeError('OpenSSH ELF is not 16KB aligned: ' + str(binary))
    if b'/data/data/com.termux/files/home' in binary.read_bytes():
        raise RuntimeError('OpenSSH still contains another app\'s fixed HOME')
    provided = {p.name: p for directory in [OUT / 'jniLibs' / abi, OUT / 'usr' / abi / 'lib']
                for p in directory.iterdir() if p.is_file()}
    system = {'libc.so', 'libdl.so', 'libm.so', 'liblog.so', 'libandroid.so', 'libstdc++.so'}
    queue = [binary]; seen = set()
    while queue:
        path = queue.pop()
        if path.name in seen:
            continue
        seen.add(path.name)
        dynamic = subprocess.check_output(['readelf', '-d', str(path)], text=True)
        for dependency in re.findall(r'Shared library: \[(.+?)\]', dynamic):
            if dependency in provided:
                queue.append(provided[dependency])
            elif dependency not in system:
                raise RuntimeError('Missing OpenSSH library: ' + dependency)


def build(abi, architecture, ndk, archive):
    prefix = OUT / 'cache' / architecture / 'extracted/data/data/com.termux/files/usr'
    if not (prefix / 'include/openssl/opensslv.h').is_file():
        raise RuntimeError('Prepare verified Android dependency headers first')
    work = OUT / 'openssh-build' / abi
    if work.exists():
        shutil.rmtree(work)
    work.mkdir(parents=True)
    with tarfile.open(archive) as source:
        source.extractall(work, filter='data')
    source = work / ('openssh-' + VERSION)
    # Bionic implements bzero as a function-like macro, so it cannot be taken
    # as a function pointer. Keep upstream's volatile-call erasure semantics
    # using memset, as the Android OpenSSH package does.
    erase = source / 'openbsd-compat/explicit_bzero.c'
    text = erase.read_text()
    for before, after in [
        ('static void (* volatile ssh_bzero)(void *, size_t) = bzero;',
         'static void *(* volatile ssh_memset)(void *, int, size_t) = memset;'),
        ('ssh_bzero(p, n);', 'ssh_memset(p, 0, n);'),
    ]:
        if text.count(before) != 1:
            raise RuntimeError('Upstream secure-erasure implementation changed')
        text = text.replace(before, after, 1)
    erase.write_text(text)
    toolchain = ndk / 'toolchains/llvm/prebuilt/linux-x86_64/bin'
    environment = dict(os.environ)
    environment.update({
        'CC': str(toolchain / (architecture + '-linux-android30-clang')),
        'AR': str(toolchain / 'llvm-ar'), 'RANLIB': str(toolchain / 'llvm-ranlib'),
        'CFLAGS': '-O2 -fPIE -Dfd_mask=int -DHAVE_ATTRIBUTE__SENTINEL__=1',
        'CPPFLAGS': '-I' + str(prefix / 'include'),
        'LDFLAGS': '-L' + str(prefix / 'lib') + ' -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384',
        'ac_cv_func_endgrent': 'yes', 'ac_cv_func_getlastlogxbyname': 'no',
        'ac_cv_func_bzero': 'yes',
        'ac_cv_func_fmt_scaled': 'no', 'ac_cv_func_readpassphrase': 'no',
        'ac_cv_func_strnvis': 'no', 'ac_cv_search_getrrsetbyname': 'no',
        'ac_cv_member_struct_passwd_pw_gecos': 'no',
    })
    with (work / 'build.log').open('w') as log:
        subprocess.run(['./configure', '--host=' + architecture + '-linux-android',
            '--prefix=/usr', '--sysconfdir=/etc/ssh', '--with-default-path=/system/bin',
            '--without-pam', '--without-ssh1', '--with-ldns', '--disable-lastlog', '--disable-utmp',
            '--disable-utmpx', '--disable-wtmp', '--disable-wtmpx', '--disable-libutil',
            '--disable-pututline', '--disable-pututxline', '--without-openssl-header-check',
            '--without-zlib-version-check'], cwd=source, env=environment,
            stdout=log, stderr=subprocess.STDOUT, check=True)
        subprocess.run(['make', '-j4', *TOOLS], cwd=source, env=environment,
            stdout=log, stderr=subprocess.STDOUT, check=True)
    jni = OUT / 'jniLibs' / abi
    records = {}
    for name, filename in TOOLS.items():
        target = jni / ('libopenssh-' + filename + '.so')
        shutil.copyfile(source / name, target)
        target.chmod(0o755)
        subprocess.run([str(toolchain / 'llvm-strip'), '--strip-unneeded', str(target)], check=True)
        validate(target, abi)
        records[target.name] = {'sha256': sha(target), 'bytes': target.stat().st_size}
    notice = OUT / 'usr' / abi / 'share/vstab/notices/openssh-portable-LICENCE.txt'
    notice.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(source / 'LICENCE', notice)
    print('Built upstream Android OpenSSH clients: ' + abi, flush=True)
    return abi, records


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--ndk', type=Path, default=os.environ.get('ANDROID_NDK_HOME'))
    args = parser.parse_args()
    if args.ndk is None:
        parser.error('Pass --ndk or set ANDROID_NDK_HOME')
    archive = OUT / 'cache' / ('openssh-' + VERSION + '.tar.gz')
    if not archive.is_file():
        archive.parent.mkdir(parents=True, exist_ok=True)
        subprocess.run(['curl', '-fLsS', '--retry', '3', URL, '-o', str(archive)], check=True)
    if sha(archive) != SHA256:
        raise RuntimeError('Upstream OpenSSH source hash mismatch')
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        results = list(pool.map(lambda item: build(*item, args.ndk, archive), ARCHES.items()))
    # These archives also carry the complete upstream licence, and their hashes
    # must change together with the executable provenance below.
    import runpy
    packager = runpy.run_path(str(ROOT / 'scripts/prepare-android-runtime.py'))
    manifest_path = OUT / 'assets/local-runtime/runtime.json'
    manifest = json.loads(manifest_path.read_text())
    for abi, records in results:
        data = OUT / 'assets/local-runtime' / ('usr-' + abi + '.zip')
        packager['zip_tree'](OUT / 'usr' / abi, data)
        manifest['architectures'][abi]['usrSha256'] = sha(data)
        manifest['architectures'][abi]['nativeFiles'].update(records)
    manifest_path.write_text(json.dumps(manifest, indent=2) + '\n')
    (ROOT / 'release/android-openssh.lock.json').write_text(json.dumps({
        'schema': 1, 'version': VERSION, 'source': URL, 'sha256': SHA256,
        'ndkVersion': args.ndk.name, 'outputs': dict(results),
        'recipeSha256': sha(Path(__file__)),
        'home': 'SSH-only getpwuid/getpwnam shim; no compiled app/user path',
    }, indent=2) + '\n')


if __name__ == '__main__':
    main()
