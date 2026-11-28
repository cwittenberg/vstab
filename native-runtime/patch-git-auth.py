#!/usr/bin/env python3
"""Let the pinned built-in Git askpass UI handle SSH password/MFA prompts."""
import hashlib
from pathlib import Path
import sys

ORIGINAL_SHA256 = 'cb1ac82ab503597c2045f89782d1b9f17d0e3a5b8bf9aa7f3523ecacad81594b'


def patch(path: Path) -> dict:
    data = path.read_bytes()
    original = hashlib.sha256(data).hexdigest()
    if original != ORIGINAL_SHA256:
        raise ValueError('Built-in Git differs from the pinned Code OSS artifact')
    source = data.decode()
    needle = 'async handleSSHAskpass(e){let t=e[3];'
    if source.count(needle) != 1:
        raise ValueError('Cannot identify the pinned SSH askpass handler')
    # Desktop's upstream handler distinguishes passphrases and host consent but
    # assumes everything else is host consent. A real OpenSSH password or MFA
    # prompt has no host/fingerprint fields at those offsets. Keep the existing
    # specialized host/passphrase controls and use its normal secret input for
    # the remaining SSH authentication questions.
    replacement = ('async handleSSHAskpass(e){let vstabPrompt=e.slice(2).join(" ");'
        'if(!/passphrase/i.test(vstabPrompt)&&'
        '!/authenticity of host|Are you sure you want to continue/i.test(vstabPrompt))'
        'return await ii.window.showInputBox({password:!0,prompt:vstabPrompt,ignoreFocusOut:!0})||"";'
        'let t=e[3];')
    result = source.replace(needle, replacement, 1).encode()
    path.write_bytes(result)
    return {'originalSha256': original, 'patchedSha256': hashlib.sha256(result).hexdigest(),
            'sshPasswordPrompts': 1}


if __name__ == '__main__':
    import json
    print(json.dumps(patch(Path(sys.argv[1]))))
