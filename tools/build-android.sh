#!/usr/bin/env bash
set -euo pipefail
vstab_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
: "${ANDROID_HOME:?Set ANDROID_HOME to an Android SDK with platform 36 and build tools}"
: "${JAVA_HOME:?Set JAVA_HOME to JDK 17 or later}"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
exec "$vstab_root/android/gradlew" -p "$vstab_root/android" --no-daemon "${@:-assembleDebug}"
