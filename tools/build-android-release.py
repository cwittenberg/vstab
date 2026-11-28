#!/usr/bin/env python3
"""Build signed Google Play and direct-install artifacts without exposing passwords."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import runpy
import shutil
import subprocess
import sys
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = 'com.vstab'
SIGNING_NAMES = ('ANDROID_KEYSTORE', 'ANDROID_KEYSTORE_PASSWORD',
                 'ANDROID_KEY_ALIAS', 'ANDROID_KEY_PASSWORD')
SYMBOLS_PREFIX = 'BUNDLE-METADATA/com.android.tools.build.debugsymbols/'
SYMBOL_TOOLS = runpy.run_path(str(ROOT / 'scripts/prepare-android-native-symbols.py'))


def validate_native_symbols(archive, coverage, host_manifest, require_symbols=False, expected_abi=None):
    """Check actual bundled ELF bytes; absent diagnostics need an explicit pin."""
    allowed = SYMBOL_TOOLS['pinned_payloads'](host_manifest)
    if coverage.get('schema') != 1 or coverage.get('runtimePayloadModified') is not False:
        raise ValueError('The native symbol coverage report is missing or invalid')
    if coverage.get('allowStrippedManifestSha256') != hashlib.sha256(host_manifest.read_bytes()).hexdigest():
        raise ValueError('Native symbol coverage does not match the frozen host manifest')
    native = {}
    for name in archive.namelist():
        match = re.fullmatch(r'[^/]+/lib/([^/]+/[^/]+\.so)', name)
        if match:
            if match[1] in native:
                raise ValueError('Duplicate bundled native payload identity')
            native[match[1]] = name
    records = coverage.get('files', {})
    if not native or set(native) != set(records):
        raise ValueError('Native coverage does not describe the actual bundle payloads')
    if expected_abi and {name.split('/')[0] for name in native} != {expected_abi}:
        raise ValueError('The bundle native payloads do not match the selected release ABI')
    entries = [name for name in archive.namelist()
               if name.startswith(SYMBOLS_PREFIX) and not name.endswith('/')]
    expected_entries = []
    prepared = 0
    with tempfile.TemporaryDirectory(prefix='vstab-release-symbol-check-') as temporary:
        for relative, name in native.items():
            record = records[relative]
            payload = archive.read(name)
            digest = hashlib.sha256(payload).hexdigest()
            if digest != record.get('payloadSha256'):
                raise ValueError('Native symbol coverage payload hash differs from the bundle')
            if record.get('prepared') is not True:
                if require_symbols or not record.get('allowedPrestripped') or allowed.get(relative) != digest:
                    raise ValueError('A native payload lacks required genuine symbol coverage')
                continue
            prepared += 1
            symbol_name = SYMBOLS_PREFIX + relative + '.sym'
            if record.get('symbolFile') != relative + '.sym' or symbol_name not in entries:
                raise ValueError('The bundle lacks a reported native symbol table')
            data = archive.read(symbol_name)
            if not data or hashlib.sha256(data).hexdigest() != record.get('symbolsSha256'):
                raise ValueError('Bundled native symbol bytes differ from the coverage report')
            payload_path = Path(temporary) / 'payload.so'
            symbol_path = Path(temporary) / 'symbols.so'
            payload_path.write_bytes(payload); symbol_path.write_bytes(data)
            symbol_info = SYMBOL_TOOLS['elf_info'](symbol_path)
            if (not symbol_info['staticSymbolCount'] or not symbol_info['definedFunctionCount'] or
                    not SYMBOL_TOOLS['matches_payload'](symbol_info, SYMBOL_TOOLS['elf_info'](payload_path))):
                raise ValueError('Bundled symbols do not contain genuine matching native functions')
            expected_entries.append(symbol_name)
    if set(entries) != set(expected_entries):
        raise ValueError('The bundle contains unreported or unusable native symbol files')
    if (coverage.get('nativeFiles') != len(native) or coverage.get('preparedSymbolFiles') != prepared or
            coverage.get('unavailableSymbolFiles') != len(native) - prepared or
            coverage.get('nativeSymbolArchiveExpected') is not bool(prepared)):
        raise ValueError('Native symbol coverage totals differ from the bundle')
    expected_coverage = 'complete' if prepared == len(native) else 'partial' if prepared else 'unavailable'
    if (coverage.get('coverage') != expected_coverage or
            coverage.get('debugSymbolLevel') != ('SYMBOL_TABLE' if prepared else 'NONE')):
        raise ValueError('Native symbol coverage labels differ from the actual bundle')
    return sorted(entries)


def write_native_diagnostics(archive, destination, stem, symbol_entries, coverage):
    symbols_path = destination / f'{stem}.native-debug-symbols.zip'
    outputs = []
    if symbol_entries:
        with zipfile.ZipFile(symbols_path, 'w', zipfile.ZIP_DEFLATED) as symbols:
            for name in symbol_entries:
                symbols.writestr(name.removeprefix(SYMBOLS_PREFIX), archive.read(name))
        outputs.append(symbols_path)
    elif symbols_path.exists():
        # A repeat build of the same version must not leave an old ZIP that
        # falsely advertises diagnostics for this different native payload.
        symbols_path.unlink()
    report = {**coverage, 'nativeSymbolArchive': symbols_path.name if symbol_entries else None}
    coverage_path = destination / f'{stem}.native-symbol-coverage.json'
    coverage_path.write_text(json.dumps(report, indent=2) + '\n')
    return [coverage_path, *outputs]


def private_file(path):
    path = Path(path).expanduser()
    info = path.stat()
    if info.st_uid != os.getuid() or info.st_mode & 0o077 or not path.is_file():
        raise ValueError('Signing configuration and key files must be owner-only files')
    return path


def build(require_native_symbols=False):
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
    version_code = environment.get('VSTAB_VERSION_CODE', '5')
    version_name = environment.get('VSTAB_VERSION_NAME', '0.2.0')
    runtime_abi = environment.get('VSTAB_RUNTIME_ABI', 'arm64-v8a')
    if runtime_abi not in ('arm64-v8a', 'x86_64'):
        raise ValueError('VSTAB_RUNTIME_ABI must be arm64-v8a or x86_64')
    environment['VSTAB_RUNTIME_ABI'] = runtime_abi
    strict = environment.get('VSTAB_REQUIRE_NATIVE_SYMBOLS', '0')
    if strict not in ('0', '1'):
        raise ValueError('VSTAB_REQUIRE_NATIVE_SYMBOLS must be 0 or 1')
    require_native_symbols = require_native_symbols or strict == '1'
    environment['VSTAB_REQUIRE_NATIVE_SYMBOLS'] = '1' if require_native_symbols else '0'
    if not re.fullmatch(r'[1-9][0-9]*', version_code) or int(version_code) > 2100000000:
        raise ValueError('VSTAB_VERSION_CODE must be an integer from 1 to 2100000000')
    if not re.fullmatch(r'[0-9A-Za-z][0-9A-Za-z.+_-]*', version_name):
        raise ValueError('VSTAB_VERSION_NAME contains unsupported characters')
    environment['VSTAB_VERSION_CODE'] = version_code
    environment['VSTAB_VERSION_NAME'] = version_name
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
    host_assets = Path(environment.get('VSTAB_LINUX_ASSETS', str(ROOT / '.local/linux-runtime/assets')))
    if not host_assets.is_absolute():
        host_assets = ROOT / 'android/app' / host_assets
    host_manifest = host_assets / 'linux-runtime/host-manifest.json'
    coverage_path = ROOT / 'android/app/build/reports/native-symbols-release.json'
    coverage = json.loads(coverage_path.read_text())
    with zipfile.ZipFile(bundle) as archive:
        mapping = archive.read(mapping_entry)
        renamed = re.findall(rb'^app\.vstab\.([^\s]+) -> ([^\s]+):$', mapping, re.MULTILINE)
        if not any(b'app.vstab.' + before != after for before, after in renamed):
            raise ValueError('The bundle must contain the genuine R8 mapping for optimized app classes')
        symbol_entries = validate_native_symbols(archive, coverage, host_manifest,
                                                 require_native_symbols, runtime_abi)
    destination = ROOT / 'dist'
    destination.mkdir(exist_ok=True)
    stem = f'vstab-{version_name}-{version_code}-{runtime_abi}'
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
    with zipfile.ZipFile(bundle) as archive:
        native_diagnostics = write_native_diagnostics(archive, destination, stem, symbol_entries, coverage)
    for diagnostic in [mapping_path, *native_diagnostics]:
        with diagnostic.open('rb') as stream:
            digest = hashlib.file_digest(stream, 'sha256').hexdigest()
        checksums.append(f'{digest}  {diagnostic.name}\n')
        print(diagnostic)
    (destination / f'{stem}.SHA256SUMS').write_text(''.join(checksums))


if __name__ == '__main__':
    try:
        parser = argparse.ArgumentParser(description=__doc__)
        parser.add_argument('--require-native-symbols', action='store_true',
                            help='Fail unless every native payload has genuine matching symbols')
        build(parser.parse_args().require_native_symbols)
    except (OSError, ValueError, KeyError, subprocess.SubprocessError) as error:
        print(f'Release build failed: {error}', file=sys.stderr)
        sys.exit(1)
