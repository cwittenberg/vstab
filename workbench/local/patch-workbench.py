#!/usr/bin/env python3
"""Add scoped native controls and local hooks to the pinned real Code OSS bootstrap."""
import argparse
import hashlib
from pathlib import Path
import re

MARKER = '/*vstab-local-embedder-v4*/'
ORIGINAL_SHA256 = '6cd0f009b42cc337cd45812f3dfa3cb1886a91e29720aa60683b8c306ef08f3e'
PATCHED_SHA256 = 'f0c733ab73fa19a05ada34eb27176d6c48f0cbd902849b8e53e14f5a6735f6e3'


def patch(source: str) -> str:
    if MARKER in source:
        if hashlib.sha256(source.encode()).hexdigest() != PATCHED_SHA256:
            raise ValueError('The v4 workbench hook differs from its expected output')
        return source
    if '/*vstab-local-embedder-' in source:
        raise ValueError('Stale vstab embedding hook; re-extract the original pinned workbench before patching')
    if hashlib.sha256(source.encode()).hexdigest() != ORIGINAL_SHA256:
        raise ValueError('Workbench differs from the pinned Code OSS artifact; refusing to patch')
    # code-server's local-storage.diff changes the browser user-data URI to an
    # unconditional remote path. Restore the exact upstream web URI locally so
    # BrowserMain uses its existing IndexedDB provider without an SSH server.
    roaming = re.compile(
        r'get userRoamingDataHome\(\)\{return (?P<join>\w+)\((?P<uri>\w+)\.file\(this\.userDataPath\)'
        r'\.with\(\{scheme:(?P<schemas>\w+)\.vscodeRemote\}\),"User"\)\}'
    )
    roaming_matches = list(roaming.finditer(source))
    if len(roaming_matches) != 1:
        raise ValueError('Cannot identify the pinned remote-only user-data getter; refusing to patch')
    roaming_match = roaming_matches[0]
    original_roaming = roaming_match.group(0)[len('get userRoamingDataHome(){return '):-1]
    restored = ('get userRoamingDataHome(){return this.remoteAuthority?'
                + original_roaming + ':' + roaming_match['uri']
                + '.file("/User").with({scheme:' + roaming_match['schemas'] + '.vscodeUserData})}')
    source = source[:roaming_match.start()] + restored + source[roaming_match.end():]
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
    uri_aliases = set(re.findall(r'folderUri:(\w+)\.revive\(\w+\.folderUri\)', source[-12000:]))
    if len(uri_aliases) != 1:
        raise ValueError('Cannot identify the pinned bootstrap URI reviver; refusing to patch')
    uri_alias = uri_aliases.pop()
    control_hook = (
        'function vstabApplyControls(options){'
        'const port=globalThis.__vstabControlOptions?.messagePorts?.get("vstab.android-controls");'
        'if(port){options.messagePorts=new Map(options.messagePorts||[]);'
        'options.messagePorts.set("vstab.android-controls",port);'
        'options.enabledExtensionProposedApi=Array.from(new Set([...(options.enabledExtensionProposedApi||[]),"vstab.android-controls"]));}'
        'return options;}'
    )
    hook = (
        MARKER + control_hook + 'if(globalThis.__vstabLocalOptions){'
        + 'const vstabOptions=globalThis.__vstabLocalOptions;'
        + 'if(vstabOptions.workspaceProvider?.workspace?.folderUri)'
        + 'vstabOptions.workspaceProvider.workspace.folderUri=' + uri_alias
        + '.revive(vstabOptions.workspaceProvider.workspace.folderUri);'
        + 'globalThis.__vstabWorkbench='
        + match['create'] + '(' + match['window'] + '.document.body,vstabApplyControls({...JSON.parse('
        + match['settings'] + '),...vstabOptions}));return;}'
    )
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
    print('vstab local embedder patch: ' + hashlib.sha256(original).hexdigest() + ' -> ' + hashlib.sha256(result).hexdigest())


if __name__ == '__main__':
    main()
