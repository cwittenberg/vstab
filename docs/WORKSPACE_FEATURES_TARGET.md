# Workspace additions: implementation and proof target

The default remains one full-size workspace. Additional windows are explicitly
opened by the user and may be arranged using Android split screen or desktop
windowing. No second backend, window, or project starts by default.

## Acceptance

1. **Independent windows:** open a second local project or SSH workspace from
   the IDE. Edit and save distinct files in both visible windows. Closing or
   disconnecting one must leave the other usable. Keep remote authentication
   separate between windows. Exercise a single-window cold start too.
2. **Persistent terminals:** create a named real PTY session through the IDE,
   start a command, reload/reconnect, and recover the same process and output.
   Never replay input. List, rename and explicitly stop sessions. Android
   force-stop cannot preserve on-device processes; remote process survival is
   proved separately from local UI reconnection.
3. **Shared projects:** use Android's folder picker. Preserve `.git`, operate
   Git in the app-private working copy, write saves back, and receive external
   edits without reopening. Conflicting edits keep both versions. Test revoked
   permissions, incomplete listings, deletion, traversal and provider failures.
4. **Open with:** select a text/source file in Android Files, open it in vstab,
   edit/save through Code OSS and independently read the provider bytes. Refuse
   missing permissions and preserve concurrent changes. A writable copy alone
   is not successful write-back.
5. **Extension failures:** trigger an actual activation error. Show the
   extension's name and a concise cause, deduplicate repeated messages, expose
   details, and offer SSH guidance for Android native-helper incompatibility.
   An extension that has not activated is not itself an error.

## Evidence

Run meaningful JVM/Node unit tests, Android instrumentation and build/lint.
Capture actual emulator screenshots of the normal user flows, plus independent
file/process checks. Keep device model, APK digest, command transcripts and
remaining limits with the generated reports under ignored `artifacts/`. See
[the demonstration guide](DEMONSTRATION.md). Emulator evidence does not establish
physical Xiaomi behavior.

## Interface

Keep Code OSS's existing visual language and full-size default. Put window,
document and session actions in its command palette and existing explorers.
Use Android's document picker and native dialogs for Android permissions.
Do not add a persistent outer toolbar or replace the editor with a mockup.
