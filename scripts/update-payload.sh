#!/usr/bin/env bash
# Explicitly opt in to replacing the packaged daemon/model. Never runs during build.
set -euo pipefail
base=$(cd "$(dirname "$0")/.." && pwd)
case "${1:-}" in
    native) cp "$base/native/build/fcwd" "$base/app/assets/qnx/fcwd" ;;
    model) cp "$base/models/build/autospeed_heads3o_int8.dlc" "$base/app/assets/qnx/autospeed_heads3o_int8.dlc" ;;
    *) echo 'Usage: scripts/update-payload.sh native|model' >&2; exit 2 ;;
esac
python3 "$base/scripts/qnx-assets.py" --refresh
python3 "$base/scripts/qnx-assets.py"
