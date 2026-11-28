# Android runtime

Includes Node.js, Bash, Git, ripgrep, npm/npx and native extension dependencies.
Requires Linux x86_64 and Android NDK r28 to build.

OpenSSH clients are built from the pinned portable source so they use the same
private SSH configuration, imported keys and known hosts as Remote Explorer.

```sh
python3 scripts/prepare-android-runtime.py
python3 scripts/build-android-openssh.py --ndk "$ANDROID_NDK_HOME"
python3 scripts/package-android-backend.py
python3 scripts/build-android-addons.py --ndk "$ANDROID_NDK_HOME"
python3 scripts/build-android-claude.py --ndk "$ANDROID_NDK_HOME"
python3 scripts/package-android-backend.py
```

The first packaging pass prepares addon sources; the second includes compiled addons.

| Output under `.local/android-runtime/` | Contents |
| --- | --- |
| `jniLibs/<ABI>/` | Native executables and addons; ARM64 and x86_64 |
| `assets/local-runtime/backend.zip` | Editor backend and extensions |
| `assets/local-runtime/usr-<ABI>.zip` | Libraries, tools, support files and licenses |
| `assets/local-runtime/runtime.json` | Runtime manifest and checksums |

Package versions: [runtime lock](../release/android-runtime.lock.json). Refresh with `prepare-android-runtime.py --resolve-lock`.

Android adaptations derive from [VSCodroid](https://github.com/rmyndharis/VSCodroid/tree/843708384856a8686355ddb75a63e43cce62b2a0); retain its [MIT license](vendor/VSCodroid-LICENSE.txt).
Keep bundled component notices in `share/vstab/notices` and `share/licenses`; provide corresponding sources where their licenses require them.

## Native crash symbols

Release builds prepare real function symbol tables using
`scripts/prepare-android-native-symbols.py`. The resulting `<ABI>/*.so.sym`
directory is supplied to Android Gradle Plugin's `NATIVE_SYMBOL_TABLES` artifact
and embedded in the Play bundle. This preserves the runtime files and their
manifest checksums.

The preparation report records each payload's SHA-256, GNU build ID when present,
defined function count and coverage. Each symbol file must have a static symbol
table and the same allocated sections and build ID as its corresponding runtime
ELF. Native addon and x86_64 musl builds preserve their linked files under
`.local/android-runtime/unstripped/` before stripping the runtime copies.

Pre-stripped vendor libraries cannot recover their missing function symbols.
They are listed as unavailable and excluded from the symbol archive; the archive
does not claim complete runtime coverage. Rebuild the matching component from its
pinned sources or obtain its exact upstream symbol files to extend coverage.
