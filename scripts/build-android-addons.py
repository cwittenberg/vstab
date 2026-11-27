#!/usr/bin/env python3
"""Build the paired Code OSS native addons against Android Bionic, both ABIs."""
import argparse
import concurrent.futures
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tarfile

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / '.local/android-runtime'
SOURCES = OUT / 'addon-sources'
PINNED = {
 'node-pty': ('1.2.0-beta.18', '4e7c9c3028e636e941d90d59a831100bebf3431ccce8ca00399752c1664aa399'),
 '@parcel/watcher': ('2.5.6', '8daa7285bee7e10bebce179007afcf07108c1b8ce9dbe5081f3db319d5453648'),
 '@vscode/sqlite3': ('5.1.12-vscode', '521675e4de9714e91862d1aff6d10cf88351e3c03ad9b699062b7b754ccb9144'),
 'node-addon-api7': ('7.1.1', 'b10455d15a977c0cd17a1cb0eb679e03d939f8ef8d4302eb33e1f78dacc71f82'),
 'node-addon-api8': ('8.9.2', '4cd65698541b19a33f798f1dc25c02c6ed1c9d7749b8824b1a1ccecdd197c8ea'),
}
HEADERS_VERSION = '24.18.0'
HEADERS_SHA = '6c7d41d83c3481d2301115b8ce4a44b7d4fbfa52859b1aac14f445d460137887'
ARCHES = {'arm64-v8a':'aarch64', 'x86_64':'x86_64'}

def sha(path):
    with path.open('rb') as f: return hashlib.file_digest(f, 'sha256').hexdigest()

def download(url, path, expected):
    path.parent.mkdir(parents=True, exist_ok=True)
    if not path.exists():
        subprocess.run(['curl','-fLsS','--retry','3',url,'-o',str(path)], check=True)
    if sha(path) != expected: raise RuntimeError(f'checksum mismatch: {path}')

def source(name, version, expected):
    package_name = 'node-addon-api' if name.startswith('node-addon-api') else name
    archive = OUT / 'cache' / (name.replace('/','_')+'-'+version+'.tgz')
    url = 'https://registry.npmjs.org/' + package_name + '/-/' + package_name.split('/')[-1] + '-' + version + '.tgz'
    download(url, archive, expected)
    dest = SOURCES / name.replace('/','_')
    if dest.exists(): shutil.rmtree(dest)
    dest.mkdir(parents=True)
    with tarfile.open(archive) as tar:
        for item in tar.getmembers():
            parts = Path(item.name).parts
            if not parts or parts[0] != 'package' or '..' in parts: raise RuntimeError('unsafe addon archive')
            item.name = str(Path(*parts[1:]))
            if item.name != '.': tar.extract(item, dest, filter='data')
    return dest

