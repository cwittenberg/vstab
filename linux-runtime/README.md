# Shared Linux runtime

The Android app hosts one real Linux development environment through bundled QEMU or an existing Termux installation. The guest uses the original paired Linux editor backend and normal Linux processes. Public extensions install their own publisher packages and engines; the runtime has no extension-specific execution adapters.

Run these commands from the repository root. Prerequisites are Docker with permission to run both `linux/amd64` and `linux/arm64` containers, Python 3.11+, Node, XZ, curl, GnuPG, ELF/binutils tools, Android SDK platform 36/build-tools 36.0.0/NDK 28.2.13676358 and JDK 17+. On an x86 build host, ARM64 rootfs assembly needs an AArch64 userspace binfmt interpreter; kernel compilation uses its own cross compiler container.

```sh
python3 scripts/build-android-qemu.py

for abi in x86_64 arm64-v8a; do
  python3 linux-runtime/guest/build-kernel.py --arch "$abi"
  python3 scripts/build-linux-guest.py --arch "$abi" \
    --kernel-dir ".local/linux-runtime/guest/kernel-$abi" \
    --server-cache "$PWD/.local/downloads" \
    --files-source linux-runtime/files/vstab-android-files.c
done

python3 linux-runtime/guest/validate-debian-snapshot.py

python3 scripts/package-workbench.py --shared-linux-assets \
  --archive .local/linux-runtime/guest/x86_64/inputs/code-server.tar.gz \
  --arm64-archive .local/linux-runtime/guest/arm64-v8a/inputs/code-server.tar.gz \
  --output .local/workbench-linux-assets
```

The QEMU builder uses the checked-in signed-package lock. `--resolve-lock` deliberately updates that lock and is not needed for a normal rebuild. Guest builds use the container, Linux source, editor archive and signed official Debian snapshot pins in `release/linux-guest.lock.json`. The snapshot checker verifies the recorded downloaded package hashes against authenticated indexes for both architectures. Guest package updates remain available through normal Debian sources. Inputs are pinned; filesystem creation timestamps/UUIDs are regenerated, so rebuilt disks are identified by their own generated manifest hashes rather than assumed bit-identical.

Freeze the host and guest assets into one directory, then build a single-ABI debug APK:

```sh
mkdir -p .local/linux-apk-assets
cp -a .local/linux-runtime/assets/. .local/linux-apk-assets/
cp -a .local/linux-runtime/guest/assets/. .local/linux-apk-assets/

export VSTAB_LINUX_ASSETS="$PWD/.local/linux-apk-assets"
export VSTAB_WORKBENCH_ASSETS="$PWD/.local/workbench-linux-assets"
export VSTAB_RUNTIME_ABI=x86_64
tools/build-android.sh assembleDebug
```

Set `ANDROID_HOME` and `JAVA_HOME` before building; use `arm64-v8a` for an ARM64 APK. `VSTAB_LINUX_ASSETS` overrides both host and guest asset roots, so its directory must contain both manifests. The workbench's shared-assets mode keeps modified frontend files in the APK and omits only original files whose bytes match both pinned backend archives; Android verifies the corresponding backend bytes before use. The debug APK is written to `android/app/build/outputs/apk/debug/app-debug.apk`.

For signed bundles and APKs, follow [the Android release instructions](../android/README.md). Pinned, pre-stripped QEMU builds report unavailable native symbol coverage; no symbol archive is fabricated. Use `--require-native-symbols` with the release builder to require full coverage.

Host-only checks:

```sh
node --test linux-runtime/guest/stop-request.test.cjs \
  linux-runtime/guest/build-metadata.test.cjs linux-runtime/guest/open-uri.test.cjs
python3 linux-runtime/guest/prove-boot.py --arch x86_64 --prove-stop-request

docker build -f linux-runtime/guest/Proof.Dockerfile \
  -t vstab-linux-guest-proof:local linux-runtime/guest
python3 linux-runtime/guest/prove-boot.py --arch arm64-v8a \
  --qemu-container vstab-linux-guest-proof:local --prove-stop-request
```

Images currently use a persistent whole-root ext4 disk. The build/API/data metadata and installed kernel must stay pinned to that disk; a shipped update must never silently replace it or relabel it as the new build. Separate `/home` storage and automatic disk migration are deferred. Android raw-folder/SAF permissions still apply to files exposed through the broker. Host QEMU checks do not prove Android provider behavior, physical ARM64 performance, Termux installation/reconnect or authenticated third-party service operation.

The compressed seed remains 2 GiB; advertised sparse capacity is 8 GiB. Hosts enlarge only stopped disks whose installed metadata advertises `diskGrowth`; the guest then uses ordinary ext4 online growth before starting the backend. `--prove-disk-growth` checks that growth preserves an existing user file. Logical sparse capacity does not preallocate the entire image on Android storage.

See [guest](guest/README.md), [host](host/README.md) and [files](files/README.md) for their interfaces and focused checks.
