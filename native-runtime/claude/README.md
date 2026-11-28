# Claude extension runtime

`scripts/build-android-claude.py --ndk /path/to/ndk` prepares the musl loader and
builds the launcher for ARM64 and x86_64. Run after the native addon build and
before final backend packaging. `--resolve-lock` refreshes the package lock from
Alpine's signed indexes using the committed signing keys.

The public `Anthropic.claude-code` extension remains unmodified. Its Alpine
payload runs through the APK's `libclaude-launch.so`, selected by the extension's
`claudeCode.claudeProcessWrapper` setting. The launcher starts the bundled musl
loader with a syscall adapter confined to the Claude process. Android-native
shell, Git and Node child processes do not inherit that adapter.

The launcher and ARM64 syscall adapter derive from VSCodroid commit
`843708384856a8686355ddb75a63e43cce62b2a0`, under the MIT license retained at
`../vendor/VSCodroid-LICENSE.txt`. The adapter also supports x86_64. The bundled
musl copyright and permission notice is copied into the runtime notices. The
Alpine signing keys were obtained from `https://alpinelinux.org/keys/`.

`seccomp-probe.c` checks the x86_64 syscall adapter under a real Linux seccomp
filter. Build it against musl's headers and startup objects, then run it with
the bundled musl loader and `--preload=libseccomp-shim.so`. It checks real path
operations through the allowed `*at` calls, pipe readiness through `ppoll`, the
epoll fallback, invalid timeouts, unknown denied calls, and handler retention.
Android application and extension workflows require separate device checks.
