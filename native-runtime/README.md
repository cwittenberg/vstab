# Legacy Android runtime sources

The Android app now uses the [shared Linux runtime](../linux-runtime/README.md). Follow that directory's build instructions for the bundled kernel, Linux image and generic VM host.

This directory retains the earlier Android-native backend sources and their license notices. The current APK build does not package that backend or use its build pipeline. Extension packages and engines are installed through the public Extensions store.

Android adaptations derive from [VSCodroid](https://github.com/rmyndharis/VSCodroid/tree/843708384856a8686355ddb75a63e43cce62b2a0); retain its [MIT license](vendor/VSCodroid-LICENSE.txt).
