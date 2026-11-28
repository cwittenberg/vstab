#!/usr/bin/env python3
"""Package the matched, actual Code OSS workbench into Android APK assets.

This uses the pinned Code OSS-derived code-server distribution and preserves its
compiled client/agent identity. It does not download a Microsoft VS Code binary,
pretend the downstream is unmodified upstream, or bypass protocol version checks.
"""

import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import runpy
import shutil
import subprocess
import tarfile
import tempfile
import urllib.request


def digest(path):
    result = hashlib.sha256()
    with path.open('rb') as stream:
        for data in iter(lambda: stream.read(1024 * 1024), b''):
            result.update(data)
    return result.hexdigest()


def download(spec, destination):
    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary = destination.with_suffix(destination.suffix + '.partial')
    with urllib.request.urlopen(spec['artifactUrl'], timeout=60) as response, temporary.open('wb') as target:
        shutil.copyfileobj(response, target)
    if digest(temporary) != spec['artifactSha256']:
        temporary.unlink()
        raise SystemExit('The workbench archive did not match the pinned SHA-256.')
    temporary.replace(destination)


def original_asset_records(archive_path, prefix):
    """Hashes of files available through the unchanged backend's static routes."""
    records = {}
    vscode_prefix = prefix + 'lib/vscode/'
    with tarfile.open(archive_path) as archive:
        for member in archive:
            if not member.isfile():
                continue
            key = backend_path = None
            if member.name.startswith(vscode_prefix):
                relative = member.name[len(vscode_prefix):]
                if not relative.startswith(('out/', 'node_modules/', 'extensions/')):
                    continue
                key = 'static/' + relative
                backend_path = relative
            elif member.name.startswith(prefix + 'src/browser/media/'):
                relative = member.name[len(prefix + 'src/browser/media/'):]
                key = 'static/resources/code-server/' + relative
                backend_path = '/_static/src/browser/media/' + relative
            if key is None:
                continue
            if '..' in PurePosixPath(key).parts or PurePosixPath(key).is_absolute():
                raise ValueError('Unsafe original backend asset path')
            stream = archive.extractfile(member)
            if stream is None:
                raise ValueError('Cannot read original backend asset')
            result = hashlib.sha256()
            length = 0
            for data in iter(lambda: stream.read(1024 * 1024), b''):
                result.update(data)
                length += len(data)
            records[key] = {'sha256': result.hexdigest(), 'bytes': length,
                            'backendPath': backend_path}
    return records


def shared_backend_assets(stage, files, amd64_records, arm64_records, static_prefix):
    """Remove only exact, cross-architecture original copies from the APK stage."""
    omitted = {}
    for name, item in list(files.items()):
        original = amd64_records.get(name)
        arm = arm64_records.get(name)
        if (original is None or arm is None or
                any(original[field] != item[field] or arm[field] != item[field]
                    for field in ['sha256', 'bytes'])):
            continue
        backend_path = original['backendPath']
        if not backend_path.startswith('/'):
            backend_path = static_prefix + backend_path
        omitted[name] = {**item, 'backendPath': backend_path}
        (stage / name).unlink()
        del files[name]
    return omitted


