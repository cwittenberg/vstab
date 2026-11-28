#!/usr/bin/env python3
"""Exercise release diagnostics with real stripped and unstripped Android ELF."""
import copy
import hashlib
import io
import json
import os
from pathlib import Path
import runpy
import shutil
import subprocess
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[1]
PREPARE = ROOT / 'scripts/prepare-android-native-symbols.py'
RELEASE = runpy.run_path(str(ROOT / 'tools/build-android-release.py'))
NDK = Path(os.environ.get('ANDROID_NDK_HOME',
    '/mnt/data2/webexe/.codex-test-artifacts/android-gateway-20261028/sdk/ndk/28.2.13676358'))
BIN = NDK / 'toolchains/llvm/prebuilt/linux-x86_64/bin'


class NativeSymbolPolicy(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if not (BIN / 'x86_64-linux-android29-clang').is_file():
            raise unittest.SkipTest('Set ANDROID_NDK_HOME to run real-ELF policy checks')
        cls.fixture = tempfile.TemporaryDirectory(prefix='vstab-native-symbol-fixture-')
        fixture = Path(cls.fixture.name)
        source = fixture / 'fixture.c'
        source.write_text('int preserved_symbol(int x) { return x + 1; }\n'
                          'int main(void) { return preserved_symbol(1); }\n')
        cls.original = fixture / 'unstripped.so'
        subprocess.run([str(BIN / 'x86_64-linux-android29-clang'), '-O0', '-g',
                        '-Wl,--build-id=sha1', '-Wl,-z,max-page-size=16384',
                        str(source), '-o', str(cls.original)], check=True)
        cls.stripped = fixture / 'stripped.so'
        subprocess.run([str(BIN / 'llvm-strip'), '--strip-unneeded',
                        str(cls.original), '-o', str(cls.stripped)], check=True)

    @classmethod
    def tearDownClass(cls):
        cls.fixture.cleanup()

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='vstab-native-policy-')
        self.addCleanup(self.temporary.cleanup)
        self.base = Path(self.temporary.name)
        self.relative = 'x86_64/libqemu-system.so'
        self.payload = self.base / 'jni' / self.relative
        self.payload.parent.mkdir(parents=True)
        shutil.copyfile(self.stripped, self.payload)
        self.manifest = self.base / 'host-manifest.json'
        self.manifest.write_text(json.dumps({'schema': 1, 'architectures': {'x86_64': {
            'executable': 'libqemu-system.so',
            'executableSha256': hashlib.sha256(self.payload.read_bytes()).hexdigest()}}}))
        self.report = self.base / 'coverage.json'
        self.output = self.base / 'symbols'

    def prepare(self, strict=False, with_unstripped=False, approved=True):
        if with_unstripped:
            source = self.base / 'unstripped' / self.relative
            source.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(self.original, source)
        command = ['python3', str(PREPARE), '--ndk', str(NDK), '--jni-root',
                   str(self.base / 'jni'), '--unstripped-root', str(self.base / 'unstripped'),
                   '--output', str(self.output), '--report', str(self.report)]
        if approved:
            command += ['--allow-stripped-manifest', str(self.manifest)]
        if strict:
            command += ['--require-symbols']
        result = subprocess.run(command, capture_output=True, text=True)
        return result, json.loads(self.report.read_text())

    def bundle(self, symbols=None, payload=None):
        buffer = io.BytesIO()
        with zipfile.ZipFile(buffer, 'w') as archive:
            archive.writestr('base/lib/' + self.relative,
                             self.payload.read_bytes() if payload is None else payload)
            if symbols is not None:
                archive.writestr(RELEASE['SYMBOLS_PREFIX'] + self.relative + '.sym', symbols)
        buffer.seek(0)
        archive = zipfile.ZipFile(buffer)
        self.addCleanup(archive.close)
        return archive

    def test_exact_prestripped_payload_passes_without_fake_archive(self):
        before = self.payload.read_bytes()
        result, report = self.prepare()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(report['coverage'], 'unavailable')
        self.assertEqual(report['debugSymbolLevel'], 'NONE')
        self.assertEqual(report['preparedSymbolFiles'], 0)
        self.assertEqual(list(self.output.rglob('*.sym')), [])
        self.assertEqual(self.payload.read_bytes(), before)
        archive = self.bundle()
        entries = RELEASE['validate_native_symbols'](archive, report, self.manifest,
                                                      expected_abi='x86_64')
        stale = self.base / 'release.native-debug-symbols.zip'
        stale.write_bytes(b'stale')
        outputs = RELEASE['write_native_diagnostics'](archive, self.base, 'release', entries, report)
        self.assertFalse(stale.exists())
        self.assertEqual(len(outputs), 1)
        self.assertIsNone(json.loads(outputs[0].read_text())['nativeSymbolArchive'])

    def test_strict_and_unapproved_prestripped_payloads_fail(self):
        result, report = self.prepare(strict=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(report['coverage'], 'unavailable')
        result, report = self.prepare(approved=False)
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(report['files'][self.relative]['allowedPrestripped'])

    def test_wrong_manifest_hash_is_rejected(self):
        manifest = json.loads(self.manifest.read_text())
        manifest['architectures']['x86_64']['executableSha256'] = '0' * 64
        self.manifest.write_text(json.dumps(manifest))
        result, _ = self.prepare()
        self.assertNotEqual(result.returncode, 0)

    def test_wrapper_rejects_tampering_wrong_abi_and_strict_zero_coverage(self):
        _, report = self.prepare()
        check = RELEASE['validate_native_symbols']
        with self.assertRaises(ValueError):
            check(self.bundle(), report, self.manifest, require_symbols=True)
        with self.assertRaises(ValueError):
            check(self.bundle(), report, self.manifest, expected_abi='arm64-v8a')
        with self.assertRaises(ValueError):
            check(self.bundle(payload=self.payload.read_bytes() + b'tampered'), report, self.manifest)
        with self.assertRaises(ValueError):
            check(self.bundle(symbols=b'fake'), report, self.manifest)

    def test_genuine_matching_symbols_pass_and_export(self):
        result, report = self.prepare(strict=True, with_unstripped=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(report['coverage'], 'complete')
        symbols = (self.output / (self.relative + '.sym')).read_bytes()
        archive = self.bundle(symbols=symbols)
        entries = RELEASE['validate_native_symbols'](archive, report, self.manifest,
                                                      require_symbols=True, expected_abi='x86_64')
        outputs = RELEASE['write_native_diagnostics'](archive, self.base, 'release', entries, report)
        self.assertEqual(len(outputs), 2)
        with zipfile.ZipFile(self.base / 'release.native-debug-symbols.zip') as exported:
            self.assertEqual(exported.read(self.relative + '.sym'), symbols)

    def test_renamed_stripped_elf_cannot_masquerade_as_symbols(self):
        _, report = self.prepare(strict=True, with_unstripped=True)
        fake = self.payload.read_bytes()
        forged = copy.deepcopy(report)
        forged['files'][self.relative]['symbolsSha256'] = hashlib.sha256(fake).hexdigest()
        with self.assertRaisesRegex(ValueError, 'genuine matching'):
            RELEASE['validate_native_symbols'](self.bundle(symbols=fake), forged, self.manifest)


if __name__ == '__main__':
    unittest.main()
