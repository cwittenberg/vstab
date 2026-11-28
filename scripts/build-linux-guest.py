#!/usr/bin/env python3
"""Build a real glibc Linux guest; no extension or publisher engine rewriting."""
import argparse
import fcntl
import gzip
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'linux-runtime/guest'
OUT = ROOT / '.local/linux-runtime/guest'
LOCK_PATH = ROOT / 'release/linux-guest.lock.json'


def sha(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--arch', choices=['x86_64', 'arm64-v8a'], default='x86_64')
    parser.add_argument('--disk-mib', type=int, default=2048)
    parser.add_argument('--server-cache', type=Path, default=Path('/home/zs/vspad/.local/downloads'))
    parser.add_argument('--files-source', type=Path)
    parser.add_argument('--files-binary', type=Path, help='Optional matching precompiled first-party file client for integration refresh')
    parser.add_argument('--refresh-tools-image', help='Host-only prepared build tool image')
    parser.add_argument('--reuse-rootfs', action='store_true')
    parser.add_argument('--refresh-integration', action='store_true', help='Repopulate the disk with current first-party integration and exact-file hardlink deduplication')
    parser.add_argument('--kernel-dir', type=Path, help='Built-in-driver kernel produced by linux-runtime/guest/build-kernel.py')
    parser.add_argument('--compression', choices=['xz', 'gzip'], default='xz')
    parser.add_argument('--assets-output', type=Path, default=OUT / 'assets')
    args = parser.parse_args()
    lock = json.loads(LOCK_PATH.read_text())
    info = lock['architectures'][args.arch]
    work = OUT / args.arch
    inputs = work / 'inputs'
    assembled = work / 'assembled'
    inputs.mkdir(parents=True, exist_ok=True)
    assembled.mkdir(exist_ok=True)
    archive = args.server_cache / info['serverArchive']
    if not archive.exists():
        archive = inputs / info['serverArchive']
        if not archive.exists():
            with urllib.request.urlopen(info['serverUrl'], timeout=180) as response, archive.open('wb') as target:
                shutil.copyfileobj(response, target)
    if sha(archive) != info['serverSha256']:
        raise RuntimeError('Code OSS archive does not match the pinned publisher release')
    target_archive = inputs / 'code-server.tar.gz'
    if not target_archive.exists() or sha(target_archive) != info['serverSha256']:
        shutil.copyfile(archive, target_archive)
    if args.files_source:
        shutil.copyfile(args.files_source, inputs / 'android-files.c')
    if args.files_binary:
        header = args.files_binary.read_bytes()[:20]
        expected_machine = b'\xb7\x00' if args.arch == 'arm64-v8a' else b'\x3e\x00'
        if header[:4] != b'\x7fELF' or header[18:20] != expected_machine:
            raise RuntimeError('File client binary does not match the guest architecture')
        source_hash = args.files_binary.with_suffix('.source.sha256').read_text().strip()
        if source_hash != sha(inputs / 'android-files.c'):
            raise RuntimeError('File client binary source provenance mismatch')
        shutil.copyfile(args.files_binary, inputs / 'android-files')
    if args.kernel_dir:
        for name in ['kernel', 'kernel.config', 'kernel-version.txt']:
            shutil.copyfile(args.kernel_dir / name, inputs / name)
    if args.refresh_integration:
        source_snapshot = work / 'source-snapshot'
        if source_snapshot.exists(): shutil.rmtree(source_snapshot)
        shutil.copytree(SOURCE, source_snapshot, ignore=shutil.ignore_patterns('__pycache__'))
        build_node = OUT / 'x86_64/assembled/rootfs/opt/code-server/lib/node'
        command = ['docker', 'run', '--rm',
            '--mount', f'type=bind,src={source_snapshot},dst=/guest-src,readonly',
            '--mount', f'type=bind,src={assembled},dst=/out',
            '--mount', f'type=bind,src={inputs},dst=/inputs,readonly',
            '--mount', f'type=bind,src={build_node},dst=/build-node,readonly',
            '-e', 'VSTAB_DISK_MIB=' + str(args.disk_mib),
            '-e', 'VSTAB_BUILTIN_KERNEL=' + ('1' if args.kernel_dir else '0'),
            '-e', 'VSTAB_REFRESH_FILES=' + ('1' if args.files_binary else '0'),
            '-e', 'VSTAB_OUTPUT_UID=' + str(os.getuid()), '-e', 'VSTAB_OUTPUT_GID=' + str(os.getgid()),
            args.refresh_tools_image or lock['architectures']['x86_64']['debianImage'], 'bash', '/guest-src/refresh-rootfs.sh']
        with (work / 'refresh.log').open('w') as log:
            subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, check=True)
    elif not args.reuse_rootfs:
        source_snapshot = work / 'source-snapshot'
        if source_snapshot.exists():
            shutil.rmtree(source_snapshot)
        shutil.copytree(SOURCE, source_snapshot, ignore=shutil.ignore_patterns('__pycache__'))
        command = ['docker', 'run', '--rm', '--platform', info['dockerPlatform'],
                   '--mount', f'type=bind,src={source_snapshot},dst=/guest-src,readonly',
                   '--mount', f'type=bind,src={inputs},dst=/inputs,readonly',
                   '--mount', f'type=bind,src={assembled},dst=/out',
                   '-e', 'VSTAB_KERNEL_PACKAGE=' + info['kernelPackage'],
                   '-e', 'VSTAB_BUILTIN_KERNEL=' + ('1' if args.kernel_dir else '0'),
                   '-e', 'VSTAB_DISK_MIB=' + str(args.disk_mib),
                   '-e', 'VSTAB_OUTPUT_UID=' + str(os.getuid()),
                   '-e', 'VSTAB_OUTPUT_GID=' + str(os.getgid()),
                   info['debianImage'], '/bin/bash', '/guest-src/assemble-rootfs.sh']
        with (work / 'build.log').open('w') as log:
            subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, check=True)
    destination = args.assets_output / 'linux-runtime/guest' / args.arch
    destination.mkdir(parents=True, exist_ok=True)
    boot_files = ['kernel'] + (['initrd'] if (assembled / 'initrd').exists() else [])
    for name in boot_files:
        temporary = destination / (name + '.next')
        shutil.copyfile(assembled / name, temporary)
        temporary.replace(destination / name)
    disk = destination / ('rootfs.ext4.' + ('xz' if args.compression == 'xz' else 'gz'))
    temporary_disk = destination / (disk.name + '.next')
    with temporary_disk.open('wb') as raw:
        if args.compression == 'xz':
            subprocess.run(['xz', '-T4', '-6', '--lzma2=dict=16MiB', '--check=crc64', '-c', str(assembled / 'rootfs.ext4')], stdout=raw, check=True)
        else:
            with (assembled / 'rootfs.ext4').open('rb') as source:
                with gzip.GzipFile(filename='', fileobj=raw, mode='wb', compresslevel=6, mtime=0) as target:
                    shutil.copyfileobj(source, target, length=4 * 1024 * 1024)
    temporary_disk.replace(disk)
    manifest_path = args.assets_output / 'linux-runtime/guest-manifest.json'
    prefix = 'linux-runtime/guest/' + args.arch + '/'
    build_metadata = json.loads((assembled / 'rootfs/usr/local/share/vstab/guest-build.json').read_text())
    architecture = {
        'kernelAsset': prefix + 'kernel', 'kernelSha256': sha(destination / 'kernel'),
        'diskAsset': prefix + disk.name, 'diskSha256': sha(disk), 'diskCompression': args.compression,
        'diskUnpackedSha256': sha(assembled / 'rootfs.ext4'),
        'diskUnpackedBytes': (assembled / 'rootfs.ext4').stat().st_size,
        'guestMachine': info['guestMachine'], 'guestCpu': info['guestCpu'],
        'kernelArguments': 'root=/dev/vda rw console=' + info['console'] + ',115200 init=/sbin/vstab-init net.ifnames=0',
        'bootstrapTag': 'vstab-bootstrap', 'bootstrapFile': 'bootstrap.env',
        'kernelVersion': (assembled / 'kernel-version.txt').read_text().strip(),
        'nodeVersion': (assembled / 'node-version.txt').read_text().strip(),
        'codeServerArchiveSha256': info['serverSha256'],
        'kernelConfigSha256': sha(assembled / 'kernel.config'),
        'debianPackageInventorySha256': sha(assembled / 'packages.tsv'),
        'filesClientSourceSha256': (assembled / 'files-client-source.sha256').read_text().strip() if (assembled / 'files-client-source.sha256').exists() else None,
        'filesClientBinarySha256': sha(assembled / 'rootfs/usr/local/bin/vstab-android-files') if (assembled / 'rootfs/usr/local/bin/vstab-android-files').exists() else None,
        **{name: build_metadata[name] for name in ['guestBuildId', 'platformApiVersion', 'dataSchemaVersion', 'userDataMode', 'upgradePolicy', 'logicalDiskBytes', 'capabilities'] if name in build_metadata},
        'prototype': True,
    }
    if 'initrd' in boot_files:
        architecture.update({'initrdAsset': prefix + 'initrd', 'initrdSha256': sha(destination / 'initrd')})
    elif (destination / 'initrd').exists():
        (destination / 'initrd').unlink()
    with manifest_path.with_suffix('.lock').open('a') as publication_lock:
        fcntl.flock(publication_lock, fcntl.LOCK_EX)
        manifest = json.loads(manifest_path.read_text()) if manifest_path.exists() else {
            'schema': 1, 'serverVersion': lock['serverVersion'], 'codeVersion': lock['codeVersion'], 'architectures': {}}
        manifest['architectures'][args.arch] = architecture
        temporary_manifest = manifest_path.with_suffix('.json.next')
        temporary_manifest.write_text(json.dumps(manifest, indent=2) + '\n')
        temporary_manifest.replace(manifest_path)
    for obsolete in ['rootfs.ext4.gz', 'rootfs.ext4.xz']:
        if obsolete != disk.name and (destination / obsolete).exists():
            (destination / obsolete).unlink()
    print(json.dumps({'architecture': args.arch, 'manifest': str(manifest_path),
                      'compressedDiskBytes': disk.stat().st_size,
                      'kernelBytes': (destination / 'kernel').stat().st_size,
                      'initrdBytes': (destination / 'initrd').stat().st_size if 'initrd' in boot_files else 0}, indent=2))


if __name__ == '__main__':
    main()