def main():
    root = Path(__file__).resolve().parent.parent
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--archive', type=Path, default=root / '.local/downloads/code-server-4.146.0-linux-amd64.tar.gz')
    parser.add_argument('--output', type=Path, default=root / '.local/workbench-assets')
    parser.add_argument('--shared-linux-assets', action='store_true',
                        help='Omit exact original frontend copies; Android verifies matching backend bytes')
    parser.add_argument('--arm64-archive', type=Path,
                        default=root / '.local/linux-runtime/guest/arm64-v8a/inputs/code-server-4.146.0-linux-arm64.tar.gz')
    args = parser.parse_args()
    spec = json.loads((root / 'workbench/bundle-spec.json').read_text())
    if not args.archive.exists():
        print('Downloading the pinned Code OSS-derived workbench artifact…')
        download(spec, args.archive)
    if digest(args.archive) != spec['artifactSha256']:
        raise SystemExit('The workbench archive did not match the pinned SHA-256.')

    originals = comparisons = None
    if args.shared_linux_assets:
        lock = json.loads((root / 'release/server-lock.json').read_text())
        if lock.get('codeServerVersion') != spec['codeServerVersion']:
            raise SystemExit('Shared frontend assets require the exact pinned server release.')
        amd64 = lock['assets']['linux-amd64']
        arm64 = lock['assets']['linux-arm64']
        if amd64['sha256'] != digest(args.archive):
            raise SystemExit('Shared frontend assets require the pinned amd64 server archive.')
        if not args.arm64_archive.is_file() or digest(args.arm64_archive) != arm64['sha256']:
            raise SystemExit('Shared frontend assets require the verified pinned ARM64 server archive as well.')
        originals = original_asset_records(args.archive,
            f'code-server-{spec["codeServerVersion"]}-linux-amd64/')
        comparisons = original_asset_records(args.arm64_archive,
            f'code-server-{spec["codeServerVersion"]}-linux-arm64/')

    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    destination = output / 'workbench'
    prefix = f'code-server-{spec["codeServerVersion"]}-linux-amd64/'
    vscode_prefix = prefix + 'lib/vscode/'
    selected = []
    required = {
        'out/vs/code/browser/workbench/workbench.js',
        'out/vs/code/browser/workbench/workbench.css',
        'out/nls.messages.js',
        'out/media/codicon.ttf',
        'out/vs/workbench/api/worker/extensionHostWorkerMain.js',
        'out/vs/workbench/contrib/webview/browser/pre/index.html',
        'out/vs/workbench/contrib/webview/browser/pre/service-worker.js',
        'node_modules/vscode-oniguruma/release/onig.wasm',
    }
    template = None
    packaged_builtin_extensions = []
    with tempfile.TemporaryDirectory(prefix='.workbench-stage-', dir=output) as temporary:
        stage = Path(temporary) / 'workbench'
        stage.mkdir()
        with tarfile.open(args.archive) as archive:
            archive_names = set(archive.getnames())
            for member in archive.getmembers():
                if not member.isfile() or not member.name.startswith(vscode_prefix + 'extensions/') or not member.name.endswith('/package.json'):
                    continue
                relative = member.name[len(vscode_prefix + 'extensions/'):]
                if relative.count('/') != 1:
                    continue
                package = json.load(archive.extractfile(member))
                name = relative.split('/')[0]
                browser = package.get('browser')
                browser_base = vscode_prefix + 'extensions/' + name + '/' + str(browser or '').removeprefix('./')
                has_browser = bool(browser) and (browser_base in archive_names or browser_base + '.js' in archive_names)
                if (not package.get('main') and not browser) or has_browser:
                    packaged_builtin_extensions.append(name)
            for member in archive:
                if not member.isfile():
                    continue
                relative = None
                if member.name.startswith(vscode_prefix):
                    candidate = member.name[len(vscode_prefix):]
                    if candidate.startswith('out/'):
                        relative = candidate
                    elif any(candidate.startswith('node_modules/' + dep + '/') for dep in spec['browserDependencies']):
                        relative = candidate
                    elif any(candidate.startswith('extensions/' + name + '/') for name in packaged_builtin_extensions):
                        relative = candidate
                    elif candidate in ['LICENSE.txt', 'LICENSE', 'ThirdPartyNotices.txt', 'product.json']:
                        relative = candidate
                elif member.name.startswith(prefix + 'src/browser/media/'):
                    relative = 'resources/code-server/' + member.name[len(prefix + 'src/browser/media/'):]
                elif member.name == prefix + 'LICENSE':
                    relative = 'notices/code-server-LICENSE'
                elif member.name == prefix + 'ThirdPartyNotices.txt':
                    relative = 'notices/code-server-ThirdPartyNotices.txt'
                if relative is None:
                    continue
                parts = PurePosixPath(relative).parts
                if PurePosixPath(relative).is_absolute() or '..' in parts:
                    raise SystemExit('An unsafe workbench archive path was rejected.')
                stream = archive.extractfile(member)
                if stream is None:
                    raise SystemExit('Cannot read an expected workbench asset.')
                data = stream.read()
                target = stage / 'static' / relative
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(data)
                selected.append(relative)
                required.discard(relative)
                if relative == 'out/vs/code/browser/workbench/workbench.html':
                    template = data.decode('utf-8')
        if required:
            raise SystemExit('Missing required workbench assets: ' + ', '.join(sorted(required)))
        if template is None:
            raise SystemExit('The workbench bootstrap template is missing.')
        product = json.loads((stage / 'static/product.json').read_text())
        if product.get('commit') != spec['serverProductCommit'] or product.get('version') != spec['codeVersion']:
            raise SystemExit('The bundled workbench identity does not match the pinned remote server.')

        # Keep the upstream startup order, but own the HTML entry point and its
        # application metadata. Script/worker/media URLs stay under native-owned
        # routes, including strictly pinned backend copies in the shared mode.
        template = template.replace('content="Code"', 'content="vstab"')
        template = template.replace('{{BASE}}/_static/src/browser/media/', '{{WORKBENCH_WEB_BASE_URL}}/resources/code-server/')
        template = template.replace('{{VS_BASE}}/manifest.json', '{{WORKBENCH_WEB_BASE_URL}}/resources/vstab/manifest.json')
        template = template.replace('<meta charset="utf-8" />', '<meta charset="utf-8" />\n\t\t<meta name="vstab-content-source" content="apk" />')
        template = template.replace('<script type="module" src="{{WORKBENCH_WEB_BASE_URL}}/out/vs/code/browser/workbench/workbench.js"></script>', '{{VSTAB_LOCAL_BOOTSTRAP}}\n\t<script type="module" src="{{WORKBENCH_WEB_BASE_URL}}/out/vs/code/browser/workbench/workbench.js"></script>')
        # The first Android bundle ships English NLS from the APK; do not issue
        # an empty-script request to the remote root for an absent locale module.
        template = re.sub(r'^\s*<script type="module" src="\{\{WORKBENCH_NLS_URL\}\}"></script>\s*$', '', template, flags=re.MULTILINE)
        (stage / 'workbench.html').write_text(template)
        controls_source = root / 'workbench/controls/extension'
        if controls_source.is_dir():
            subprocess.run(['node', str(root / 'workbench/controls/build.mjs')], check=True, cwd=root)
            shutil.copytree(controls_source, stage / 'controls/extension')
            shutil.copyfile(root / 'workbench/controls/native-startup.mjs', stage / 'controls/native-startup.mjs')
            shutil.copyfile(root / 'workbench/controls/file-transfers.mjs', stage / 'controls/file-transfers.mjs')
            shutil.copyfile(root / 'workbench/controls/port-forwarding.mjs', stage / 'controls/port-forwarding.mjs')
            shutil.copyfile(root / 'workbench/controls/auth-callbacks.mjs', stage / 'controls/auth-callbacks.mjs')
        licenses = stage / 'static/notices'
        licenses.mkdir(parents=True, exist_ok=True)
        for license_path in (root / 'workbench/licenses').iterdir():
            if license_path.is_file():
                shutil.copyfile(license_path, licenses / license_path.name)
        # Keep the scoped native controls and file-transfer hooks in the pinned
        # Code OSS bootstrap used by both Android Node and Linux SSH sessions.
        patcher = root / 'workbench/local/patch-workbench.py'
        workbench_js = stage / 'static/out/vs/code/browser/workbench/workbench.js'
        original_digest = digest(workbench_js)
        patch_function = runpy.run_path(str(patcher))['patch']
        workbench_js.write_text(patch_function(workbench_js.read_text()))
        embedding_patch = {'source': 'workbench/local/patch-workbench.py', 'originalSha256': original_digest, 'patchedSha256': digest(workbench_js)}
        # Rich browser builtin outputs are produced from the exact official
        # source commit by prepare-web-extensions.sh, independently of the
        # matched core client/agent binaries.
        web_root = root / '.local/upstream/vscode/.build/web'
        web_extensions = web_root / 'extensions'
        web_build_provenance = None
        if web_extensions.is_dir():
            provenance_path = web_root / 'vstab-build-provenance.json'
            if not provenance_path.is_file():
                raise SystemExit('The browser extension output has no completed build provenance; finish scripts/build-web-extensions.mjs first.')
            web_build_provenance = json.loads(provenance_path.read_text())
            if web_build_provenance.get('upstreamCommit') != spec['upstreamCommit']:
                raise SystemExit('The browser extensions do not match the pinned Code OSS source revision.')
            shutil.copytree(web_extensions, stage / 'static/extensions', dirs_exist_ok=True)
            shutil.copyfile(provenance_path, stage / 'web-extensions-build.json')
            packaged_builtin_extensions = []
            missing_browser_entries = []
            for extension_dir in sorted((stage / 'static/extensions').iterdir()):
                package_path = extension_dir / 'package.json'
                if not package_path.is_file():
                    continue
                package = json.loads(package_path.read_text())
                packaged_builtin_extensions.append(extension_dir.name)
                browser = package.get('browser')
                if browser:
                    entry = extension_dir / browser
                    if not entry.is_file() and not Path(str(entry) + '.js').is_file():
                        missing_browser_entries.append(extension_dir.name + ':' + browser)
            if missing_browser_entries:
                raise SystemExit('Built-in browser entry points are missing: ' + ', '.join(missing_browser_entries))
        local_manifest = stage / 'static/resources/vstab/manifest.json'
        local_manifest.parent.mkdir(parents=True, exist_ok=True)
        local_manifest.write_text(json.dumps({'name': 'vstab', 'short_name': 'vstab', 'display': 'standalone', 'start_url': '/'}, indent=2) + '\n')
        static_prefix = '/stable-' + spec['serverProductCommit'] + '/static/'
        configuration = {
            'remoteAuthority': 'remote',
            'serverBasePath': '.',
            'webviewEndpoint': static_prefix.rstrip('/') + '/out/vs/workbench/contrib/webview/browser/pre',
            'isEnabledFileDownloads': True, 'isEnabledFileUploads': True,
            'isEnabledFileCache': False, 'isEnabledCoderGettingStarted': False,
            'developmentOptions': {'logLevel': 3},
            'enableWorkspaceTrust': True,
            'configurationDefaults': {'workbench.startupEditor': 'none', 'telemetry.telemetryLevel': 'off'},
            'callbackRoute': 'stable-' + spec['serverProductCommit'] + '/callback',
            'productConfiguration': {
                'codeServerVersion': spec['codeServerVersion'],
                'nameShort': 'vstab', 'nameLong': 'vstab',
                'rootEndpoint': '.', 'logoutEndpoint': './logout',
                'proxyEndpointTemplate': './proxy/{{port}}/',
                'enableTelemetry': False, 'embedderIdentifier': 'vstab-android',
                'extensionsGallery': {
                    'serviceUrl': 'https://open-vsx.org/vscode/gallery',
                    'itemUrl': 'https://open-vsx.org/vscode/item',
                    'extensionUrlTemplate': 'https://open-vsx.org/vscode/gallery/{publisher}/{name}/latest',
                    'resourceUrlTemplate': 'https://open-vsx.org/vscode/asset/{publisher}/{name}/{version}/Microsoft.VisualStudio.Code.WebResources/{path}',
                    'controlUrl': '', 'recommendationsUrl': '',
                },
                'linkProtectionTrustedDomains': ['https://open-vsx.org'],
            },
        }
        files = {}
        for path in sorted((stage / 'static').rglob('*')):
            if path.is_file():
                files[path.relative_to(stage).as_posix()] = {'sha256': digest(path), 'bytes': path.stat().st_size}
        shared_files = shared_backend_assets(stage, files, originals, comparisons, static_prefix) if args.shared_linux_assets else {}
        controls_files = {}
        if (stage / 'controls').is_dir():
            for path in sorted((stage / 'controls').rglob('*')):
                if path.is_file():
                    controls_files[path.relative_to(stage).as_posix()] = {'sha256': digest(path), 'bytes': path.stat().st_size}
        manifest = {
            **{key: value for key, value in spec.items() if key != 'browserDependencies'},
            'staticPrefix': static_prefix,
            'assetRoot': 'workbench',
            'template': 'workbench/workbench.html',
            'templateSha256': digest(stage / 'workbench.html'),
            'templateSubstitutions': {
                'WORKBENCH_SCRIPT_NONCE': 'Fresh native-generated base64url nonce for each HTML response',
                'WORKBENCH_WEB_CONFIGURATION': 'JSON configurationDefaults plus native userDataPath and optional windowIndicator; HTML-attribute encode the full JSON',
                'WORKBENCH_AUTH_SESSION': '',
                'WORKBENCH_WEB_BASE_URL': static_prefix.rstrip('/'),
                'WORKBENCH_NLS_FALLBACK_URL': static_prefix + 'out/nls.messages.js',
                'BASE': '.', 'VS_BASE': '.',
                'VSTAB_LOCAL_BOOTSTRAP': 'Android supplies /controls/native-startup.mjs for Node-backed local/SSH workbenches',
            },
            'configurationDefaults': configuration,
            'remoteConfigurationDefaults': configuration,
            'packagedBuiltinExtensions': sorted(packaged_builtin_extensions),
            'embeddingPatch': embedding_patch,
            'officialWebExtensionsBuild': web_build_provenance,
            'controlsRuntimeFiles': controls_files,
            'userDataPathRule': 'ready.home + /.local/share/vstab/user-data',
            'files': files,
            'sharedBackendAssets': shared_files,
            'sharedBackendAssetCount': len(shared_files),
            'sharedBackendAssetBytes': sum(item['bytes'] for item in shared_files.values()),
            'sharedBackendVerification': ({
                'mode': 'exact original bytes on both pinned Linux architectures',
                'linuxAmd64ArchiveSha256': digest(args.archive),
                'linuxArm64ArchiveSha256': digest(args.arm64_archive),
            } if args.shared_linux_assets else None),
            'fileCount': len(files),
            'uncompressedBytes': sum(item['bytes'] for item in files.values()),
        }
        (stage / 'bundle.json').write_text(json.dumps(manifest, indent=2, sort_keys=True) + '\n')
        shutil.copyfile(root / 'workbench/SOURCES.md', stage / 'SOURCES.md')
        # Only the generated child directory is replaced; caller-provided output
        # roots and unrelated files are never removed.
        if destination.is_symlink():
            raise SystemExit('Refusing to replace a symlinked workbench output directory.')
        if destination.exists():
            previous_manifest = destination / 'bundle.json'
            if not previous_manifest.is_file():
                raise SystemExit('Refusing to replace a directory that is not a generated workbench bundle.')
            previous = json.loads(previous_manifest.read_text())
            if previous.get('assetRoot') != 'workbench' or previous.get('upstreamRepository') != spec['upstreamRepository']:
                raise SystemExit('Refusing to replace an unrecognized workbench output directory.')
            shutil.rmtree(destination)
        shutil.move(str(stage), str(destination))
    print(json.dumps({'assets': str(destination), 'staticPrefix': static_prefix, 'fileCount': manifest['fileCount'], 'uncompressedBytes': manifest['uncompressedBytes'], 'sharedBackendAssetCount': manifest['sharedBackendAssetCount'], 'sharedBackendAssetBytes': manifest['sharedBackendAssetBytes'], 'upstreamCommit': spec['upstreamCommit'], 'serverProductCommit': spec['serverProductCommit']}))


if __name__ == '__main__':
    main()
