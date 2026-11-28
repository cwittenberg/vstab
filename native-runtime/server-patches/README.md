# Android server patches

```sh
python3 native-runtime/patch-server.py /path/to/extracted/code-server \
  --manifest-out /path/to/runtime/server-patches.json
```

Also accepts an extracted `lib/vscode` directory. Use `--dry-run` to check inputs.
Version and file hashes are pinned in [pins.json](pins.json).

| Patch | Purpose |
| --- | --- |
| Platform and user-data paths | Android environment support |
| Watchdog | Keep the JavaScript process monitor; skip the incompatible native addon |
| ripgrep | Use `VSTAB_RIPGREP_PATH` for the bundled executable |

Set `HOME`, `XDG_CONFIG_HOME`, `TMPDIR`, `SHELL`, `PATH`, `LD_LIBRARY_PATH`, `TERM` and `TERMINFO` for the packaged runtime.
Set `VSTAB_RIPGREP_PATH` to `applicationInfo.nativeLibraryDir/librg.so`.
Adapted from [VSCodroid](https://github.com/rmyndharis/VSCodroid/tree/843708384856a8686355ddb75a63e43cce62b2a0) under its [MIT license](../vendor/VSCodroid-LICENSE.txt).
