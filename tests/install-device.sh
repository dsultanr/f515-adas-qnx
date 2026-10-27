#!/usr/bin/env bash
# Only stages files. On F515 install via the firmware's supported installer/Toolbox.
set -euo pipefail
base=$(cd "$(dirname "$0")/.." && pwd)
args=()
if [ -n "${ADB_SERIAL:-}" ]; then args=(-s "$ADB_SERIAL"); fi
for apk in "$base"/build/F515AdasQnx-*.apk "$base/build/autostart-test/AutostartTest.apk"; do
    test -f "$apk"
    adb "${args[@]}" push "$apk" /sdcard/Download/
done
echo 'Install both APKs from Download using Toolbox, then follow docs/TESTING.md.'
