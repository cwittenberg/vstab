#!/usr/bin/env bash
set -euo pipefail
vstab_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
: "${ANDROID_HOME:?Set ANDROID_HOME to the Android SDK}"
: "${ANDROID_AVD_HOME:?Set ANDROID_AVD_HOME to the directory containing the vstab AVD}"
vstab_kvm_gid="$(stat -c %g /dev/kvm)"
docker run -d --name vstab-tablet --network host --device /dev/kvm \
  --group-add "$vstab_kvm_gid" --user "$(id -u):$(id -g)" --shm-size=2g \
  -v "$ANDROID_HOME:$ANDROID_HOME:ro" -v "$ANDROID_AVD_HOME:$ANDROID_AVD_HOME" \
  -e ANDROID_HOME -e ANDROID_AVD_HOME -e ANDROID_USER_HOME=/tmp/vstab-android \
  vstab-android-emulator:local "$ANDROID_HOME/emulator/emulator" \
  -avd vstab-api36-tablet -port 5580 -no-window -no-audio -no-snapshot \
  -no-boot-anim -gpu swiftshader -memory 3072 -cores 2 -camera-back none -camera-front none
