# Codex and Claude extension compatibility

Install the publisher extensions through the Code OSS Extensions view:
`openai.chatgpt` for Codex and `Anthropic.claude-code` for Claude Code. Neither
extension is bundled in the APK. Workspace Trust and publisher-trust decisions
remain part of the normal installation and activation flow.

## Backend requirements

The developer inspection packages target Linux x64 and include native helpers.
Evaluate those packages in a matching Linux x64 SSH workspace. A successful
extension installation does not establish that its desktop helper binaries run
under Android's Bionic runtime. Other host architectures and Android-compatible
packages require separate checks.

The publisher's own sidebar is distinct from Code OSS's generic Chat view.
Opening a sign-in panel establishes only that panel's activation. Provider
authentication, model requests, edits and approvals need their own end-to-end
validation. Local Android helper execution and physical tablet behavior are
not established by the package inspection tools.

## Pinned package inspection

The inspection inputs record publisher, version, target platform and checksum:

- [Codex Linux x64 lock](../extensions/codex/linux-x64.lock.json)
- [Claude Code Linux x64 lock](../extensions/claude/linux-x64.lock.json)

```sh
python3 scripts/codex-package.py --download --inspect
python3 scripts/claude-package.py --download --inspect
```

These helpers verify the publisher VSIX against its pinned SHA-256 and inspect
the manifest. Downloads and inspection reports stay under ignored `.local/`
directories. They do not install, execute or authenticate either extension, and
do not read provider credentials. Gallery versions may differ from the pins;
record the version actually installed when validating the app. Review each
package's own license before redistribution.

## Verify in the app

1. Connect to a controlled Linux SSH workspace through vstab.
2. Search the exact extension ID in Extensions and install the publisher package.
3. Review the workspace and publisher-trust prompts for that project.
4. Open the extension's graphical sidebar and check extension-host activation
   and the native helper process, including after SSH reconnect.
5. Exercise the chosen provider sign-in flow, a model request, an edit and any
   approval flow required for the intended use.

Keep the APK identity, host architecture, installed package identity and results
with local validation records under ignored `artifacts/`. Keep credentials and
authentication responses out of those records.
