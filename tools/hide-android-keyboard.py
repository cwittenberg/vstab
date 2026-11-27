#!/usr/bin/env python3
"""Dismiss a shown IME; never send Back into the editor's root navigation."""
import os
import re
import subprocess

adb = ['adb', '-s', os.environ.get('VSTAB_ANDROID_SERIAL', 'emulator-5580')]
state = subprocess.check_output(adb + ['shell', 'dumpsys', 'input_method'], text=True)
shown = re.search(r'\bmInputShown=(true|false)\b', state)
if shown is None:
    raise SystemExit('Cannot determine Android IME visibility; no Back key sent')
if shown.group(1) == 'true':
    subprocess.check_call(adb + ['shell', 'input', 'keyevent', 'KEYCODE_BACK'])
    print('Dismissed the visible Android keyboard')
else:
    print('Android keyboard is already hidden; no Back key sent')
