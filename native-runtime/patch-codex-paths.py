#!/usr/bin/env python3
"""Relocate Codex's sibling helper to its APK-executable filename.

Codex 0.172 locates this helper beside current_exe and exposes no path override.
Android extracts executable payloads only with lib*.so names. Both strings have
the same byte length, preserving ELF offsets and Rust's static string lengths.
The build downloads the original engine from the signed, pinned Termux lock.
"""
import argparse
import hashlib
from pathlib import Path

ORIGINAL = b'codex-code-mode-host'
APK_NAME = b'libcodex-modehost.so'
EXPECTED_OCCURRENCES = 3


def patch(data):
    if not data.startswith(b'\x7fELF'):
        raise ValueError('Expected the Android-native Codex ELF executable')
    if len(ORIGINAL) != len(APK_NAME):
        raise ValueError('Codex helper relocation must preserve all byte offsets')
    if data.count(ORIGINAL) == 0 and data.count(APK_NAME) == EXPECTED_OCCURRENCES:
        return data
    if data.count(ORIGINAL) != EXPECTED_OCCURRENCES or APK_NAME in data:
        raise ValueError('Codex helper references changed; review the new pinned engine before packaging')
    return data.replace(ORIGINAL, APK_NAME)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('engine', type=Path)
    args = parser.parse_args()
    original = args.engine.read_bytes()
    result = patch(original)
    args.engine.write_bytes(result)
    print('Codex APK helper relocation: ' + hashlib.sha256(original).hexdigest() + ' -> ' + hashlib.sha256(result).hexdigest())
