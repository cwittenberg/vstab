#!/usr/bin/env python3
"""Require a real JUnit success marker; am instrument can return zero on failures."""
import argparse
import os
from pathlib import Path
import re
import subprocess

parser = argparse.ArgumentParser()
parser.add_argument('--classes', required=True)
parser.add_argument('--output', type=Path, required=True)
args = parser.parse_args()
serial = os.environ.get('VSTAB_ANDROID_SERIAL', 'emulator-5580')
result = subprocess.run(['adb', '-s', serial, 'shell', 'am', 'instrument', '-w',
                         '-e', 'class', args.classes,
                         'com.vstab.test/androidx.test.runner.AndroidJUnitRunner'],
                        capture_output=True, text=True)
output = result.stdout + result.stderr
args.output.parent.mkdir(parents=True, exist_ok=True)
args.output.write_text(output)
print(output)
if result.returncode or not re.search(r'\bOK \(\d+ tests?\)', output) or 'FAILURES!!!' in output:
    raise SystemExit(1)
