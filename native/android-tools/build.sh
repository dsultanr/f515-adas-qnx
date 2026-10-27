#!/usr/bin/env bash
set -euo pipefail
: "${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME to an Android NDK installation}"
here=$(cd "$(dirname "$0")" && pwd)
out="$here/../build/android-tools"
mkdir -p "$out"
cc="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android26-clang"
for name in frame_server fcw_recv udp_recv; do
    "$cc" -O2 "$here/$name.c" -o "$out/$name"
done
