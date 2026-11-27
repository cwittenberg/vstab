# Codex extension compatibility

vstab installs extensions through the Code OSS Extensions view. Codex is a
separate publisher extension; it is not bundled in the APK.

The developer inspection pin for `openai.chatgpt` is in
[`extensions/codex/linux-x64.lock.json`](../extensions/codex/linux-x64.lock.json).
It selects the stable Linux x64 package. Package identity, version and checksum
are inspection inputs, not a claim that the extension has passed Android or
provider authentication tests.

## Inspect the pinned package

```sh
python3 scripts/codex-package.py --download --inspect
```

The helper verifies the publisher VSIX against the pinned SHA-256 and checks
its manifest identity. Downloads and extracted inspection reports stay in
ignored `.local/` directories. It does not install, execute or authenticate the
extension. Inspect the package's own license before redistribution.

## Runtime boundary

The pinned package has a Node extension entry point and Linux native helpers.
Use a matching Linux x64 SSH workspace to evaluate that package. Its installability
in the gallery does not establish that desktop helper binaries run under
Android's Bionic runtime. Qualify other host architectures and Android-compatible
packages separately.

## Verify in the app

1. Connect to a controlled Linux SSH workspace through the normal vstab UI.
2. Search `@id:openai.chatgpt` in Extensions and install the publisher extension.
   Record the version actually installed; stable and prerelease gallery versions
   can differ from the developer inspection pin.
3. Review workspace and publisher trust prompts for that controlled project.
4. Open **Codex: Open Codex Sidebar**. Verify its graphical panel, extension-host
   activation and native helper process, including after reconnection.
5. Test the user's chosen provider sign-in flow, a model request, edits and
   approvals separately. A rendered sign-in panel proves only that panel's
   activation.

Record APK identity, host architecture, installed package identity, logs and
captures under ignored `artifacts/`. Keep credentials out of reports. Follow
[the demonstration guide](DEMONSTRATION.md) for broader local and SSH checks;
physical-tablet behavior needs its own evidence.
