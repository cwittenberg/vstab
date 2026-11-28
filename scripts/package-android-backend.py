#!/usr/bin/env python3
"""Package the exact paired Code OSS agent as Android data, without foreign ELF."""
import hashlib
import argparse
import json
import runpy
from pathlib import Path
import shutil
import subprocess
import tarfile
import zipfile

ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'.local/android-runtime'
ARCHIVE=ROOT/'.local/downloads/code-server-4.146.0-linux-amd64.tar.gz'
EXPECTED='cf7e82ca5fe1df664d43a43d900c3c9710f8f3403a5e8d0e18f6a12d10d7401b'

def sha(path):
    with path.open('rb') as stream:return hashlib.file_digest(stream,'sha256').hexdigest()

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.parse_args()
    if sha(ARCHIVE)!=EXPECTED:raise RuntimeError('untrusted code-server archive')
    ssh_lock=ROOT/'release/android-openssh.lock.json'
    if not ssh_lock.is_file():raise RuntimeError('Build the upstream Android OpenSSH clients before packaging the backend')
    openssh=json.loads(ssh_lock.read_text())
    if openssh.get('recipeSha256')!=sha(ROOT/'scripts/build-android-openssh.py'):
        raise RuntimeError('Rebuild Android OpenSSH after its build recipe changes')
    for abi,files in openssh['outputs'].items():
        for name,record in files.items():
            binary=OUT/'jniLibs'/abi/name
            if not binary.is_file() or sha(binary)!=record['sha256']:
                raise RuntimeError('Rebuild upstream Android OpenSSH; a client differs from its verified source build')
    backend=OUT/'backend'
    if backend.exists():shutil.rmtree(backend)
    backend.mkdir(parents=True)
    with tarfile.open(ARCHIVE) as archive:
        for item in archive.getmembers():
            parts=Path(item.name).parts
            if not parts or parts[0]!='code-server-4.146.0-linux-amd64' or '..' in parts:
                raise RuntimeError('unsafe backend archive path')
            item.name=str(Path(*parts[1:]))
            if item.name!='.':archive.extract(item,backend,filter='data')
    patcher=ROOT/'native-runtime/patch-server.py'
    if not patcher.is_file():raise RuntimeError('Android server patcher not prepared')
    subprocess.run(['python3',str(patcher),str(backend),'--manifest-out',
                    str(ROOT/'release/android-server-patches.json')],check=True)
    subprocess.run(['python3',str(ROOT/'native-runtime/patch-addon-loaders.py'),str(backend),
                    '--manifest-out',str(ROOT/'release/android-addon-loader-patches.json')],check=True)
    git_auth=runpy.run_path(str(ROOT/'native-runtime/patch-git-auth.py'))['patch'](
        backend/'lib/vscode/extensions/git/dist/main.js')
    spdlog=backend/'lib/vscode/node_modules/@vscode/spdlog/index.js'
    shutil.copyfile(ROOT/'native-runtime/vendor/spdlog.js',spdlog)
    shutil.copyfile(ROOT/'native-runtime/runtime-probe.cjs',backend/'vstab-runtime-probe.cjs')
    shutil.copyfile(ROOT/'native-runtime/vendor/VSCodroid-LICENSE.txt',backend/'VSCodroid-LICENSE.txt')
    removed=[]
    # Native machine code lives solely in APK nativeLibraryDir. Incompatible
    # desktop/Windows helper payloads cannot be mistaken for Android support.
    for path in sorted(backend.rglob('*')):
        if not path.is_file():continue
        with path.open('rb') as file:magic=file.read(4)
        if magic==b'\x7fELF' or path.suffix in ('.node','.exe','.dll','.dylib'):
            removed.append({'path':str(path.relative_to(backend)),
                            'sha256':sha(path),'bytes':path.stat().st_size})
            path.unlink()
    # Desktop Copilot's standalone Linux host is unavailable on Bionic; it is
    # neither a core dependency nor one of vstab's requested AI extensions.
    copilot=backend/'lib/vscode/extensions/copilot'
    if copilot.exists():shutil.rmtree(copilot)
    for path in backend.rglob('*'):
        if path.is_symlink() and not path.exists():path.unlink()
    builtin_packager=runpy.run_path(str(ROOT/'scripts/package-server-assets.py'))
    first_party_files=builtin_packager['extension_files'](ROOT)
    for relative,data in first_party_files.items():
        target=backend/'lib/vscode/extensions'/relative
        target.parent.mkdir(parents=True,exist_ok=True)
        target.write_bytes(data)
    first_party={'schema':1,'extensions':builtin_packager['EXTENSIONS'],
        'files':{name:{'sha256':hashlib.sha256(data).hexdigest(),'bytes':len(data)}
                 for name,data in sorted(first_party_files.items())}}
    (backend/'lib/vscode/extensions/vstab-first-party.json').write_text(json.dumps(first_party,indent=2)+'\n')
    notice=backend/'VSTAB-ANDROID-SOURCES.json'
    provenance={'schema':1,'codeServerVersion':'4.146.0','codeVersion':'1.146.0',
        'microsoftSourceCommit':'69047191fc427f2f92828279aae1d90d8eeda7ab',
        'sourceArchiveSha256':EXPECTED,
        'vscodroidSourceCommit':'843708384856a8686355ddb75a63e43cce62b2a0',
        'removedForeignBinaries':removed,
        'firstPartyExtensions':first_party,
        'gitAskpassPatch':git_auth,
        'opensshSource':openssh,
        'spdlogImplementation':'native-runtime/vendor/spdlog.js (VSCodroid MIT)'}
    notice.write_text(json.dumps(provenance,indent=2)+'\n')
    destination=OUT/'assets/local-runtime/backend.zip'
    with zipfile.ZipFile(destination,'w',zipfile.ZIP_DEFLATED,compresslevel=6) as archive:
        for path in sorted(backend.rglob('*')):
            if path.is_file():
                entry=zipfile.ZipInfo(str(path.relative_to(backend)),(2026,1,1,0,0,0))
                entry.compress_type=zipfile.ZIP_DEFLATED
                entry.external_attr=0o100644<<16
                archive.writestr(entry,path.read_bytes(),compresslevel=6)
    manifest_path=OUT/'assets/local-runtime/runtime.json'
    manifest=json.loads(manifest_path.read_text())
    manifest.update({'runtimeId':'vstab-android-node24-code146-'+sha(destination)[:16],
        'nodeExecutable':'libnode.so','bashExecutable':'libbash.so',
        'backendAsset':'local-runtime/backend.zip','backendSha256':sha(destination),
        'backendEntry':'out/node/entry.js',
        'nodeArguments':[],
        'symlinks':{'node':'libnode.so','bash':'libbash.so','git':'libgit.so','rg':'librg.so',
                    'npm':'libnpm.so','npx':'libnpx.so','ssh':'libssh.so',
                    'ssh-keygen':'libssh-keygen.so','ssh-agent':'libssh-agent.so',
                    'ssh-add':'libssh-add.so'},
        'usrLinks':{**{'lib/git-core/git-remote-'+protocol:'libgit-remote-curl.so'
                    for protocol in ['http','https','ftp','ftps']},
                    'lib/git-core/git-sh-i18n--envsubst':'libgit-envsubst.so'},
        'arguments':['{backend}/out/node/entry.js','--config','{config}',
            '--socket','{socket}','--socket-mode','0600','--user-data-dir','{state}',
            '--extensions-dir','{state}/extensions','--disable-telemetry',
            '--disable-update-check','{workspace}'],
        'environment':{'VSTAB_RIPGREP_PATH':'{nativeLibDir}/librg.so',
                       'VSTAB_NODE_PATH':'{nativeLibDir}/libnode.so','VSTAB_USR':'{usr}',
                       'SSL_CERT_FILE':'{usr}/etc/tls/cert.pem',
                       'GIT_SSL_CAINFO':'{usr}/etc/tls/cert.pem',
                       'GIT_EXEC_PATH':'{usr}/lib/git-core',
                       'GIT_TEMPLATE_DIR':'{usr}/share/git-core/templates',
                       'TERMINFO':'{usr}/share/terminfo',
                       'NODE_EXTRA_CA_CERTS':'{usr}/etc/tls/cert.pem'}})
    manifest['addonLinks']={
        'lib/vscode/node_modules/node-pty/build/Release/pty.node':'libpty.so',
        'lib/vscode/node_modules/@parcel/watcher/build/Release/watcher.node':'libwatcher.so',
        'lib/vscode/node_modules/@vscode/sqlite3/build/Release/vscode-sqlite3.node':'libvscode-sqlite3.so',
        'node_modules/argon2/build/Release/argon2.node':'libargon2.so'}
    identity=json.dumps({'backendSha256':manifest['backendSha256'],
                         'architectures':manifest['architectures']},sort_keys=True).encode()
    manifest['runtimeId']='vstab-native-'+hashlib.sha256(identity).hexdigest()[:20]
    manifest_path.write_text(json.dumps(manifest,indent=2)+'\n')
    (ROOT/'release/android-backend.json').write_text(json.dumps({
       **provenance,'backendZipSha256':sha(destination),'backendZipBytes':destination.stat().st_size,
       'backendFiles':sum(1 for p in backend.rglob('*') if p.is_file())},indent=2)+'\n')
    print(f'Packaged paired Android backend: {destination.stat().st_size} bytes; '
          f'{len(removed)} incompatible native payloads removed.',flush=True)

if __name__=='__main__':main()
