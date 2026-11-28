#!/usr/bin/env python3
"""Add scoped native controls and file-transfer hooks to the pinned real Code OSS bootstrap."""
import argparse
import hashlib
from pathlib import Path
import re

MARKER = '/*vstab-native-embedder-v1*/'
ORIGINAL_SHA256 = '6cd0f009b42cc337cd45812f3dfa3cb1886a91e29720aa60683b8c306ef08f3e'
PATCHED_SHA256 = 'e394b300b94a65b756e5c198e2eb3dde6fdc61bfbb2f3998b00ca25fe3a53197'


def patch(source: str) -> str:
    if MARKER in source:
        if hashlib.sha256(source.encode()).hexdigest() != PATCHED_SHA256:
            raise ValueError('The native workbench hook differs from its expected output')
        return source
    if '/*vstab-local-embedder-' in source or '/*vstab-native-embedder-' in source:
        raise ValueError('Stale vstab embedding hook; re-extract the original pinned workbench before patching')
    if hashlib.sha256(source.encode()).hexdigest() != ORIGINAL_SHA256:
        raise ValueError('Workbench differs from the pinned Code OSS artifact; refusing to patch')
    # Android owns the destination picker. Keep large individual files on the
    # regular download path so the main-frame adapter can stream them to SAF.
    # Folder downloads retain upstream's directory flow and its own limits.
    large_download = 'r=i.isDirectory||i.size>n,a=xo();if(r&&Kg.supported(a))'
    if source.count(large_download) != 1:
        raise ValueError('Cannot identify the pinned browser download path; refusing to patch')
    source = source.replace(large_download, 'r=i.isDirectory,a=xo();if(r&&Kg.supported(a))', 1)
    # A browser may retain an old Android URI clipboard when Code OSS's custom
    # MIME write fails. Explicit Explorer copies must still use its own in-memory
    # file clipboard. Emit only a local signal, before attempting that write.
    copy_resources = 'async writeResources(e){try{await xo().navigator.clipboard.write('
    if source.count(copy_resources) != 1:
        raise ValueError('Cannot identify the pinned Explorer clipboard writer; refusing to patch')
    source = source.replace(copy_resources,
        'async writeResources(e){if(e.length)xo().dispatchEvent(new Event("vstab-explorer-copy"));try{await xo().navigator.clipboard.write(', 1)
    # Use Android's real SSH listeners instead of code-server's synthetic
    # localhost address and authenticated HTTP proxy. Keep upstream behavior
    # when the native bootstrap is absent.
    resolver = 'resolveExternalUri:n=>{let r=n,a=yvt(r);'
    factory = 'tunnelProvider:{tunnelFactory:'
    if source.count(resolver) != 1 or source.count(factory) != 1:
        raise ValueError('Cannot identify the pinned Ports provider and URI resolver; refusing to patch')
    source = source.replace(resolver,
        'resolveExternalUri:n=>{if(globalThis.__vstabControlOptions?.resolveExternalUri)return globalThis.__vstabControlOptions.resolveExternalUri(n);let r=n,a=yvt(r);', 1)
    source = source.replace(factory,
        'tunnelProvider:globalThis.__vstabControlOptions?.tunnelProvider??{tunnelFactory:', 1)
    callback = 'urlCallbackProvider:new M8o(e.callbackRoute)'
    if source.count(callback) != 1:
        raise ValueError('Cannot identify the pinned URL callback provider; refusing to patch')
    source = source.replace(callback,
        'urlCallbackProvider:globalThis.__vstabControlOptions?.urlCallbackProvider?.(A)??new M8o(e.callbackRoute)', 1)
    tunnel_address = 'tunnelRemotePort:f.remoteAddress.port,tunnelRemoteHost:f.remoteAddress.host,localAddress:await this.resolveExternalUri(v)'
    if source.count(tunnel_address) != 1:
        raise ValueError('Cannot identify the pinned Ports local address conversion; refusing to patch')
    source = source.replace(tunnel_address,
        'tunnelRemotePort:f.remoteAddress.port,tunnelRemoteHost:f.remoteAddress.host,tunnelLocalPort:f.localPort,localAddress:await this.resolveExternalUri(v)', 1)
    # Derive minifier aliases from the existing startup call; never patch an
    # arbitrary create-like call elsewhere in the bundled workbench.
    pattern = re.compile(
        r'(?P<guard>if\(!(?P<element>\w+)\|\|!(?P<settings>\w+)\)throw new Error\("Missing web configuration element"\);)'
        r'(?P<rest>let (?P<config>\w+)=\{\.\.\.JSON\.parse\((?P=settings)\),remoteAuthority:location\.host\}[^;]*;'
        r'(?P<create>\w+)\((?P<window>\w+)\.document\.body,\{\.\.\.(?P=config),)'
    )
    matches = list(pattern.finditer(source))
    if len(matches) != 1:
        raise ValueError(f'Expected one pinned Code OSS bootstrap, found {len(matches)}; refusing to patch')
    match = matches[0]
    control_hook = (
        'function vstabApplyControls(options){'
        'const port=globalThis.__vstabControlOptions?.messagePorts?.get("vstab.android-controls");'
        'if(port){options.messagePorts=new Map(options.messagePorts||[]);'
        'options.messagePorts.set("vstab.android-controls",port);'
        'options.enabledExtensionProposedApi=Array.from(new Set([...(options.enabledExtensionProposedApi||[]),"vstab.android-controls"]));}'
        'return options;}'
    )
    hook = MARKER + control_hook
    normal_startup = match['rest'].replace('...' + match['config'] + ',',
                                          '...vstabApplyControls(' + match['config'] + '),', 1)
    return (source[:match.end('guard')] + hook + normal_startup + source[match.end():])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('input', type=Path)
    parser.add_argument('output', type=Path, nargs='?')
    args = parser.parse_args()
    original = args.input.read_bytes()
    result = patch(original.decode()).encode()
    (args.output or args.input).write_bytes(result)
    print('vstab native embedder patch: ' + hashlib.sha256(original).hexdigest() + ' -> ' + hashlib.sha256(result).hexdigest())


if __name__ == '__main__':
    main()
