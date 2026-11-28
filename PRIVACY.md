# VStab Privacy Policy

**Effective date: 28 November 2026**  
**Last updated: 28 November 2026**

This policy explains how the VStab project maintainers handle information in
connection with **VStab for Android**, package name **`com.vstab`**. The project is
maintained at [github.com/cwittenberg/vstab](https://github.com/cwittenberg/vstab).
For privacy questions or requests, contact **[vstab@env.now](mailto:vstab@env.now)**.

## Local editing and development

You do not need a VStab account to use the local editor or connect to your own SSH
server. The editor, bundled development tools and local terminal run on your
Android device. VStab does not upload your local projects, terminal contents or
SSH credentials to a central service operated by the VStab maintainers as part of
these core features.

The app stores information needed to provide its features, including:

- Project files, edits, working copies of shared documents, synchronization and
  recovery information, and files you create or download.
- Editor settings, workspace state, installed extensions, browser storage,
  session cookies, terminal history and tool configuration.
- SSH hostnames or IP addresses, ports, usernames, key references, host-key trust
  records, recent folders and connection timestamps.
- Imported SSH private keys, locally generated editor-session credentials, and
  credentials or sign-in tokens saved by tools or extensions you use.
- Editor, connection, extension and runtime diagnostics, which may contain file
  paths, server addresses, commands, error details or output produced by your
  tools.

App-private data is stored on the device. Files you open through Android's file
picker can also remain with their original document provider. VStab may keep
private working or recovery copies and write your edits back to the selected
file or folder. If that provider is cloud-backed, its operator processes the
files and changes under its own privacy practices.

Clipboard access supports copy, paste and file transfers. When you paste content
or files into a workspace, that content is copied into the local or remote
workspace you selected.

## SSH connections and remote workspaces

When you connect to an SSH server, VStab communicates with that server to
authenticate and provide editing, file transfers, terminal commands and port
forwarding. The server operator can receive your connection's network address,
username, authentication exchanges, and the files, commands and other data you
use in the remote workspace. The operator may keep its own access logs.

SSH passwords and private-key passphrases entered into VStab's connection prompts
are used for the connection and are not written to saved SSH profiles. Private
keys are used locally for public-key authentication. Imported key files retain
their original encryption; an imported unencrypted key remains unencrypted in
app-private storage. This does not prevent tools or extensions with access to
that environment from accessing those files.

VStab saves connection settings, imported keys and trusted host keys so you can
reconnect. It also stores generated credentials used by its local editor
service. Tools and extensions may separately save authentication tokens or other
credentials.

To prepare a remote workspace, VStab installs and runs editor software on the
server you select. Setup may download the editor distribution from GitHub.
Editor files, extensions, configuration, generated session credentials, logs and
workspace data can remain on that server after disconnection or removal of the
Android app.

## Extensions, AI assistants and other online services

VStab's built-in extension browsing and downloads use **Open VSX**, operated by
the Eclipse Foundation. Extension searches and downloads can send search terms,
extension identifiers and request information, such as your network address,
client/version information and request or search activity identifiers, to that
service and its delivery providers.

Git operations, package managers such as npm, web previews and terminal commands
connect to the repositories, registries, websites and other services selected by
you or configured in your projects and tools. Those recipients can receive the
request information and content needed for the operation. Forwarded web previews
can also contact services used by the previewed application.

Installed extensions and development tools run within your development
environment. Depending on the tool and its configuration, they can access
project files, terminal output, editor context and stored credentials, and can
make network requests, including background requests.

Optional AI tools, including **OpenAI Codex** and **Anthropic Claude Code**, may
send prompts, source code, selected files, conversation history, command output,
account information and technical diagnostics to their providers according to
the features and settings you use. Their accounts and authentication state may
be stored on your device or on the SSH host where the tool runs. Installing or
using an extension does not mean its processing is covered by VStab's editor
telemetry setting.

These services have their own privacy policies, retention rules and account
controls. Relevant examples include [Eclipse/Open VSX](https://www.eclipse.org/legal/privacy/),
[GitHub](https://docs.github.com/en/site-policy/privacy-policies/github-general-privacy-statement),
[OpenAI](https://openai.com/policies/privacy-policy/) and
[Anthropic](https://www.anthropic.com/legal/privacy). Review the policies and
settings of the services and extensions you choose. Their processing locations
may be outside your country.

## Telemetry, diagnostics and advertising

VStab configures telemetry in its bundled editor and editor backend to be off.
The core app does not automatically send its local editor or connection logs to
the VStab maintainers. Extensions, tools, remote servers and external websites
can have separate diagnostic or telemetry behavior.

Google Play and Android may collect crash, application-not-responding,
performance, app-version and device information according to your device and
Google settings. Google may make diagnostics available to VStab's developers
through Android vitals. This platform reporting is separate from the editor's
telemetry setting. See [Google's explanation of Android vitals](https://support.google.com/googleplay/android-developer/answer/9844486).

VStab does not sell personal information or use your project contents or SSH
credentials for advertising. The core app does not serve advertising or request
an advertising identifier.

## Permissions and security

Network access is used for the editor's local connections, SSH and online
features. Android's document picker grants access to the files or folders you
select. A foreground service and notifications support user-started development
processes while you work or switch to another app.

VStab uses Android's app-private storage and file permissions to restrict access
to its private files. Built-in remote connections use SSH encryption and host-key
verification. Local editor components communicate through loopback connections
on your device. Imported keys retain their existing encryption; VStab does not
add a separate encryption layer to all stored files. Connections made by your
own commands, extensions and external services depend on their implementations
and settings.

## Retention, deletion and your choices

Local projects, settings, imported keys, extension state and other persistent
app data remain until you delete them, clear the app's storage or uninstall it.
Tools and extensions may manage their own histories, caches and retention.
Removing a saved SSH host removes its saved connection entry; it does not
necessarily delete imported keys, terminal history or other copies of data.

To remove all app-private state, use Android's **Settings → Apps → VStab →
Storage → Clear storage**, or uninstall VStab. Export any local projects you want
to keep first. This does not delete original shared documents, cloud-provider
files, exported files, external-browser data, remote SSH workspaces or data held
by third-party accounts. Delete those copies through their respective file
provider, server, browser or service. Signing out of an extension or service may
also be needed to remove or revoke its credentials.

You can stop the local runtime, disconnect SSH, uninstall extensions, sign out of
third-party services and revoke file access by clearing the app's storage. You
can manage Google diagnostic sharing through your device and Google settings.

## Contact and support information

If you email **[vstab@env.now](mailto:vstab@env.now)**, we receive the address,
message, attachments and other information you choose to send. We use that
information to respond to your request, provide support and address related
privacy or security issues. Email providers process correspondence on our
behalf. We retain support correspondence only as needed for those purposes and
applicable legal obligations; you may ask us to delete it.

You may contact us to ask about information we hold about you or to request
access, correction or deletion where applicable. We cannot retrieve or delete
files stored only on your device, on your SSH server or in a third-party account
we do not control. Please do not send passwords, private keys, access tokens or
private project files when making a privacy request.

If you use GitHub to view this policy or contact the project through an issue,
GitHub processes that interaction under its own privacy policy. Public issues
are visible to others; use the email address above for private requests.

## Children and third-party accounts

For questions about a child's use of VStab or information sent to us, contact
**[vstab@env.now](mailto:vstab@env.now)**. Third-party services may have their own
age and account requirements.

## Changes to this policy

We may update this policy when VStab's features or data practices change. Updates
will be published with a revised date at the top of this page.
