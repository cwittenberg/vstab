# vstab

<!-- impeccable:product-schema 1 -->

## Platform

android

## Users

The initial user develops on a roughly 13-inch Xiaomi Android tablet and wants a usable VS Code development environment. The exact tablet model, Android/HyperOS version, keyboard and remote host specifications remain to be recorded during implementation.

## Product Purpose

Provide a VS Code workbench on Android with built-in SSH remote development, integrated terminals and support for compatible VS Code extensions.

## Operating Context

The user supplied https://github.com/microsoft/vscode as the editor source foundation. The application must launch the bundled VS Code workbench and support local editing, with native Remote SSH integrated into the IDE. Codex and Claude Code compatibility are explicit requirements. [TARGET.md](TARGET.md) records the clarified implementation target.

## Capabilities and Constraints

- Remote SSH is included, enabled by default and available before opening a workspace. It must not require installing an extension, Termux or a separate SSH client.
- Launch the actual bundled Code OSS workbench without requiring SSH. Support local editing and saving, then connect to a remote project and use its files, tools and integrated terminal in that workbench.
- Local startup, editing, saving and bundled language features must work offline with networking disabled. Android System WebView renders APK assets; a hosted editor or an emulator on the device is not an acceptable dependency.
- Keep compatible extension support in the architecture from the beginning; prioritize Codex and Claude Code after the SSH milestone.
- Microsoft’s official Remote-SSH extension is not a required implementation dependency. An independently implemented or appropriately adapted open-source alternative can provide the workflow.
- A native on-device Node extension host and local PTY terminal are required, following the referenced VSCodroid architecture. A remote operating system is not a substitute for these offline local capabilities.
- Remove the additional outer Android toolbar/frame. SSH actions belong in the Code OSS interface.
- The source includes the bundled workbench, Android-native backend, local editing and SSH integration. Qualification must be tied to a specific APK and the required ordinary app journeys.

## Stack

The implementation combines a bundled Code OSS workbench, Java Android shell, native files and JSch SSH transport with a packaged Android/Bionic Node backend and local PTY support. The browser-worker-only local mode remains a prototype branch. See TARGET.md for the required demonstrations and docs/VSTAB_PLAN.md for the current architecture.

## Brand Commitments

The application name is **vstab**. The workspace directory is currently named `vspad`; that is not the product name.

## Product Principles

- Make SSH remote development a built-in capability.
- Preserve the familiar VS Code editor and developer workflow.
- Keep files, terminals, Git, tasks and extensions attached to the same remote workspace.
- Prove support on the physical tablet and through ordinary product flows.
- Report compatibility per extension and runtime; do not promise all desktop extensions.
