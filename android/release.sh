#!/bin/bash
# Build a signed release APK: ./release.sh 0.1.0 1
# Needs ~/.config/squelch-android/{android.jks,android.pass} (a copy lives on nas).
set -euo pipefail
cd "$(dirname "$0")"
ver=${1:?version, e.g. 0.1.0}
code=${2:?versionCode, e.g. 1}
key=~/.config/squelch-android
export JAVA_HOME=${JAVA_HOME:-$HOME/.local/android-toolchain/jdk17}
export ANDROID_HOME=${ANDROID_HOME:-$HOME/.local/android-toolchain/sdk}
export SQUELCH_KEYSTORE_PATH=$key/android.jks
export SQUELCH_KEYSTORE_PASSWORD=$(cat "$key/android.pass")
export SQUELCH_KEY_PASSWORD=$SQUELCH_KEYSTORE_PASSWORD
export SQUELCH_VERSION_NAME=$ver SQUELCH_VERSION_CODE=$code
./gradlew -q assembleRelease
out=build/squelch-$ver.apk
mkdir -p build
cp app/build/outputs/apk/release/app-release.apk "$out"
"$ANDROID_HOME"/build-tools/35.0.0/apksigner verify --print-certs "$out" | grep -m1 SHA-256
echo "$out"
