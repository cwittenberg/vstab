#!/usr/bin/env python3
"""Check gallery target selection without downloading or changing an extension."""
import json
from pathlib import Path
import runpy
import subprocess
import unittest

PATCH = runpy.run_path(str(Path(__file__).with_name('patch-server.py')))


class ExtensionTargetTests(unittest.TestCase):
    def select(self, platform, arch, identifier, target):
        script = ('const process=' + json.dumps({'platform': platform, 'arch': arch}) + ';' +
                  PATCH['EXTENSION_TARGET_HELPER'] +
                  'console.log(JSON.stringify(__vstabExtensionTarget(' +
                  json.dumps(identifier) + ',' + json.dumps(target) + ')));')
        return json.loads(subprocess.check_output(['node', '-e', script], text=True))

    def test_claude_gets_its_public_musl_variant_on_both_android_abis(self):
        self.assertEqual(self.select('android', 'arm64', 'Anthropic.claude-code', 'linux-arm64'), 'alpine-arm64')
        self.assertEqual(self.select('android', 'x64', 'anthropic.claude-code', 'linux-x64'), 'alpine-x64')

    def test_codex_native_addons_and_remote_linux_keep_their_platform(self):
        for identifier in ['openai.chatgpt', 'vscode.git', 'another.publisher']:
            self.assertEqual(self.select('android', 'arm64', identifier, 'linux-arm64'), 'linux-arm64')
        self.assertEqual(self.select('linux', 'x64', 'Anthropic.claude-code', 'linux-x64'), 'linux-x64')
        self.assertEqual(self.select('android', 'unknown', 'Anthropic.claude-code', 'unknown'), 'unknown')

    def test_both_installability_and_version_selection_are_patched(self):
        source = ('async isExtensionPlatformCompatible(e){let n=await this.getTargetPlatform();return n}'
                  'async getCompatibleVersion(e,n,o,i){let s=await this.getTargetPlatform(),a=null;return s}')
        patched = PATCH['patch_extension_targets'](source)
        self.assertIn('let n=__vstabExtensionTarget(e.identifier.id,await this.getTargetPlatform());', patched)
        self.assertIn('let s=__vstabExtensionTarget(e.identifier.id,await this.getTargetPlatform()),a=null;', patched)

    def test_changed_or_ambiguous_upstream_stops_packaging(self):
        for source in ['', 'async isExtensionPlatformCompatible(e){let n=await this.getTargetPlatform();']:
            with self.assertRaises(ValueError):
                PATCH['patch_extension_targets'](source)


if __name__ == '__main__':
    unittest.main()
