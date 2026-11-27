#!/usr/bin/env python3
"""Inspect and capture the debug Android app through ADB.

Shared UI helpers are also used by the workspace verification tools.
"""
import argparse
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent.parent
ADB = shutil.which('adb') or str(Path(os.environ.get('ANDROID_HOME', '')) / 'platform-tools/adb')
SERIAL = os.environ.get('VSTAB_ANDROID_SERIAL', 'emulator-5580')
CDP_PORT = os.environ.get('VSTAB_CDP_PORT', '9227')


def adb(*args, timeout=30):
    return subprocess.check_output([ADB, '-s', SERIAL, *args], timeout=timeout)


def ui():
    adb('shell', 'uiautomator', 'dump', '--compressed', '/sdcard/vstab-ui.xml')
    return ET.fromstring(adb('exec-out', 'cat', '/sdcard/vstab-ui.xml'))


def center(node):
    x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.attrib['bounds']))
    return str((x1+x2)//2), str((y1+y2)//2)


def tap(node):
    adb('shell', 'input', 'tap', *center(node))



def screenshot(name):
    target = ROOT / 'artifacts/demo' / name
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(adb('exec-out', 'screencap', '-p'))
    print('Captured', target.relative_to(ROOT))



def forward_devtools():
    deadline=time.monotonic()+20
    socket=None
    while time.monotonic()<deadline:
        result=subprocess.run([ADB,'-s',SERIAL,'shell','pidof','app.vstab'],capture_output=True,text=True)
        if result.returncode==0 and result.stdout.strip():
            pid=result.stdout.strip().split()[0]
            candidate='webview_devtools_remote_'+pid
            sockets=adb('shell','cat','/proc/net/unix').decode()
            if '@'+candidate in sockets:
                socket=candidate
                break
        time.sleep(.25)
    if socket is None:
        raise RuntimeError('No debug WebView socket belonging to vstab appeared')
    adb('forward', 'tcp:' + CDP_PORT, 'localabstract:' + socket)
    print('Forwarded actual vstab Android WebView CDP to 127.0.0.1:' + CDP_PORT)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['devtools', 'capture'])
    parser.add_argument('--name', default='android-current.png')
    args = parser.parse_args()
    if args.action == 'devtools':
        forward_devtools()
    else:
        screenshot(args.name)
