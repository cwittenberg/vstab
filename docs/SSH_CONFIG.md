# Canonical SSH configuration

Saved aliases live in the Android app's real native `HOME/.ssh/config`. The
Remote Explorer and its `vstab-ssh-config:/config` editor use that one file,
including while a remote workspace is open. Folder history and document-picker
migration references remain a separate native metadata overlay keyed by the
stable alias ID.

`SshConfigStore` resolves explicit `Host` aliases against ordered matching rules.
Scalar options use OpenSSH's first obtained value; matching `Host *` defaults
therefore belong after more specific rules when they should act as fallbacks.
Negated patterns are honored. `IdentityFile` values accumulate in order and are
expanded without opening the key files. Supported settings are `HostName`,
`User`, `Port`, `IdentityFile`, `IdentitiesOnly` and `PreferredAuthentications`.
Authentication methods currently implemented are `publickey` and `password`;
explicit `keyboard-interactive` and other methods produce blocking diagnostics.
Paths support the Android home `~`, `~/`, and `%h`, `%n`, `%r`, `%p`, `%d`, `%%`
substitutions. Environment expansion and `~other-user` are diagnosed.

Top-level `Include` resolves sorted file patterns only inside the app's `.ssh`
directory, with canonical-path checks, at most 8 nesting levels, 32 files and
256 KiB aggregate input. Wildcards in directory components and conditional
Include inside `Host`/`Match` are explicitly diagnosed. Included host entries
are read-only through the individual-host form; their source files are not
silently rewritten. `Match`, `ProxyCommand`, `ProxyJump` and other unimplemented
settings produce actionable diagnostics and prevent affected profiles from
being connected with silently different settings. No command is executed by
the parser.

The config editor saves exact UTF-8 user text, including comments, whitespace
and line endings. Invalid or unsupported text can be saved and produces editor
diagnostics. Saves require the last-read SHA-256 revision, use a per-file lock,
sync a private temporary file, recheck the revision and atomically replace the
config. A stale save returns `SSH_CONFIG_CONFLICT` and keeps the newer file.
Both the file and input are limited to 256 KiB; NULs and invalid UTF-8 are
rejected. The canonical config is an ordinary private file, not an arbitrary
symlink target.

Adding a host parses `ssh` arguments without running them: `-p`, `-l`, `-i`,
supported `-o` fields and `user@host` are accepted, including connection flags
placed after the host. A second bare word, remote commands and other
flags are rejected before writing anything. Simple host edits preserve other
blocks and all comment text. Shared or repeated rules require the config editor
instead of a form edit that could change their inheritance. Removing a literal
alias preserves other aliases in a shared header, unrelated blocks and comments.

The store never receives authentication passwords/passphrases and never reads
private key bytes. Public profile metadata contains aliases, endpoints, key
path labels, zero-based source lines and diagnostics. Native key import and
session credential prompts belong to the Android connection layer.

`SshConfigStoreTest` covers first-value resolution, pattern negation, repeated
identities, escaped/quoted paths, post-host flags, unsupported authentication,
safe token expansion, includes and recursion,
raw invalid-text saves, byte limits, conflicting readers, comment-preserving
edits/removal, command parsing and stable IDs. Android instrumentation and the
ordinary UI flow are separate checks.
