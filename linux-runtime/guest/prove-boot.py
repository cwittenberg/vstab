#!/usr/bin/env python3
"""Boot the generated Linux guest in host QEMU and check real SSH/editor paths."""
import argparse
import base64
import hashlib
import http.cookiejar
import json
import os
from pathlib import Path
import secrets
import shlex
import shutil
import socket
import subprocess
import time
import urllib.parse
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / '.local/linux-runtime/guest'


def free_port():
    with socket.socket() as channel:
        channel.bind(('127.0.0.1', 0))
        return channel.getsockname()[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--arch', choices=['x86_64', 'arm64-v8a'], default='x86_64')
    parser.add_argument('--keep-running', action='store_true')
    parser.add_argument('--kernel-dir', type=Path, help='Use a built-in-driver kernel without initrd')
    parser.add_argument('--qemu-container', help='Host-only QEMU image, with host networking and a read/write checkout mount')
    parser.add_argument('--files-config', type=Path, help='Private config for an already-running isolated host filesystem broker fixture')
    parser.add_argument('--files-proof-root', help='Explicitly authorized writable fixture path under /mnt/android')
    parser.add_argument('--memory-mib', type=int, default=1024)
    parser.add_argument('--prove-stop-request', action='store_true')
    parser.add_argument('--assets-root', type=Path, default=OUT / 'assets')
    parser.add_argument('--prove-disk-growth', action='store_true')
    args = parser.parse_args()
    if args.memory_mib < 256 or args.memory_mib > 8192:
        parser.error('--memory-mib must be between 256 and 8192')
    arch = args.arch
    inputs = OUT / arch / 'assembled'
    proof = OUT / arch / ('proof-' + str(int(time.time())))
    bootstrap = proof / 'bootstrap'
    bootstrap.mkdir(parents=True, mode=0o700)
    key = proof / 'controller-key'
    subprocess.run(['ssh-keygen', '-q', '-t', 'ed25519', '-N', '', '-f', str(key)], check=True)
    password = secrets.token_hex(32)
    encoded = lambda value: base64.b64encode(value.encode()).decode()
    environment = 'host-proof-' + arch
    (bootstrap / 'bootstrap.env').write_text('VSTAB_ENVIRONMENT_ID_B64=' + encoded(environment) + '\n' +
        'VSTAB_AUTHORIZED_KEY_B64=' + encoded(key.with_suffix('.pub').read_text().strip()) + '\n' +
        'VSTAB_EDITOR_PASSWORD_B64=' + encoded(password) + '\n')
    if args.files_config:
        with (bootstrap / 'bootstrap.env').open('a') as stream:
            stream.write('VSTAB_FILES_CONFIG_B64=' + encoded(args.files_config.read_text()) + '\n')
    (bootstrap / 'bootstrap.env').chmod(0o600)
    disk = proof / 'rootfs.ext4'
    subprocess.run(['cp', '--sparse=always', str(inputs / 'rootfs.ext4'), str(disk)], check=True)
    published = json.loads((args.assets_root / 'linux-runtime/guest-manifest.json').read_text())['architectures'][arch]
    seed_bytes = disk.stat().st_size
    logical_bytes = seed_bytes
    if published.get('capabilities', {}).get('diskGrowth') is True:
        logical_bytes = published.get('logicalDiskBytes', seed_bytes)
        if not isinstance(logical_bytes, int) or logical_bytes < seed_bytes: raise RuntimeError('Invalid advertised logical disk capacity')
    preservation_hash = None
    if args.prove_disk_growth:
        if logical_bytes <= seed_bytes: raise RuntimeError('Growth proof requires an advertised disk larger than the seed')
        sentinel = proof / 'preservation.bin'; sentinel.write_bytes(bytes(i % 251 for i in range(163857)))
        preservation_hash = hashlib.sha256(sentinel.read_bytes()).hexdigest()
        guest_sentinel = '/home/developer/.vstab-grow-preservation-proof'
        subprocess.run(['debugfs', '-w', '-R', 'write ' + json.dumps(str(sentinel)) + ' ' + guest_sentinel, str(disk)], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        for field in ['uid', 'gid']:
            subprocess.run(['debugfs', '-w', '-R', f'set_inode_field {guest_sentinel} {field} 1000', str(disk)], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    if logical_bytes > seed_bytes:
        with disk.open('r+b') as grow: grow.truncate(logical_bytes)
    ssh_port, editor_port = free_port(), free_port()
    arm = arch == 'arm64-v8a'
    console = proof / 'console.log'
    command = ['qemu-system-aarch64' if arm else 'qemu-system-x86_64',
        '-machine', 'virt,gic-version=3' if arm else 'q35', '-cpu', 'cortex-a72' if arm else 'max',
        '-accel', 'tcg,thread=multi', '-smp', '2', '-m', str(args.memory_mib), '-nodefaults', '-no-reboot',
        '-kernel', str(args.kernel_dir / 'kernel' if args.kernel_dir else inputs / 'kernel'),
        '-append', 'root=/dev/vda rw console=' + ('ttyAMA0' if arm else 'ttyS0') + ',115200 init=/sbin/vstab-init net.ifnames=0',
        '-drive', 'file=' + str(disk) + ',if=virtio,format=raw',
        '-netdev', f'user,id=n0,hostfwd=tcp:127.0.0.1:{ssh_port}-:22,hostfwd=tcp:127.0.0.1:{editor_port}-:3000',
        '-device', 'virtio-net-pci,netdev=n0,romfile=',
        '-fsdev', 'local,id=b0,path=' + str(bootstrap) + ',security_model=none,readonly=on',
        '-device', 'virtio-9p-pci,fsdev=b0,mount_tag=vstab-bootstrap',
        '-display', 'none', '-serial', 'file:' + str(console), '-monitor', 'none']
    if not args.kernel_dir and (inputs / 'initrd').exists():
        command.extend(['-initrd', str(inputs / 'initrd')])
    if args.qemu_container:
        command = ['docker', 'run', '--rm', '--network', 'host',
            '--mount', f'type=bind,src={ROOT},dst={ROOT}', '-w', str(ROOT), args.qemu_container] + command
    result = {'schema': 1, 'architecture': arch, 'environment': 'Host QEMU TCG; real Linux kernel/ext4 and unmodified paired Linux code-server distribution. Not an Android product-flow proof.',
              'sshPort': ssh_port, 'editorPort': editor_port, 'guestMemoryMiB': args.memory_mib,
              'seedDiskBytes': seed_bytes, 'logicalDiskBytes': logical_bytes, 'checks': {}}
    process = subprocess.Popen(command, stdout=subprocess.DEVNULL, stderr=(proof / 'qemu.log').open('w'), start_new_session=True)
    result['qemuPid'] = process.pid
    started = time.monotonic()
    marker = None
    try:
        while time.monotonic() - started < 150:
            if process.poll() is not None:
                raise RuntimeError('QEMU exited before guest readiness')
            if console.exists():
                for line in console.read_text(errors='replace').splitlines():
                    if 'VSTAB_LINUX_READY ' in line:
                        marker = json.loads(line.split('VSTAB_LINUX_READY ', 1)[1])
                if marker:
                    break
            time.sleep(0.5)
        if not marker:
            raise RuntimeError('Linux guest did not emit readiness')
        result['readySeconds'] = round(time.monotonic() - started, 3)
        result['readiness'] = marker
        if not args.kernel_dir:
            for field in ['guestBuildId', 'platformApiVersion', 'dataSchemaVersion', 'userDataMode']:
                if field in published and marker.get(field) != published[field]: raise RuntimeError('Guest readiness build metadata mismatch: ' + field)
            if 'guestBuildId' in published: result['checks']['installedBuildMetadataMatchesPublishedImage'] = True
        known_hosts = proof / 'known_hosts'
        known_hosts.write_text(f'[127.0.0.1]:{ssh_port} {marker["sshHostKey"]["type"]} {marker["sshHostKey"]["key"]}\n')
        ssh = ['ssh', '-i', str(key), '-p', str(ssh_port), '-o', 'StrictHostKeyChecking=yes', '-o', 'BatchMode=yes',
               '-o', 'ConnectTimeout=10', '-o', 'UserKnownHostsFile=' + str(known_hosts), 'developer@127.0.0.1']
        def execute(command):
            return subprocess.check_output(ssh + [command], timeout=45, stderr=subprocess.STDOUT, text=True).strip()
        result['identity'] = execute('id; uname -srm; git --version; ssh -V 2>&1')
        if 'uid=1000(developer)' not in result['identity'] or 'Linux' not in result['identity']:
            raise RuntimeError('SSH did not reach the real ordinary-user Linux guest')
        result['checks']['pinnedHostKeyAndPublicKeySsh'] = True
        if args.prove_disk_growth:
            disk_report = marker.get('disk', {})
            if not disk_report.get('grown') or disk_report.get('capacityBytes') != logical_bytes or disk_report.get('filesystemBytes') != logical_bytes:
                raise RuntimeError('Guest did not online-grow ext4 to its advertised sparse disk capacity')
            result['checks']['onlineRootExt4Growth'] = True
            preserved = execute('sha256sum /home/developer/.vstab-grow-preservation-proof').split()[0]
            result['checks']['existingUserFileSurvivesGrowth'] = preserved == preservation_hash
            if not result['checks']['existingUserFileSurvivesGrowth']: raise RuntimeError('Existing user file changed during filesystem growth')
            execute('rm /home/developer/.vstab-grow-preservation-proof')
        if args.files_config:
            result['checks']['androidBrokerFuseMount'] = execute('mountpoint -q /mnt/android && printf VSTAB_FUSE_OK') == 'VSTAB_FUSE_OK'
        if args.files_proof_root:
            if not args.files_config or not args.files_proof_root.startswith('/mnt/android/') or '..' in args.files_proof_root.split('/'):
                raise RuntimeError('File proof requires an explicit Android-mount fixture root')
            filename = args.files_proof_root.rstrip('/') + '/.vstab-final-proof-' + secrets.token_hex(12)
            filesystem_script = "const fs=require('fs'),assert=require('assert');const p=" + json.dumps(filename) + ",q=p+'-renamed';let fd;try{const b=Buffer.alloc(163857);for(let i=0;i<b.length;i++)b[i]=i%251;fd=fs.openSync(p,'wx',0o600);fs.writeFileSync(fd,b);fs.fsyncSync(fd);fs.closeSync(fd);fd=undefined;assert(fs.readFileSync(p).equals(b));assert.equal(fs.statSync(p).mode&0o777,0o600);assert.equal(fs.statSync(p).uid,1000);assert.throws(()=>fs.openSync(p,'wx'),e=>e.code==='EEXIST');fs.renameSync(p,q);assert(fs.readFileSync(q).equals(b));fs.unlinkSync(q);console.log('VSTAB_FUSE_FILE_OPS_OK')}finally{if(fd!==undefined)fs.closeSync(fd);for(const f of[p,q])try{fs.unlinkSync(f)}catch(e){if(e.code!=='ENOENT')throw e}}"
            result['checks']['packagedClientFuseBinaryFileOperations'] = execute('/opt/code-server/lib/node -e ' + shlex.quote(filesystem_script)) == 'VSTAB_FUSE_FILE_OPS_OK'
        result['checks']['realTimeClock'] = abs(int(execute('date +%s')) - int(time.time())) <= 120
        result['checks']['publicGalleryHttps'] = execute('curl --fail --silent --show-error --connect-timeout 10 --max-time 20 --output /dev/null https://open-vsx.org/ && printf VSTAB_TLS_OK') == 'VSTAB_TLS_OK'
        script = "const fs=require('fs'),cp=require('child_process');const p=process.env.HOME+'/workspaces/default/native-exec-proof.sh';fs.writeFileSync(p,'#!/bin/sh\\nprintf VSTAB_LINUX_EXEC\\n');fs.chmodSync(p,0o700);const r=cp.spawnSync(p,[],{encoding:'utf8'});if(r.status||r.stdout!=='VSTAB_LINUX_EXEC')throw Error('nested exec failed');console.log(JSON.stringify({platform:process.platform,arch:process.arch,node:process.version,uid:process.getuid(),nested:r.stdout}));"
        result['nativeNode'] = json.loads(execute('/opt/code-server/lib/node -e ' + shlex.quote(script)))
        result['checks']['linuxNodeNestedWritableScriptExecution'] = result['nativeNode']['platform'] == 'linux'
        pty_script = "const p=require('/opt/code-server/lib/vscode/node_modules/node-pty');let s='';const t=p.spawn('/bin/bash',['-lc','printf VSTAB_PTY_OK'],{cwd:process.env.HOME,env:process.env});t.onData(x=>s+=x);t.onExit(e=>{if(e.exitCode||!s.includes('VSTAB_PTY_OK'))process.exit(1);console.log('VSTAB_PTY_OK')});"
        result['checks']['publisherLinuxNodePtyAddon'] = execute('/opt/code-server/lib/node -e ' + shlex.quote(pty_script)) == 'VSTAB_PTY_OK'
        cookies = http.cookiejar.CookieJar()
        opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cookies))
        body = urllib.parse.urlencode({'password': password}).encode()
        with opener.open(urllib.request.Request(f'http://127.0.0.1:{editor_port}/login', data=body), timeout=15) as response:
            html = response.read()
        if b'vscode-workbench-web-configuration' not in html or not list(cookies):
            raise RuntimeError('Authenticated code-server workbench was not delivered')
        result['checks']['authenticatedOriginalWorkbenchHtml'] = True
        with urllib.request.urlopen(f'http://127.0.0.1:{editor_port}/', timeout=10) as response:
            anonymous_html = response.read()
            result['checks']['editorRejectsMissingAuthentication'] = (
                urllib.parse.urlparse(response.url).path == '/login'
                and b'type="password"' in anonymous_html
                and b'vscode-workbench-web-configuration' not in anonymous_html)
        if not all(result['checks'].values()):
            raise RuntimeError('One or more guest behavior checks failed')
        execute('sync')
        if args.prove_stop_request:
            request_file = bootstrap / 'stop-request'
            request_file.write_text(environment + '-unrelated')
            time.sleep(1.5)
            if process.poll() is not None: raise RuntimeError('A different environment stop request terminated the guest')
            request_file.write_text(environment)
            try: code = process.wait(timeout=20)
            except subprocess.TimeoutExpired: raise RuntimeError('Matching stop request did not power off QEMU')
            result['checks']['gracefulMatchingEnvironmentStopRequest'] = code == 0
            if code != 0: raise RuntimeError('Guest stop request did not exit QEMU cleanly')
        result['status'] = 'passed'
        if args.keep_running:
            (proof / 'running.json').write_text(json.dumps({'pid': process.pid, 'sshPort': ssh_port, 'editorPort': editor_port, 'keyFile': str(key), 'knownHosts': str(known_hosts)}, indent=2))
        print(json.dumps({k: v for k, v in result.items() if k != 'readiness'}, indent=2))
    except Exception as error:
        result['status'] = 'failed'
        result['error'] = str(error)
        if marker:
            try:
                result['guestServiceLog'] = execute('sudo tail -60 /var/log/vstab-code-server.log')
            except Exception:
                pass
        print(json.dumps(result, indent=2))
        raise
    finally:
        (proof / 'proof.json').write_text(json.dumps(result, indent=2) + '\n')
        if not args.keep_running or result.get('status') != 'passed':
            process.terminate()
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill(); process.wait()


if __name__ == '__main__':
    main()
