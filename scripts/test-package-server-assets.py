#!/usr/bin/env python3
"""Check shipped extension bytes and real immutable SSH installation behavior."""
import base64
import hashlib
import io
import json
from pathlib import Path
import runpy
import subprocess
import tarfile
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
PACKAGER = runpy.run_path(str(ROOT / 'scripts/package-server-assets.py'))


class ServerAssetsTest(unittest.TestCase):
    def test_payload_is_deterministic_and_exact_source_without_fixtures(self):
        first, manifest = PACKAGER['payload']()
        second, _ = PACKAGER['payload']()
        self.assertEqual(first, second)
        with tarfile.open(fileobj=io.BytesIO(first), mode='r:gz') as archive:
            members = archive.getmembers()
            self.assertTrue(all(m.isfile() for m in members))
            names = {m.name for m in members}
            self.assertIn('vstab-session-tools/extension.cjs', names)
            self.assertIn('vstab-compatibility/extension.js', names)
            self.assertFalse(any('/fixtures/' in n or '/test/' in n or n.endswith('.test.cjs') for n in names))
            for name, expected in PACKAGER['extension_files']().items():
                actual = archive.extractfile(name).read()
                self.assertEqual(actual, expected)
                self.assertEqual(manifest['files'][name]['sha256'], hashlib.sha256(actual).hexdigest())

    def test_generated_bootstrap_is_bounded_and_valid_shell(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary)
            metadata = PACKAGER['generate'](output)
            script = (output / 'server-bootstrap.sh').read_bytes()
            self.assertLess(len(script), 1024 * 1024)
            self.assertNotIn(PACKAGER['MARKER'].encode(), script)
            self.assertEqual(metadata['bootstrapSha256'], hashlib.sha256(script).hexdigest())
            subprocess.run(['sh', '-n', str(output / 'server-bootstrap.sh')], check=True)
            source = script.decode()
            self.assertLess(source.index('vstab_first_party_sha='), source.index('# Reuse a live healthy instance.'))

    def test_real_install_is_immutable_reusable_and_preserves_old_process(self):
        data, _ = PACKAGER['payload']()
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            base = root / 'server/4.146.0'
            upstream = base / 'lib/vscode/extensions/upstream/package.json'
            upstream.parent.mkdir(parents=True)
            upstream.write_text('{"name":"upstream"}\n')
            old = subprocess.Popen(['sleep', '30'])
            try:
                prefix = '''set -eu
umask 077
vstab_version=4.146.0
vstab_root="$1"
vstab_install="$vstab_root/server/$vstab_version"
vstab_stage=
vstab_error() { printf '%s\\n' "$1" >&2; exit 7; }
'''
                script = prefix + PACKAGER['install_script'](data) + '\nprintf "%s\\n" "$vstab_install"\n'
                result = subprocess.run(['sh', '-s', '--', str(root)], input=script, text=True, capture_output=True, check=True)
                installed = Path(result.stdout.strip())
                self.assertNotEqual(installed, base)
                self.assertIsNone(old.poll(), 'Preparing a feature build must leave old server/build processes alive')
                self.assertEqual(upstream.stat().st_ino, (installed / upstream.relative_to(base)).stat().st_ino)
                for name, expected in PACKAGER['extension_files']().items():
                    self.assertEqual((installed / 'lib/vscode/extensions' / name).read_bytes(), expected)
                identity = (installed / '.vstab-first-party-sha256').stat().st_ino
                again = subprocess.run(['sh', '-s', '--', str(root)], input=script, text=True, capture_output=True, check=True)
                self.assertEqual(again.stdout, result.stdout)
                self.assertEqual((installed / '.vstab-first-party-sha256').stat().st_ino, identity)
                self.assertFalse((base / 'lib/vscode/extensions/vstab-session-tools').exists())
                self.assertIsNone(old.poll())
            finally:
                old.terminate()
                old.wait(timeout=3)

    def test_payload_tampering_stops_before_copy_or_activation(self):
        data, _ = PACKAGER['payload']()
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            script = PACKAGER['install_script'](data)
            encoded = base64.b64encode(data).decode()
            script = script.replace(encoded[:100], ('A' if encoded[0] != 'A' else 'B') + encoded[1:100], 1)
            prefix = 'set -eu\nvstab_version=4.146.0\nvstab_root="$1"\nvstab_install="$1/base"\nvstab_error() { exit 7; }\n'
            (root / 'server').mkdir()
            result = subprocess.run(['sh', '-s', '--', str(root)], input=prefix + script, text=True, capture_output=True)
            self.assertEqual(result.returncode, 7)
            self.assertFalse(any(root.glob('server/4.146.0-vstab-*')))


if __name__ == '__main__':
    unittest.main()
