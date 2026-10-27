#!/usr/bin/env bash
set -euo pipefail
base=$(cd "$(dirname "$0")/.." && pwd)
image=${QNX_BUILD_IMAGE:-f515-qnx-build}
if ! docker image inspect "$image" >/dev/null 2>&1; then
    docker build -t "$image" "$base/native"
fi
docker run --rm --user "$(id -u):$(id -g)" -v "$base:/work" -w /work "$image" bash native/build-inside.sh
