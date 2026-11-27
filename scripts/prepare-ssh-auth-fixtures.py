#!/usr/bin/env python3
"""Create private, task-owned SSH test keys for the controlled vstab demo server.

Requires Python cryptography+bcrypt. No existing user key/authentication files
are read. Private key material and the random passphrase never enter argv or
stdout. Re-running validates and reuses this fixture, then idempotently appends
only its public keys to the fixed controlled developer account.
"""
from __future__ import annotations

import base64
import hashlib
import json
import os
from pathlib import Path
import secrets
import subprocess

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey

ROOT = Path(__file__).resolve().parents[1]
DIRECTORY = ROOT / '.local/demo/sidebar-keys'
MANIFEST = DIRECTORY / 'fixtures.json'
CONTAINER = 'vstab-demo-ssh'
ACCOUNT = 'developer'


def private_write(path: Path, data: bytes) -> None:
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, 'wb') as output:
        output.write(data)
        output.flush()
        os.fsync(output.fileno())


def fingerprint(public: bytes) -> str:
    blob = base64.b64decode(public.split()[1])
    return 'SHA256:' + base64.b64encode(hashlib.sha256(blob).digest()).decode().rstrip('=')


def prepare() -> dict:
    DIRECTORY.mkdir(parents=True, exist_ok=True, mode=0o700)
    if DIRECTORY.is_symlink():
        raise RuntimeError('The demo fixture directory must not be a symbolic link.')
    DIRECTORY.chmod(0o700)
    if not MANIFEST.exists():
        if any(DIRECTORY.iterdir()):
            raise RuntimeError('Partial SSH demo fixture exists; inspect it before regenerating.')
        report = {'schema': 1, 'purpose': 'Task-owned controlled demo SSH authentication only',
                  'container': CONTAINER, 'account': ACCOUNT, 'keys': {}}
        for name, encrypted in [('plain', False), ('encrypted', True)]:
            key = Ed25519PrivateKey.generate()
            public = key.public_key().public_bytes(serialization.Encoding.OpenSSH,
                                                   serialization.PublicFormat.OpenSSH)
            passphrase = secrets.token_urlsafe(28).encode('ascii') if encrypted else None
            encryption = serialization.BestAvailableEncryption(passphrase) if encrypted else serialization.NoEncryption()
            private = key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.OpenSSH, encryption)
            private_path, public_path = DIRECTORY / (name + '_ed25519'), DIRECTORY / (name + '_ed25519.pub')
            private_write(private_path, private)
            private_write(public_path, public + (' vstab-sidebar-' + name + '\n').encode())
            record = {'privateKey': str(private_path), 'publicKey': str(public_path),
                      'fingerprint': fingerprint(public), 'encrypted': encrypted}
            if passphrase is not None:
                passphrase_path = DIRECTORY / 'encrypted.passphrase'
                private_write(passphrase_path, passphrase + b'\n')
                record['passphraseFile'] = str(passphrase_path)
            report['keys'][name] = record
        private_write(MANIFEST, (json.dumps(report, indent=2) + '\n').encode())
    report = json.loads(MANIFEST.read_text())
    public_keys = []
    for name in ['plain', 'encrypted']:
        record = report['keys'][name]
        private_path = DIRECTORY / (name + '_ed25519')
        public_path = DIRECTORY / (name + '_ed25519.pub')
        for path in [private_path, public_path, MANIFEST]:
            if path.is_symlink():
                raise RuntimeError('Demo fixture files must not be symbolic links.')
            path.chmod(0o600)
        password = None
        if name == 'encrypted':
            passphrase_path = DIRECTORY / 'encrypted.passphrase'
            if passphrase_path.is_symlink():
                raise RuntimeError('The demo passphrase must not be a symbolic link.')
            passphrase_path.chmod(0o600)
            password = passphrase_path.read_bytes().rstrip(b'\n')
        key = serialization.load_ssh_private_key(private_path.read_bytes(), password=password)
        derived = key.public_key().public_bytes(serialization.Encoding.OpenSSH, serialization.PublicFormat.OpenSSH)
        public = public_path.read_bytes().strip()
        if public.split()[:2] != derived.split() or fingerprint(derived) != record['fingerprint']:
            raise RuntimeError('The demo public/private key fixture does not match its manifest.')
        public_keys.append(public.decode('ascii'))
    # This script contains only public-key data. No private bytes/passphrase are
    # sent to Docker, the server, subprocess arguments, stdout or diagnostics.
    remote = r'''
import fcntl,json,os
from pathlib import Path
keys=json.load(__import__('sys').stdin)['publicKeys']
home=Path('/home/developer'); directory=home/'.ssh'; directory.mkdir(mode=0o700,exist_ok=True)
target=directory/'authorized_keys'
if directory.is_symlink() or target.is_symlink(): raise RuntimeError('Controlled authorized_keys must be an ordinary app-owned file')
added=0
with target.open('a+b') as file:
    fcntl.flock(file,fcntl.LOCK_EX); file.seek(0); previous=file.read(256*1024+1)
    if len(previous)>256*1024: raise RuntimeError('Controlled authorized_keys exceeds demo bound')
    existing=set()
    for line in previous.decode('utf-8').splitlines():
        words=line.split()
        if len(words)>=2: existing.add(tuple(words[:2]))
    file.seek(0,2)
    for key in keys:
        words=key.split()
        if len(words)!=3 or words[0]!='ssh-ed25519' or not words[2].startswith('vstab-sidebar-'): raise RuntimeError('Invalid demo public key')
        identity=tuple(words[:2])
        if identity not in existing:
            if previous and not previous.endswith(b'\n'): file.write(b'\n')
            file.write((key+'\n').encode('ascii')); previous=b'\n'; existing.add(identity); added+=1
    file.flush(); os.fsync(file.fileno())
os.chmod(directory,0o700); os.chmod(target,0o600)
print(json.dumps({'addedPublicKeys':added,'authorizedKeys':str(target)}))
'''
    result = subprocess.run(['docker', 'exec', '-i', '--user', ACCOUNT, CONTAINER,
                             'python3', '-c', remote], input=json.dumps({'publicKeys': public_keys}),
                            capture_output=True, text=True)
    if result.returncode:
        raise RuntimeError('The controlled demo public-key installation failed; no private material was transferred.')
    report['installation'] = json.loads(result.stdout)
    return report


if __name__ == '__main__':
    try:
        print(json.dumps(prepare(), indent=2))
    except Exception:
        # Third-party decryption diagnostics are deliberately not echoed.
        raise SystemExit('SSH demo fixture preparation failed. Inspect the task-owned fixture and controlled container; secrets were not printed.')
