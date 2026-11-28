#!/usr/bin/env python3
"""Check exact-byte frontend omission without downloading or packaging a bundle."""
import hashlib
import io
from pathlib import Path
import runpy
import tarfile
import tempfile
import unittest

PACKAGER = runpy.run_path(str(Path(__file__).with_name('package-workbench.py')))


class SharedAssets(unittest.TestCase):
    def test_only_unchanged_bytes_on_both_architectures_are_omitted(self):
        with tempfile.TemporaryDirectory(prefix='vstab-shared-assets-') as directory:
            root = Path(directory)
            payloads = {'out/original.js': b'original', 'out/patched.js': b'original patched input',
                        'out/architecture.js': b'x64 only', 'resources/code-server/icon.svg': b'<svg/>'}
            archives = []
            for architecture in ['amd64', 'arm64']:
                archive = root / (architecture + '.tar.gz')
                prefix = 'code-server-test-linux-' + architecture + '/'
                with tarfile.open(archive, 'w:gz') as target:
                    for name, data in payloads.items():
                        if architecture == 'arm64' and name == 'out/architecture.js': data = b'ARM only'
                        source = 'src/browser/media/' + name.removeprefix('resources/code-server/') if name.startswith('resources/code-server/') else 'lib/vscode/' + name
                        member = tarfile.TarInfo(prefix + source); member.size = len(data)
                        target.addfile(member, io.BytesIO(data))
                archives.append(PACKAGER['original_asset_records'](archive, prefix))
            stage = root / 'stage'; files = {}
            for name, data in payloads.items():
                if name == 'out/patched.js': data = b'VStab-owned patched script'
                path = stage / 'static' / name; path.parent.mkdir(parents=True, exist_ok=True); path.write_bytes(data)
                files['static/' + name] = {'sha256': hashlib.sha256(data).hexdigest(), 'bytes': len(data)}
            omitted = PACKAGER['shared_backend_assets'](stage, files, *archives, '/stable-test/static/')
            self.assertEqual(set(omitted), {'static/out/original.js', 'static/resources/code-server/icon.svg'})
            self.assertEqual(omitted['static/out/original.js']['backendPath'], '/stable-test/static/out/original.js')
            self.assertEqual(omitted['static/resources/code-server/icon.svg']['backendPath'], '/_static/src/browser/media/icon.svg')
            self.assertFalse((stage / 'static/out/original.js').exists())
            self.assertTrue((stage / 'static/out/patched.js').is_file())
            self.assertTrue((stage / 'static/out/architecture.js').is_file())
            self.assertEqual(set(files), {'static/out/patched.js', 'static/out/architecture.js'})


if __name__ == '__main__':
    unittest.main()
