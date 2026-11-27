# Android runtime

vstab's local editor uses the actual paired Code OSS 1.146.0 agent under an
Android-native Node 24 process. Node runs on Android's Bionic libc. The renderer
remains the real Code OSS workbench bundled in the APK. The remote SSH route
continues to use its matching Linux agent.

All executable native code ships in the APK's read-only native library directory.
This includes Node, Bash, Git, ripgrep, npm/npx entry points and the native PTY,
SQLite, Argon2 and file watcher addons. Backend JavaScript and support data are
extracted from checksum-verified APK assets into app-private storage. The addon
paths in that tree link to the corresponding read-only APK native libraries.
The app does not depend on Termux, a Linux guest, proot or an emulator on-device.

Build on a Linux x86_64 host with Android NDK r28, after preparing the pinned
workbench archive as described in [the Android build instructions](../android/README.md):

```sh
python3 scripts/prepare-android-runtime.py
python3 scripts/package-android-backend.py
python3 scripts/build-android-addons.py --ndk "$ANDROID_NDK_HOME"
python3 scripts/package-android-backend.py
```

The first backend packaging step extracts the paired JavaScript backend and its
native addon sources. The addon build consumes those sources; the final
packaging step refreshes the runtime identity with the completed native outputs.

The first command consumes the checked-in Termux package lock. Deliberately
refresh that lock with `--resolve-lock`; this verifies the package index against
Termux's pinned signing key before accepting new versions. Every downloaded
package and addon source is hashed. Both `arm64-v8a` and `x86_64` are built;
native outputs must use Android's loader and at least 16 KB ELF segment alignment.

Generated outputs stay under `.local/android-runtime/`:

- `jniLibs/<ABI>/`: read-only native executables and addons to package in the APK.
- `assets/local-runtime/backend.zip`: paired Code OSS/code-server JavaScript,
  built-in Node extensions and licenses. Incompatible desktop ELF payloads are
  removed; no protocol or product-commit check is bypassed.
- `assets/local-runtime/usr-<ABI>.zip`: shared libraries, npm JavaScript, terminal
  definitions, Git templates and third-party copyright notices.
- `assets/local-runtime/runtime.json`: content identity, archive hashes,
  executable/symlink contract and launch arguments consumed by Android.

The platform adaptation starts from VSCodroid's MIT-licensed Android runtime
research at commit `843708384856a8686355ddb75a63e43cce62b2a0`. Vendored files retain
its MIT license. Backend patches are narrow, hash-pinned changes recorded in
`release/android-server-patches.json`; `process.platform` remains `android`.
An optional desktop native watchdog is skipped on Android while Code OSS's
existing JavaScript parent-process monitor remains active. The rotating logger
uses VSCodroid's actual file-writing JavaScript implementation.

The four bundled native addon loaders use `process.dlopen` on Android to load
their actual read-only APK bindings. Their private `.node` paths are symlinks
to APK files with `.so` names; ordinary Node `require` would resolve those links
and mistakenly parse the ELF file as JavaScript. These narrow, hash-pinned
loader changes avoid global symlink flags and work in child processes, worker
threads and debug hosts independently of their Node arguments.

`runtime-probe.cjs` is for execution by the actual Android app process after
extracting its runtime. It exercises a native Node child process, Bash through
the real `node-pty` binding, SQLite, Argon2 and inotify. Host compilation alone
does not establish that these work in Android's app sandbox. Final qualification
must also open the ordinary app UI, edit and save a local file, use its integrated
terminal and activate a normal Node extension.

`NativeRuntimeBackendTest` exercises backend startup inside the Android
application process: runtime extraction, native component checks, the paired
Code OSS server and its authenticated native gateway. Run it against the APK
being evaluated; generated logs and qualification reports are local build
outputs. Ordinary editor UI journeys and physical ARM64 qualification are
separate checks.

Third-party executables supplied inside arbitrary extensions need compatible
Android binaries. Desktop glibc ELF payloads are not Bionic addons. An extension
being installable does not prove that every helper it ships runs on Android.
Termux Bash/Git and their libraries have their own GPL/LGPL/other notices under
the support ZIP's `share/vstab/notices` and `share/licenses`; those terms remain
separate from vstab's application code. A public binary distribution must retain
the component notices and provide its corresponding source/build inputs under
the relevant component licenses.
