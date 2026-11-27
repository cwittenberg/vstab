# Android native paired-server adaptations

This helper modifies the exact Code OSS **1.146.0** / code-server **4.146.0** JavaScript already paired with vstab's renderer. It does not combine VSCodroid's 1.141.2 renderer/server with this release or bypass protocol commit checks.

```sh
python3 native-runtime/patch-server.py /path/to/extracted/code-server \
  --manifest-out /path/to/runtime/server-patches.json
```

An extracted `lib/vscode` directory is also accepted. Source-file SHA-256 pins and expected match counts make an unknown release or partial patch fail closed. Reapplication verifies the recorded outputs and leaves them unchanged. A dry run validates all inputs without changing them.

## Changes

1. The bundled common-platform code treats a real Node `process.platform === 'android'` as Linux-shaped for Code OSS's internal OS/configuration decisions. Node's platform value is not changed globally.
2. The server and PTY host's direct user-data-path switch accepts Android and uses its normal `XDG_CONFIG_HOME` or `~/.config` fallback.
3. Android skips the optional native watchdog module. The existing upstream one-second JavaScript parent-PID monitor remains active. This allows the packager to remove the incompatible foreign watchdog ELF without replacing its API with a success stub.
4. `@vscode/ripgrep-universal` selects the explicit readonly Android-native `VSTAB_RIPGREP_PATH` on Android. A missing path fails clearly. Other platforms retain the original behavior.

The platform/user-data adaptations follow the MIT-licensed VSCodroid source approach, rebased onto our exact paired bundles. [Platform source patch](https://github.com/rmyndharis/VSCodroid/blob/843708384856a8686355ddb75a63e43cce62b2a0/patches/0001-platform-treat-android-as-linux.patch), [user-data source patch](https://github.com/rmyndharis/VSCodroid/blob/843708384856a8686355ddb75a63e43cce62b2a0/patches/0002-userdatapath-accept-android.patch).

## Native runtime contract

- Start a separate Bionic Node executable from the APK's readonly native-library directory. `process.execPath` must remain that executable; ordinary `child_process.fork` then executes the real native Node binary.
- Set `HOME`, `XDG_CONFIG_HOME`, `TMPDIR`, `SHELL`, `PATH`, `LD_LIBRARY_PATH`, `TERM` and `TERMINFO` to the packaged Android environment. `SHELL` can be an app-private symlink resolving to readonly bundled Bash.
- Set `VSTAB_RIPGREP_PATH` to `applicationInfo.nativeLibraryDir/librg.so`.
- No `NODE_OPTIONS` preload is required by this minimal patch. Do not globally spoof Android as Linux for npm/extensions: packages with genuine Android builds must still detect Android.
- Supply Bionic/Node-ABI-compatible native addons at the exact package paths, including node-pty **1.2.0-beta.18**, watcher **2.5.6**, sqlite **5.1.12-vscode**, and code-server's authentication addon. Logging must keep a working implementation.
- Current node-pty JavaScript already selects Unix for non-Windows and checks `build/Release/pty.node` first. No fabricated PTY or OS dispatch shim is added.
- Ordinary native forks are retained for the initial proof. VSCodroid's worker-thread process-budget/reconnection adaptations are a separate possible change if physical Android behavior requires them.

Native ELF compilation, library closure, 16 KiB compatibility, Android process lifecycle and actual on-device execution are separate checks. A successful static patch is not proof of a working native IDE.

## Extension binary compatibility

Pure JavaScript Node extensions can use the actual local Node extension host once it is qualified. A Linux glibc helper is not automatically Android-compatible. VSCodroid separately chooses Alpine/musl executable packages and uses loader adaptations; dlopen'd `.node` addons need their own Bionic/ABI assessment. [Primary target-platform patch](https://github.com/rmyndharis/VSCodroid/blob/843708384856a8686355ddb75a63e43cce62b2a0/patches/0009-marketplace-request-alpine-on-android.patch).

This helper does not bundle a substitute Codex CLI, change a publisher extension, claim universal extension compatibility or introduce CPU/OS emulation. The earlier actual Android Codex installation evidence belongs to the SSH host; local Codex execution is a separate qualification target.
