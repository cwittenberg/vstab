# Extension activation diagnostics

The `vstab.extension-compatibility` Node workspace extension runs on Android and
on the SSH workspace backend. Install `extension/` as a built-in extension on
each backend. Its command is **vstab: Show Extension Compatibility Report**.

It reads only the current extension host's `remoteexthost.log` or `exthost.log`,
derived from VS Code's `ExtensionContext.logUri`. Its parser follows the real
`extHostExtensionActivator.ts` failure records, including the multiline error
record that follows the header. It does not attempt to activate other extensions
and never treats an inactive extension as a failed extension.

An observed installed extension failure produces a warning naming the extension,
a status-bar entry, and a redacted details report. Android native or permission
failures include **Connect to SSH Host…**, which invokes the existing
`vstab.ssh.connect` command. Repeated equivalent failures are notified once per
24 hours; the report remains available. Reads, error records and stored results
are bounded, and file replacement/truncation resets the parser.

This detects activation failures of extensions in the same Node host. An
extension that activates successfully and later hides a helper-process error,
or a failure in the separate browser-worker host, may not write such a record.
Those are not silently claimed to be covered. Installation-time compatibility
cannot be guaranteed from a manifest alone.

Run the unit and filesystem integration checks:

```sh
node --test workbench/compatibility/test/*.test.cjs
```
