#!/usr/bin/env python3
"""Load the APK's readonly Bionic addons without global symlink-resolution flags.

The `.node` file in the private backend is a link to an APK `lib*.so`. Node's
ordinary require resolves that link first, then incorrectly treats the `.so`
suffix as JavaScript. Android-only branches use Node's actual native loader on
the logical addon pathname. Other platforms retain their original require path.
Always apply to a fresh verified code-server 4.146.0 extraction.
"""
import argparse
import hashlib
import json
from pathlib import Path

PATCHES = {
 'lib/vscode/node_modules/node-pty/lib/utils.js': (
  'ad1377e91a931958b5f6e235f8fb10e3c9fd8cb347531e27ed144341f0009dc7',
  [('require("".concat(dir, "/").concat(name, ".node"))',
    '__vstabNativeBinding("".concat(dir, "/").concat(name, ".node"))')]),
 'lib/vscode/node_modules/@parcel/watcher/index.js': (
  '7028a46ab84bc5b0effeb7b6781b1f1375f1762c18de089b74a3d9f8cc742224',
  [("require('./build/Release/watcher.node')", "__vstabNativeBinding('./build/Release/watcher.node')"),
   ("require('./build/Debug/watcher.node')", "__vstabNativeBinding('./build/Debug/watcher.node')")]),
 'lib/vscode/node_modules/@vscode/sqlite3/lib/sqlite3-binding.js': (
  'a008802bb74c13937011d4b4546f61d439b89ae7b609088863ba185255a40457',
  [("require('../build/Release/vscode-sqlite3.node')", "__vstabNativeBinding('../build/Release/vscode-sqlite3.node')")]),
 'node_modules/argon2/argon2.cjs': (
  '8dd7a74ee44c56d6816d9a0439692df148199ab25db1130c871f638f8098d2da',
  [('gypBuild(__dirname)', '(process.platform === "android" ? __vstabNativeBinding("./build/Release/argon2.node") : gypBuild(__dirname))')]),
}

HELPER = '''
// vstab: APK native addons have readonly .so names; retain their .node loading semantics.
function __vstabNativeBinding(request) {
    if (process.platform !== "android") return require(request);
    const binding = { exports: {} };
    process.dlopen(binding, require("node:path").resolve(__dirname, request));
    return binding.exports;
}
'''

def sha(data): return hashlib.sha256(data).hexdigest()

def patch(root):
    planned = []
    for relative, (expected, replacements) in PATCHES.items():
        path = root / relative
        original = path.read_bytes()
        if sha(original) != expected:
            raise RuntimeError(f'Addon loader differs from pinned code-server 4.146.0: {relative}')
        source = original.decode('utf-8')
        for old, new in replacements:
            if source.count(old) != 1:
                raise RuntimeError(f'Expected one exact loader expression: {relative}')
            source = source.replace(old, new)
        if source.startswith('"use strict";\n'):
            source = source.replace('"use strict";\n', '"use strict";\n' + HELPER, 1)
        else:
            source = HELPER + source
        patched = source.encode('utf-8')
        planned.append((path, patched, {'path': relative, 'originalSha256': expected,
                                       'patchedSha256': sha(patched)}))
    # Validate all four original files before changing any of them.
    for path, patched, record in planned: path.write_bytes(patched)
    return {'schema': 1, 'codeServerVersion': '4.146.0', 'androidOnly': True,
            'mechanism': 'process.dlopen on original .node path into readonly APK native library',
            'preserveSymlinksRequired': False, 'files': [item[2] for item in planned]}

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('root', type=Path)
    parser.add_argument('--manifest-out', type=Path, required=True)
    args = parser.parse_args()
    result = patch(args.root)
    args.manifest_out.write_text(json.dumps(result, indent=2) + '\n')
    print('Patched four exact Android native addon loaders; no global symlink flags required.')
