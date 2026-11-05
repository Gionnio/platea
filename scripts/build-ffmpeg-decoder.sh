#!/bin/bash
# Builds the Media3 ffmpeg audio decoder (TrueHD/MLP, DTS, AC3, E-AC3, FLAC, ALAC) into app/libs/, where the
# app build picks it up (app/libs/ is not in git). Needs: Android NDK r26b (FFmpeg) and 28.2 (Media3 module),
# cmake, ninja, nasm. Sources: androidx/media at the Media3 version of the app, FFmpeg release/6.0 (LGPL build).
set -euo pipefail
WORK=${WORK:-$HOME/Documents/APP/Platea-native}
SDK=${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}
MEDIA3=1.11.1
mkdir -p "$WORK" && cd "$WORK"
[ -d media ] || git clone --depth 1 --branch "$MEDIA3" https://github.com/androidx/media.git media
JNI=media/libraries/decoder_ffmpeg/src/main/jni
[ -d "$JNI/ffmpeg" ] || git clone --depth 1 --branch release/6.0 https://github.com/FFmpeg/FFmpeg.git "$JNI/ffmpeg"
(cd "$JNI" && ./build_ffmpeg.sh "$(cd .. && pwd)" "$SDK/ndk/26.1.10909125" darwin-x86_64 23 truehd mlp dca ac3 eac3 flac alac)
printf "sdk.dir=%s\nndk.dir=%s/ndk/28.2.13676358\n" "$SDK" "$SDK" > media/local.properties
(cd media && JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew :lib-decoder-ffmpeg:assembleRelease)
cp media/libraries/decoder_ffmpeg/buildout/outputs/aar/lib-decoder-ffmpeg-release.aar "$(dirname "$0")/../app/libs/"
echo "app/libs/lib-decoder-ffmpeg-release.aar ready"
