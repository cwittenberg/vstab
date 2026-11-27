# Claude Code extension target

The official target is **`Anthropic.claude-code`**, installed by the user from Open VSX into the SSH host's Node extension environment. The developer inspection pin is recorded in `linux-x64.lock.json`. Downloaded packages and inspection reports stay under ignored `.local/` directories.

`python3 scripts/claude-package.py --download --inspect` caches the unmodified publisher VSIX under ignored `.local/downloads/`, verifies the pinned registry SHA-256 and inspects its manifest. It does not install the extension or read any provider credentials. Keep the actual Android demonstration's Extensions-view installation independent of this developer inspection.

The inspected extension has a Node entry point, no browser entry and a bundled native runtime. It is therefore a remote Linux extension target, not a local browser-worker extension. Its native package must match the remote host's architecture, not the tablet's.

Qualification requires installing the actual extension through vstab, opening its graphical panel, checking its native runtime starts and exercising its own sign-in flow. A panel reaching sign-in is activation evidence; authenticated prompts, edits and approvals require separate provider authentication and workflow proof.

Anthropic documents [Open VSX and compatible VS Code forks](https://code.claude.com/docs/en/vs-code). Keep its package unmodified, preserve its own authentication choices and use the end user's account. Its [legal and product integration terms](https://code.claude.com/docs/en/legal-and-compliance) remain applicable; vstab does not collect or substitute provider credentials.