def build_abi(abi, target, ndk, prepared, headers):
    print('Building Bionic native addons: '+abi, flush=True)
    tools = ndk / 'toolchains/llvm/prebuilt/linux-x86_64/bin'
    cc = tools / (target+'-linux-android30-clang')
    cxx = tools / (target+'-linux-android30-clang++')
    work = OUT / 'addon-build' / abi
    work.mkdir(parents=True, exist_ok=True)
    jni = OUT / 'jniLibs' / abi
    page_flags = ['-Wl,-z,max-page-size=16384','-Wl,-z,common-page-size=16384']
    common = ['-shared','-fPIC','-std=c++17','-O2','-static-libstdc++','-Wl,-Bsymbolic',
              *page_flags,'-DNAPI_VERSION=9','-I'+str(headers)]
    def run(args): subprocess.run(list(map(str,args)), check=True)
    run([cc,'-fPIE','-pie','-O2',*page_flags,'-o',jni/'libnpm.so',ROOT/'native-runtime/npm-launcher.c'])
    shutil.copyfile(jni/'libnpm.so',jni/'libnpx.so')
    (jni/'libnpm.so').chmod(0o755)
    (jni/'libnpx.so').chmod(0o755)
    def compile_addon(name, sources, extra, api=7):
        src = prepared[name]
        out = jni / {'node-pty':'libpty.so','@parcel/watcher':'libwatcher.so',
                     '@vscode/sqlite3':'libvscode-sqlite3.so','argon2':'libargon2.so'}[name]
        run([cxx,*common,'-I'+str(prepared['node-addon-api'+str(api)]),*extra,
             '-o',out,*[src / s for s in sources]])
        run([tools / 'llvm-strip',out])
        return out
    compile_addon('node-pty',['src/unix/pty.cc'],
                  ['-DNODE_ADDON_API_DISABLE_DEPRECATED','-DNODE_GYP_MODULE_NAME=pty'])
    clockwait = work / 'cond-clockwait.o'
    run([cc,'-c','-fPIC','-O2','-o',clockwait,ROOT/'native-runtime/vendor/cond-clockwait.c'])
    compile_addon('@parcel/watcher',[
        'src/binding.cc','src/Watcher.cc','src/Backend.cc','src/DirTree.cc',
        'src/Glob.cc','src/Debounce.cc','src/watchman/BSER.cc',
        'src/watchman/WatchmanBackend.cc','src/shared/BruteForceBackend.cc',
        'src/linux/InotifyBackend.cc','src/unix/legacy.cc'],
        ['-fexceptions','-DNAPI_DISABLE_CPP_EXCEPTIONS','-DWATCHMAN','-DINOTIFY',
         '-DBRUTE_FORCE','-DNODE_GYP_MODULE_NAME=watcher','-Wl,--wrap=pthread_cond_clockwait',str(clockwait)])
    src = prepared['@vscode/sqlite3']
    amalgamation = src / 'sqlite-autoconf-3390400'
    sqlite_flags = ['-DNDEBUG','-D_REENTRANT=1','-DSQLITE_THREADSAFE=1','-DHAVE_USLEEP=1',
        '-DSQLITE_ENABLE_FTS3','-DSQLITE_ENABLE_FTS4','-DSQLITE_ENABLE_FTS5',
        '-DSQLITE_ENABLE_RTREE','-DSQLITE_ENABLE_DBSTAT_VTAB=1','-DSQLITE_ENABLE_MATH_FUNCTIONS']
    sqlite_obj = work / 'sqlite3.o'
    run([cc,'-c','-fPIC','-O2',*sqlite_flags,'-o',sqlite_obj,amalgamation/'sqlite3.c'])
    compile_addon('@vscode/sqlite3',['src/backup.cc','src/database.cc','src/node_sqlite3.cc','src/statement.cc'],
        ['-fexceptions','-DNODE_API_SWALLOW_UNTHROWABLE_EXCEPTIONS',*sqlite_flags,
         '-I'+str(amalgamation),str(sqlite_obj)],api=8)
    argon = prepared['argon2']
    objects = []
    for source_file in ['argon2.c','blake2/blake2b.c','core.c','encoding.c','thread.c','ref.c']:
        obj = work / (source_file.replace('/','_')+'.o')
        run([cc,'-c','-fPIC','-O2','-DNDEBUG','-I'+str(argon/'argon2/include'),
             '-o',obj,argon/'argon2/src'/source_file])
        objects.append(str(obj))
    compile_addon('argon2',['argon2.cpp'],['-fexceptions','-DNODE_ADDON_API_DISABLE_DEPRECATED',
        '-DNODE_API_NO_EXTERNAL_BUFFERS_ALLOWED','-I'+str(argon/'argon2/include'),*objects],api=8)
    # Assert actual ELF identity and Android-compatible loader/alignment of all APK native files.
    records = {}
    for binary in sorted(jni.glob('*.so')):
        meta = subprocess.check_output(['readelf','-hlW',str(binary)],text=True)
        expected_machine = 'AArch64' if abi == 'arm64-v8a' else 'Advanced Micro Devices X86-64'
        if expected_machine not in meta: raise RuntimeError('wrong machine: '+str(binary))
        for line in meta.splitlines():
            if line.strip().startswith('LOAD ') and int(line.split()[-1],16)<16384:
                raise RuntimeError('ELF is not 16KB aligned: '+str(binary))
        if 'Requesting program interpreter:' in meta and '/system/bin/linker64' not in meta:
            raise RuntimeError('not Android-linked: '+str(binary))
        records[binary.name] = {'sha256':sha(binary),'bytes':binary.stat().st_size}
    return abi,records

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--ndk',type=Path,default=os.environ.get('ANDROID_NDK_HOME'))
    args=p.parse_args()
    if args.ndk is None: raise SystemExit('Pass --ndk or set ANDROID_NDK_HOME')
    SOURCES.mkdir(parents=True,exist_ok=True)
    prepared={name:source(name,*pin) for name,pin in PINNED.items()}
    prepared['argon2']=OUT/'backend/node_modules/argon2'
    # This shared input must be fully extracted before ABI builds run in parallel.
    sqlite_source=prepared['@vscode/sqlite3']
    with tarfile.open(sqlite_source/'deps/sqlite-autoconf-3390400.tar.gz') as tar:
        tar.extractall(sqlite_source,filter='data')
    for package,version in [('node-pty','1.2.0-beta.18'),('@parcel/watcher','2.5.6'),('@vscode/sqlite3','5.1.12-vscode')]:
        package_json=OUT/'backend/lib/vscode/node_modules'/package/'package.json'
        if json.loads(package_json.read_text())['version']!=version: raise RuntimeError('JS/native version mismatch '+package)
    archive=OUT/'cache'/f'node-v{HEADERS_VERSION}-headers.tar.gz'
    download(f'https://nodejs.org/dist/v{HEADERS_VERSION}/{archive.name}',archive,HEADERS_SHA)
    with tarfile.open(archive) as tar: tar.extractall(SOURCES,filter='data')
    headers=SOURCES/f'node-v{HEADERS_VERSION}/include/node'
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        results=list(pool.map(lambda item:build_abi(*item,args.ndk,prepared,headers),ARCHES.items()))
    manifest_path=OUT/'assets/local-runtime/runtime.json'
    manifest=json.loads(manifest_path.read_text())
    links={'lib/vscode/node_modules/node-pty/build/Release/pty.node':'libpty.so',
        'lib/vscode/node_modules/@parcel/watcher/build/Release/watcher.node':'libwatcher.so',
        'lib/vscode/node_modules/@vscode/sqlite3/build/Release/vscode-sqlite3.node':'libvscode-sqlite3.so',
        'node_modules/argon2/build/Release/argon2.node':'libargon2.so'}
    manifest['addonLinks']=links
    for abi,records in results: manifest['architectures'][abi]['nativeFiles']=records
    manifest_path.write_text(json.dumps(manifest,indent=2)+'\n')
    (ROOT/'release/android-addons.lock.json').write_text(json.dumps({
      'nodeHeadersVersion':HEADERS_VERSION,'nodeHeadersSha256':HEADERS_SHA,
      'sources':{name:{'version':pin[0],'sha256':pin[1]} for name,pin in PINNED.items()},
      'argon2Source':'verified code-server4.146.0 archive node_modules/argon2 (0.45.2)',
      'npmLauncherSourceSha256':sha(ROOT/'native-runtime/npm-launcher.c'),
      'ndkVersion':args.ndk.name,'androidApi':30,'minimumApi':29,'elfPageAlignment':16384,
      'outputs':dict(results)},indent=2)+'\n')
    print('Both Android native addon sets built and verified.',flush=True)

if __name__=='__main__':main()
