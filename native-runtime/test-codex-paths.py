#!/usr/bin/env python3
import runpy
from pathlib import Path
import unittest

PATCHER = runpy.run_path(str(Path(__file__).with_name('patch-codex-paths.py')))


class CodexPathTests(unittest.TestCase):
    def test_relocation_preserves_binary_length_and_unrelated_bytes(self):
        original = b'\x7fELF\0' + b'\0'.join([PATCHER['ORIGINAL']] * 3) + b'\0trailer'
        patched = PATCHER['patch'](original)
        self.assertEqual(len(patched), len(original))
        self.assertEqual(patched.count(PATCHER['APK_NAME']), 3)
        self.assertTrue(patched.endswith(b'\0trailer'))
        self.assertEqual(PATCHER['patch'](patched), patched)

    def test_unexpected_binary_or_changed_upstream_references_stop_packaging(self):
        for data in [b'script', b'\x7fELF', b'\x7fELF' + PATCHER['ORIGINAL'],
                     b'\x7fELF' + PATCHER['ORIGINAL'] * 4]:
            with self.assertRaises(ValueError):
                PATCHER['patch'](data)


if __name__ == '__main__':
    unittest.main()
