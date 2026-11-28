# Android runtime

Includes Node.js, Bash, Git, ripgrep, npm/npx and native extension dependencies.
Requires Linux x86_64 and Android NDK r28 to build.

```sh
python3 scripts/prepare-android-runtime.py
python3 scripts/package-android-backend.py
python3 scripts/build-android-addons.py --ndk "$ANDROID_NDK_HOME"
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

[Full build](../docs/BUILD.md)
