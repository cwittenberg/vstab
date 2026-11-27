#!/usr/bin/env python3
"""Drive the ordinary Android UI against the isolated SSH fixture.

No app-only test hooks or direct editor/file modifications are used here.
Private fixture credentials are read locally and never printed.
"""
import argparse
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import time
import urllib.request
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


def field(resource, value):
    tree = ui()
    node = next(n for n in tree.iter('node') if n.attrib.get('resource-id') == 'app.vstab:id/' + resource)
    tap(node)
    adb('shell', 'input', 'keycombination', 'KEYCODE_CTRL_LEFT', 'KEYCODE_A')
    adb('shell', 'input', 'text', shlex.quote(str(value).replace(' ', '%s')))


def screenshot(name):
    target = ROOT / 'artifacts/demo' / name
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(adb('exec-out', 'screencap', '-p'))
    print('Captured', target.relative_to(ROOT))


def connect():
    private = json.loads((ROOT / '.local/demo/connection.json').read_text())
    adb('shell', 'am', 'start', '-n', 'app.vstab/.MainActivity')
    time.sleep(1)
    screenshot('03-ssh-connection-settings.png')
    for key, value in [('host', private['emulatorHost']), ('port', private['port']),
                       ('username', private['username']), ('password', private['password']),
                       ('folder', private['workspace'])]:
        field(key, value)
    adb('shell', 'input', 'keyevent', 'KEYCODE_BACK')
    tree = ui()
    tap(next(n for n in tree.iter('node') if n.attrib.get('resource-id') == 'app.vstab:id/connect'))
    expected = subprocess.check_output(['ssh-keygen', '-lf', str(ROOT / '.local/demo/known_hosts')], text=True).split()[1]
    verified = False
    deadline = time.monotonic() + 180
    while time.monotonic() < deadline:
        try:
            with urllib.request.urlopen('http://127.0.0.1:' + CDP_PORT + '/json/list', timeout=1) as response:
                targets=json.load(response)
            for target in targets:
                description=json.loads(target.get('description') or '{}')
                if target.get('type')=='page' and target.get('url','').startswith('http://127.0.0.1:') and description.get('visible') and description.get('attached'):
                    screenshot('05-ssh-workbench-opening.png')
                    print('Android SSH workspace opened; first-connection fingerprint confirmation:', verified)
                    return
        except (OSError,ValueError):
            pass
        tree = ui()
        text = '\n'.join(n.attrib.get('text', '') for n in tree.iter('node'))
        trust = next((n for n in tree.iter('node') if n.attrib.get('text', '').casefold() == 'trust and connect'), None)
        if trust is not None:
            if expected not in text:
                raise RuntimeError('The displayed SSH fingerprint does not match the independently verified fixture key')
            screenshot('04-ssh-host-verification.png')
            tap(trust)
            verified = True
            print('Verified fixture host fingerprint in native Android dialog')
        if any(n.attrib.get('resource-id') == 'app.vstab:id/workbench' for n in tree.iter('node')):
            screenshot('05-ssh-workbench-opening.png')
            print('Android SSH workspace opened; first-connection fingerprint confirmation:', verified)
            return
        status = next((n.attrib.get('text', '') for n in tree.iter('node') if n.attrib.get('resource-id') == 'app.vstab:id/connection_status'), '')
        if 'failed' in status.lower() or 'denied' in status.lower():
            raise RuntimeError(status)
        time.sleep(1)
    raise RuntimeError('Android connection did not open the workbench before the deadline')


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
    parser.add_argument('action', choices=['connect', 'devtools', 'capture'])
    parser.add_argument('--name', default='android-current.png')
    args = parser.parse_args()
    if args.action == 'connect':
        connect()
    elif args.action == 'devtools':
        forward_devtools()
    else:
        screenshot(args.name)
