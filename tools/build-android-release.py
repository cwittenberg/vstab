#!/usr/bin/env python3
"""Build signed Google Play and direct-install artifacts without exposing passwords."""
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = 'com.vstab'
SIGNING_NAMES = ('ANDROID_KEYSTORE', 'ANDROID_KEYSTORE_PASSWORD',
                 'ANDROID_KEY_ALIAS', 'ANDROID_KEY_PASSWORD')


def private_file(path):
    path = Path(path).expanduser()
    info = path.stat()
    if info.st_uid != os.getuid() or info.st_mode & 0o077 or not path.is_file():
        raise ValueError('Signing configuration and key files must be owner-only files')
    return path


def build():
    environment = os.environ.copy()
    for name in ('ANDROID_HOME', 'JAVA_HOME'):
        if not environment.get(name):
            raise ValueError(f'Set {name} before building')
    provided = [bool(environment.get(name)) for name in SIGNING_NAMES]
    if any(provided) and not all(provided):
        raise ValueError('All four ANDROID signing variables must be provided together')
    if not any(provided):
        config_path = environment.get('VSTAB_SIGNING_CONFIG',
            str(Path.home() / '.local/state/vstab/android-publishing/signing.json'))
        config = json.loads(private_file(config_path).read_text())
        if config.get('package') != PACKAGE:
            raise ValueError(f'Signing configuration must identify package {PACKAGE}')
        password = private_file(config['passwordFile']).read_text().rstrip('\r\n')
        environment.update({
            'ANDROID_KEYSTORE': str(private_file(config['keystore']).resolve()),
            'ANDROID_KEYSTORE_PASSWORD': password,
            'ANDROID_KEY_ALIAS': config['alias'],
            'ANDROID_KEY_PASSWORD': password,
        })
    version_code = environment.get('VSTAB_VERSION_CODE', '4')
    version_name = environment.get('VSTAB_VERSION_NAME', '0.1.3')
    if not re.fullmatch(r'[1-9][0-9]*', version_code) or int(version_code) > 2100000000:
        raise ValueError('VSTAB_VERSION_CODE must be an integer from 1 to 2100000000')
    if not re.fullmatch(r'[0-9A-Za-z][0-9A-Za-z.+_-]*', version_name):
        raise ValueError('VSTAB_VERSION_NAME contains unsupported characters')
    environment['PATH'] = str(Path(environment['JAVA_HOME']) / 'bin') + os.pathsep + environment.get('PATH', '')
    subprocess.run([str(ROOT / 'tools/build-android.sh'),
                    'assembleRelease', 'bundleRelease', 'lintRelease'],
                   cwd=ROOT, env=environment, check=True)
    output = ROOT / 'android/app/build/outputs'
    bundle = output / 'bundle/release/app-release.aab'
    apk = output / 'apk/release/app-release.apk'
    signature = subprocess.run(['jarsigner', '-J-Duser.language=en', '-J-Duser.country=US',
                                '-verify', str(bundle)], env=environment,
                               capture_output=True, text=True, timeout=120)
    if signature.returncode or 'jar verified.' not in signature.stdout:
        raise ValueError('Android App Bundle signature verification failed')
    subprocess.run([str(Path(environment['ANDROID_HOME']) / 'build-tools/36.0.0/apksigner'),
                    'verify', str(apk)], env=environment, check=True)
    badging = subprocess.check_output([
        str(Path(environment['ANDROID_HOME']) / 'build-tools/36.0.0/aapt2'),
        'dump', 'badging', str(apk)], env=environment, text=True)
    package_line = next((line for line in badging.splitlines() if line.startswith('package: ')), '')
    identity = dict(re.findall(r"(\w+)='([^']*)'", package_line))
    expected = {'name': PACKAGE, 'versionCode': version_code, 'versionName': version_name}
    if any(identity.get(key) != value for key, value in expected.items()):
        raise ValueError(f'Built package identity does not match {PACKAGE} {version_name} (code {version_code})')
    mapping_entry = 'BUNDLE-METADATA/com.android.tools.build.obfuscation/proguard.map'
    symbols_prefix = 'BUNDLE-METADATA/com.android.tools.build.debugsymbols/'
    with zipfile.ZipFile(bundle) as archive:
        mapping = archive.read(mapping_entry)
        renamed = re.findall(rb'^app\.vstab\.([^\s]+) -> ([^\s]+):$', mapping, re.MULTILINE)
        if not any(b'app.vstab.' + before != after for before, after in renamed):
            raise ValueError('The bundle must contain the genuine R8 mapping for optimized app classes')
        symbol_entries = [name for name in archive.namelist()
                          if name.startswith(symbols_prefix) and not name.endswith('/')]
        if not symbol_entries or any(not archive.getinfo(name).file_size for name in symbol_entries):
            raise ValueError('The bundle must contain nonempty native symbol tables')
    destination = ROOT / 'dist'
    destination.mkdir(exist_ok=True)
    stem = f'vstab-{version_name}-{version_code}'
    checksums = []
    for extension, source in [('aab', bundle), ('apk', apk)]:
        target = destination / f'{stem}.{extension}'
        temporary = target.with_suffix(target.suffix + '.tmp')
        shutil.copyfile(source, temporary)
        temporary.replace(target)
        with target.open('rb') as stream:
            digest = hashlib.file_digest(stream, 'sha256').hexdigest()
        checksums.append(f'{digest}  {target.name}\n')
        print(target)
    mapping_path = destination / f'{stem}.mapping.txt'
    mapping_path.write_bytes(mapping)
    symbols_path = destination / f'{stem}.native-debug-symbols.zip'
    with zipfile.ZipFile(bundle) as archive, zipfile.ZipFile(symbols_path, 'w', zipfile.ZIP_DEFLATED) as symbols:
        for name in sorted(symbol_entries):
            symbols.writestr(name.removeprefix(symbols_prefix), archive.read(name))
    for diagnostic in [mapping_path, symbols_path]:
        with diagnostic.open('rb') as stream:
            digest = hashlib.file_digest(stream, 'sha256').hexdigest()
        checksums.append(f'{digest}  {diagnostic.name}\n')
        print(diagnostic)
    (destination / f'{stem}.SHA256SUMS').write_text(''.join(checksums))


if __name__ == '__main__':
    try:
        build()
    except (OSError, ValueError, KeyError, subprocess.SubprocessError) as error:
        print(f'Release build failed: {error}', file=sys.stderr)
        sys.exit(1)
