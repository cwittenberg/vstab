# SSH configuration

1. Open **Remote Explorer** and add a host.
2. Connect with a password or imported SSH key.
3. Check the server fingerprint before accepting a new host key.
4. Open the remote project with **File → Open Folder...**.

## Configuration

Edit the on-device `~/.ssh/config` through Remote Explorer:

```sshconfig
Host dev
    HostName example.com
    User developer
    Port 22
    IdentityFile ~/.ssh/keys/dev
```

| Setting | Support |
| --- | --- |
| `Host`, wildcards and negated patterns | Supported; put specific hosts before `Host *` |
| `HostName`, `User`, `Port` | Supported |
| `IdentityFile`, `IdentitiesOnly` | Supported; repeated identity files allowed |
| `PreferredAuthentications` | `publickey,password` |
| Top-level `Include` | Files within the app's `.ssh` directory |
| `ProxyJump`, `ProxyCommand`, `Match` | Not supported |
| Keyboard-interactive/MFA | Not supported |

Import keys through **Import SSH Key**. Passwords and key passphrases are prompted when connecting and are not saved in SSH config.

Unsupported settings appear as diagnostics. Fix them before connecting.
Included files are read-only in the host form; edit their source files instead.
