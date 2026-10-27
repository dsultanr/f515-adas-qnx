#!/usr/bin/env bash
# Requires your licensed SNPE SDK and your own representative calibration images.
set -euo pipefail
: "${SNPE_ROOT:?Set SNPE_ROOT to SNPE 1.55.0.2958}"
test "$#" -eq 1 || { echo 'Usage: SNPE_ROOT=/sdk models/rebuild.sh /path/to/1024x512-images' >&2; exit 2; }
base=$(cd "$(dirname "$0")/.." && pwd)
sdk=$(realpath "$SNPE_ROOT")
calib=$(realpath "$1")
image=${SNPE_BUILD_IMAGE:-f515-snpe-converter}
if ! docker image inspect "$image" >/dev/null 2>&1; then docker build -t "$image" "$base/models"; fi
docker run --rm --user "$(id -u):$(id -g)" -v "$base:/work" -v "$sdk:/snpe:ro" -v "$calib:/calibration:ro" \
    -w /work "$image" bash models/conv.sh bash models/rebuild-inside.sh
